package dev.joycon2.bridge.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.RemoteViews;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

import dev.joycon2.bridge.R;
import dev.joycon2.bridge.output.BridgeMode;
import dev.joycon2.bridge.output.OutputSnapshot;
import dev.joycon2.bridge.output.OutputStage;
import dev.joycon2.bridge.output.ShizukuGamepadBackend;
import dev.joycon2.bridge.ui.MainActivity;

/** Keeps the selected Joy-Con topology alive while a game is in the foreground. */
public final class BridgeService extends Service implements ShizukuGamepadBackend.Listener {
    public interface Listener {
        void onBridgeChanged(BridgeSnapshot snapshot);
    }

    public static final String ACTION_KEEP_ALIVE = "dev.joycon2.bridge.action.KEEP_ALIVE";
    public static final String ACTION_MODE_COMPAT =
            "dev.joycon2.bridge.action.MODE_COMPAT";
    public static final String ACTION_MODE_COMBINED =
            "dev.joycon2.bridge.action.MODE_COMBINED";
    public static final String ACTION_MODE_NATIVE =
            "dev.joycon2.bridge.action.MODE_NATIVE";
    public static final String ACTION_STOP = "dev.joycon2.bridge.action.STOP";

    private static final String CHANNEL_ID = "joycon_bridge";
    private static final String OLD_CHANNEL_ID = "y700_joycon_bridge";
    private static final int NOTIFICATION_ID = 7001;
    private static final int MAX_LOG_LINES = 60;
    private static final String PREFERENCES = "bridge";
    private static final String KEY_MODE = "mode";
    private static final String KEY_SWAP_AB = "swap_ab";
    private static final String KEY_SWAP_XY = "swap_xy";
    private static final String KEY_COMPAT_LEFT_SWAP_AB = "compat_left_swap_ab";
    private static final String KEY_COMPAT_LEFT_SWAP_XY = "compat_left_swap_xy";
    private static final String KEY_COMPAT_RIGHT_SWAP_AB = "compat_right_swap_ab";
    private static final String KEY_COMPAT_RIGHT_SWAP_XY = "compat_right_swap_xy";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private final Deque<String> logLines = new ArrayDeque<>();

    private ShizukuGamepadBackend outputBackend;
    private Listener listener;
    private OutputSnapshot outputSnapshot = OutputSnapshot.initial();
    private boolean foreground;
    private boolean emitPosted;
    private boolean swapAB;
    private boolean swapXY;
    private boolean compatLeftSwapAB;
    private boolean compatLeftSwapXY;
    private boolean compatRightSwapAB;
    private boolean compatRightSwapXY;

    public final class LocalBinder extends Binder {
        public BridgeService service() {
            return BridgeService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        BridgeMode savedMode = BridgeMode.fromCode(preferences.getInt(
                KEY_MODE,
                BridgeMode.NATIVE_DUAL.code()
        ));
        swapAB = preferences.getBoolean(KEY_SWAP_AB, false);
        swapXY = preferences.getBoolean(KEY_SWAP_XY, false);
        compatLeftSwapAB = preferences.getBoolean(KEY_COMPAT_LEFT_SWAP_AB, false);
        compatLeftSwapXY = preferences.getBoolean(KEY_COMPAT_LEFT_SWAP_XY, false);
        compatRightSwapAB = preferences.getBoolean(KEY_COMPAT_RIGHT_SWAP_AB, false);
        compatRightSwapXY = preferences.getBoolean(KEY_COMPAT_RIGHT_SWAP_XY, false);
        appendLog(getString(R.string.log_bridge_started));
        startOutputBackend(
                savedMode,
                swapAB,
                swapXY,
                compatLeftSwapAB,
                compatLeftSwapXY,
                compatRightSwapAB,
                compatRightSwapXY
        );
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_KEEP_ALIVE : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopFromNotification();
            return START_NOT_STICKY;
        }

        enterForeground();
        if (ACTION_MODE_COMPAT.equals(action)) {
            setMode(BridgeMode.COMPAT_DUAL);
        } else if (ACTION_MODE_COMBINED.equals(action)) {
            setMode(BridgeMode.COMBINED);
        } else if (ACTION_MODE_NATIVE.equals(action)) {
            setMode(BridgeMode.NATIVE_DUAL);
        } else if (outputBackend == null) {
            startOutputBackend(savedMode());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (outputBackend != null) {
            outputBackend.stop();
            outputBackend = null;
        }
        listener = null;
        super.onDestroy();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        emitNow();
    }

    public void clearListener(Listener listener) {
        if (this.listener == listener) {
            this.listener = null;
        }
    }

    public void setMode(BridgeMode mode) {
        BridgeMode selected = mode == null ? BridgeMode.NATIVE_DUAL : mode;
        enterForeground();
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putInt(KEY_MODE, selected.code())
                .apply();
        appendLog(getString(R.string.log_request_mode, selected.label()));
        if (outputBackend == null) {
            startOutputBackend(selected);
        } else {
            outputBackend.selectMode(selected);
        }
    }

    public void setButtonSwaps(boolean swapAB, boolean swapXY) {
        if (this.swapAB == swapAB && this.swapXY == swapXY) {
            return;
        }
        this.swapAB = swapAB;
        this.swapXY = swapXY;
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SWAP_AB, swapAB)
                .putBoolean(KEY_SWAP_XY, swapXY)
                .apply();
        appendLog(getString(
                R.string.log_combined_correction,
                logToggle(swapAB),
                logToggle(swapXY)
        ));
        if (outputBackend != null) {
            outputBackend.setButtonSwaps(swapAB, swapXY);
        }
        scheduleEmit();
    }

    public void setCompatButtonSwaps(
            boolean leftSwapAB,
            boolean leftSwapXY,
            boolean rightSwapAB,
            boolean rightSwapXY
    ) {
        if (compatLeftSwapAB == leftSwapAB
                && compatLeftSwapXY == leftSwapXY
                && compatRightSwapAB == rightSwapAB
                && compatRightSwapXY == rightSwapXY) {
            return;
        }
        compatLeftSwapAB = leftSwapAB;
        compatLeftSwapXY = leftSwapXY;
        compatRightSwapAB = rightSwapAB;
        compatRightSwapXY = rightSwapXY;
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_COMPAT_LEFT_SWAP_AB, leftSwapAB)
                .putBoolean(KEY_COMPAT_LEFT_SWAP_XY, leftSwapXY)
                .putBoolean(KEY_COMPAT_RIGHT_SWAP_AB, rightSwapAB)
                .putBoolean(KEY_COMPAT_RIGHT_SWAP_XY, rightSwapXY)
                .apply();
        appendLog(getString(
                R.string.log_compat_correction,
                logToggle(leftSwapAB),
                logToggle(leftSwapXY),
                logToggle(rightSwapAB),
                logToggle(rightSwapXY)
        ));
        if (outputBackend != null) {
            outputBackend.setCompatButtonSwaps(
                    leftSwapAB,
                    leftSwapXY,
                    rightSwapAB,
                    rightSwapXY
            );
        }
        scheduleEmit();
    }

    public void retry() {
        enterForeground();
        if (outputBackend == null) {
            startOutputBackend(savedMode());
        } else {
            outputBackend.retry();
        }
    }

    public boolean testRumble() {
        if (outputBackend == null) {
            startOutputBackend(savedMode());
        }
        return outputBackend != null && outputBackend.testRumble();
    }

    public void refreshPresentation() {
        createNotificationChannel();
        if (foreground) {
            updateNotification();
        }
    }

    @Override
    public void onOutputChanged(OutputSnapshot snapshot) {
        runOnMain(() -> {
            outputSnapshot = snapshot;
            scheduleEmit();
            if (foreground) {
                updateNotification();
            }
        });
    }

    @Override
    public void onOutputLog(String message) {
        runOnMain(() -> appendLog(message));
    }

    private void appendLog(String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> appendLog(message));
            return;
        }
        logLines.addLast(LocalTime.now().format(TIME_FORMAT) + "  " + message);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeFirst();
        }
        scheduleEmit();
    }

    private void scheduleEmit() {
        if (emitPosted) {
            return;
        }
        emitPosted = true;
        mainHandler.postDelayed(() -> {
            emitPosted = false;
            emitNow();
        }, 80L);
    }

    private void emitNow() {
        Listener current = listener;
        if (current != null) {
            current.onBridgeChanged(new BridgeSnapshot(
                    new ArrayList<>(logLines),
                    outputSnapshot,
                    foreground,
                    swapAB,
                    swapXY,
                    compatLeftSwapAB,
                    compatLeftSwapXY,
                    compatRightSwapAB,
                    compatRightSwapXY
            ));
        }
    }

    private void enterForeground() {
        if (foreground) {
            return;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
            foreground = true;
            appendLog(getString(R.string.log_foreground_started));
        } catch (RuntimeException error) {
            appendLog(getString(R.string.log_foreground_failed, safeMessage(error)));
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        manager.deleteNotificationChannel(OLD_CHANNEL_ID);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent activityIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        String state = switch (outputSnapshot.stage()) {
            case ACTIVE -> getString(R.string.notification_active);
            case READY -> getString(R.string.notification_ready);
            case ERROR -> getString(R.string.notification_error);
            default -> getString(R.string.notification_preparing);
        };
        RemoteViews compactControls = buildNotificationControls(
                R.layout.notification_bridge_compact,
                state
        );
        RemoteViews expandedControls = buildNotificationControls(
                R.layout.notification_bridge_expanded,
                state
        );
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(modeLabel(outputSnapshot.mode()))
                .setContentText(state + " · " + getString(
                        R.string.notification_devices_format,
                        deviceCount(outputSnapshot.deviceMask())
                ))
                .setContentIntent(contentIntent)
                .setCustomContentView(compactControls)
                .setCustomBigContentView(expandedControls)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .build();
    }

    private RemoteViews buildNotificationControls(int layoutId, String state) {
        RemoteViews controls = new RemoteViews(getPackageName(), layoutId);
        controls.setTextViewText(R.id.notification_mode, modeLabel(outputSnapshot.mode()));
        controls.setTextViewText(R.id.notification_state, state + " · " + getString(
                R.string.notification_devices_format,
                deviceCount(outputSnapshot.deviceMask())
        ));
        controls.setTextViewText(R.id.notification_compat, getString(
                R.string.notification_action_compat
        ));
        controls.setTextViewText(R.id.notification_combined, getString(
                R.string.notification_action_combined
        ));
        controls.setTextViewText(R.id.notification_native, getString(
                R.string.notification_action_native
        ));
        controls.setTextViewText(R.id.notification_stop, getString(
                R.string.notification_action_stop
        ));
        controls.setOnClickPendingIntent(
                R.id.notification_compat,
                serviceAction(ACTION_MODE_COMPAT, 11)
        );
        controls.setOnClickPendingIntent(
                R.id.notification_combined,
                serviceAction(ACTION_MODE_COMBINED, 12)
        );
        controls.setOnClickPendingIntent(
                R.id.notification_native,
                serviceAction(ACTION_MODE_NATIVE, 13)
        );
        controls.setOnClickPendingIntent(
                R.id.notification_stop,
                serviceAction(ACTION_STOP, 14)
        );
        return controls;
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent intent = new Intent(this, BridgeService.class).setAction(action);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void stopFromNotification() {
        appendLog(getString(R.string.log_notification_stop));
        if (outputBackend != null) {
            outputBackend.stop();
            outputBackend = null;
        }
        outputSnapshot = OutputSnapshot.initial();
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        emitNow();
        stopSelf();
    }

    private void startOutputBackend(BridgeMode mode) {
        startOutputBackend(
                mode,
                swapAB,
                swapXY,
                compatLeftSwapAB,
                compatLeftSwapXY,
                compatRightSwapAB,
                compatRightSwapXY
        );
    }

    private void startOutputBackend(
            BridgeMode mode,
            boolean initialSwapAB,
            boolean initialSwapXY,
            boolean initialCompatLeftSwapAB,
            boolean initialCompatLeftSwapXY,
            boolean initialCompatRightSwapAB,
            boolean initialCompatRightSwapXY
    ) {
        outputBackend = new ShizukuGamepadBackend(this, mainHandler, this);
        outputBackend.start(
                mode,
                initialSwapAB,
                initialSwapXY,
                initialCompatLeftSwapAB,
                initialCompatLeftSwapXY,
                initialCompatRightSwapAB,
                initialCompatRightSwapXY
        );
    }

    private BridgeMode savedMode() {
        return BridgeMode.fromCode(getSharedPreferences(PREFERENCES, MODE_PRIVATE).getInt(
                KEY_MODE,
                BridgeMode.NATIVE_DUAL.code()
        ));
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private static int deviceCount(int mask) {
        return Integer.bitCount(mask & 0x3);
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String logToggle(boolean enabled) {
        return enabled ? "on" : "off";
    }

    private String modeLabel(BridgeMode mode) {
        return getString(switch (mode) {
            case COMPAT_DUAL -> R.string.mode_compat;
            case COMBINED -> R.string.mode_combined;
            case NATIVE_DUAL -> R.string.mode_native;
        });
    }
}
