package dev.joycon2.bridge.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import dev.joycon2.bridge.BuildConfig;
import dev.joycon2.bridge.R;
import dev.joycon2.bridge.output.BridgeMode;
import dev.joycon2.bridge.output.JoyConEvdevMapper;
import dev.joycon2.bridge.output.OutputSnapshot;
import dev.joycon2.bridge.output.OutputStage;
import dev.joycon2.bridge.service.BridgeService;
import dev.joycon2.bridge.service.BridgeSnapshot;

/** Material 3 controller mode switcher with localized user and developer surfaces. */
public final class MainActivity extends AppCompatActivity implements BridgeService.Listener {
    private static final String UI_PREFERENCES = "joycon_bridge_ui";
    private static final String KEY_DEVELOPER_MODE = "developer_mode";
    private static final String SOURCE_URL =
            "https://github.com/zzsmoky/Joy-con-Bridge-for-Android";
    private static final String[] LANGUAGE_TAGS = {"", "zh-CN", "en", "ja"};

    private final Map<BridgeMode, MaterialButton> modeButtons =
            new EnumMap<>(BridgeMode.class);

    private BridgeService bridgeService;
    private boolean bound;
    private boolean renderingButtonSwaps;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView deviceLine;
    private TextView metaLine;
    private TextView logView;
    private MaterialButton retryButton;
    private MaterialButton vibrationTestButton;
    private MaterialSwitch swapABSwitch;
    private MaterialSwitch swapXYSwitch;
    private MaterialSwitch compatLeftSwapABSwitch;
    private MaterialSwitch compatLeftSwapXYSwitch;
    private MaterialSwitch compatRightSwapABSwitch;
    private MaterialSwitch compatRightSwapXYSwitch;
    private MaterialSwitch developerModeSwitch;
    private View compatCorrectionHeader;
    private View compatCorrectionContent;
    private ImageView compatCorrectionIndicator;
    private View combinedCorrectionHeader;
    private View combinedCorrectionContent;
    private ImageView combinedCorrectionIndicator;
    private View developerContainer;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bridgeService = ((BridgeService.LocalBinder) binder).service();
            renderWaiting(false);
            bridgeService.refreshPresentation();
            bridgeService.setListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridgeService = null;
            renderWaiting(true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        int surfaceColor = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSurface,
                Color.BLACK
        );
        boolean lightSystemBars = MaterialColors.isColorLight(surfaceColor);
        getWindow().setStatusBarColor(surfaceColor);
        getWindow().setNavigationBarColor(surfaceColor);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(lightSystemBars);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(lightSystemBars);
        setContentView(R.layout.activity_main);

        bindViews();
        configureTopBar();
        configureControls();

        Intent keepAlive = new Intent(this, BridgeService.class)
                .setAction(BridgeService.ACTION_KEEP_ALIVE);
        startForegroundService(keepAlive);
        bound = bindService(
                new Intent(this, BridgeService.class),
                serviceConnection,
                Context.BIND_AUTO_CREATE
        );

        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7002);
        }
    }

    @Override
    protected void onDestroy() {
        if (bridgeService != null) {
            bridgeService.clearListener(this);
        }
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
        bridgeService = null;
        super.onDestroy();
    }

    private void bindViews() {
        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        deviceLine = findViewById(R.id.device_line);
        metaLine = findViewById(R.id.meta_line);
        logView = findViewById(R.id.log_view);
        retryButton = findViewById(R.id.retry_button);
        vibrationTestButton = findViewById(R.id.vibration_test_button);
        swapABSwitch = findViewById(R.id.combined_swap_ab);
        swapXYSwitch = findViewById(R.id.combined_swap_xy);
        compatLeftSwapABSwitch = findViewById(R.id.compat_left_swap_ab);
        compatLeftSwapXYSwitch = findViewById(R.id.compat_left_swap_xy);
        compatRightSwapABSwitch = findViewById(R.id.compat_right_swap_ab);
        compatRightSwapXYSwitch = findViewById(R.id.compat_right_swap_xy);
        compatCorrectionHeader = findViewById(R.id.compat_correction_header);
        compatCorrectionContent = findViewById(R.id.compat_correction_content);
        compatCorrectionIndicator = findViewById(R.id.compat_correction_indicator);
        combinedCorrectionHeader = findViewById(R.id.combined_correction_header);
        combinedCorrectionContent = findViewById(R.id.combined_correction_content);
        combinedCorrectionIndicator = findViewById(R.id.combined_correction_indicator);
        developerModeSwitch = findViewById(R.id.developer_mode_switch);
        developerContainer = findViewById(R.id.developer_container);

        modeButtons.put(BridgeMode.COMPAT_DUAL, findViewById(R.id.mode_compat_button));
        modeButtons.put(BridgeMode.COMBINED, findViewById(R.id.mode_combined_button));
        modeButtons.put(BridgeMode.NATIVE_DUAL, findViewById(R.id.mode_native_button));
    }

    private void configureTopBar() {
        MaterialToolbar toolbar = findViewById(R.id.top_app_bar);
        MenuItem languageItem = toolbar.getMenu()
                .add(R.string.switch_language)
                .setIcon(R.drawable.ic_language);
        languageItem.setShowAsAction(
                MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT
        );
        toolbar.setOnMenuItemClickListener(item -> {
            showLanguageDialog();
            return true;
        });
    }

    private void configureControls() {
        configureCorrectionSection(
                compatCorrectionHeader,
                compatCorrectionContent,
                compatCorrectionIndicator
        );
        configureCorrectionSection(
                combinedCorrectionHeader,
                combinedCorrectionContent,
                combinedCorrectionIndicator
        );

        TextView aboutVersion = findViewById(R.id.about_version);
        aboutVersion.setText(getString(R.string.about_version_format, BuildConfig.VERSION_NAME));
        findViewById(R.id.about_source_button).setOnClickListener(view -> startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))
        ));

        modeButtons.get(BridgeMode.COMPAT_DUAL)
                .setOnClickListener(view -> selectMode(BridgeMode.COMPAT_DUAL));
        modeButtons.get(BridgeMode.COMBINED)
                .setOnClickListener(view -> selectMode(BridgeMode.COMBINED));
        modeButtons.get(BridgeMode.NATIVE_DUAL)
                .setOnClickListener(view -> selectMode(BridgeMode.NATIVE_DUAL));

        retryButton.setOnClickListener(view -> {
            if (bridgeService != null) {
                bridgeService.retry();
            }
        });

        vibrationTestButton.setOnClickListener(view -> {
            if (bridgeService == null) {
                Toast.makeText(this, R.string.service_connecting_toast, Toast.LENGTH_SHORT).show();
                return;
            }
            boolean started = bridgeService.testRumble();
            Toast.makeText(
                    this,
                    started ? R.string.vibration_test_started : R.string.vibration_test_unavailable,
                    Toast.LENGTH_SHORT
            ).show();
        });

        swapABSwitch.setOnCheckedChangeListener((button, checked) -> updateButtonSwaps());
        swapXYSwitch.setOnCheckedChangeListener((button, checked) -> updateButtonSwaps());
        compatLeftSwapABSwitch.setOnCheckedChangeListener(
                (button, checked) -> updateCompatButtonSwaps());
        compatLeftSwapXYSwitch.setOnCheckedChangeListener(
                (button, checked) -> updateCompatButtonSwaps());
        compatRightSwapABSwitch.setOnCheckedChangeListener(
                (button, checked) -> updateCompatButtonSwaps());
        compatRightSwapXYSwitch.setOnCheckedChangeListener(
                (button, checked) -> updateCompatButtonSwaps());

        SharedPreferences preferences = getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE);
        boolean developerMode = preferences.getBoolean(KEY_DEVELOPER_MODE, false);
        developerModeSwitch.setChecked(developerMode);
        developerContainer.setVisibility(developerMode ? View.VISIBLE : View.GONE);
        developerModeSwitch.setOnCheckedChangeListener((button, checked) -> {
            developerContainer.setVisibility(checked ? View.VISIBLE : View.GONE);
            preferences.edit().putBoolean(KEY_DEVELOPER_MODE, checked).apply();
        });
    }

    private void configureCorrectionSection(View header, View content, ImageView indicator) {
        setCorrectionSectionExpanded(header, content, indicator, false, false);
        header.setOnClickListener(view -> setCorrectionSectionExpanded(
                header,
                content,
                indicator,
                content.getVisibility() != View.VISIBLE,
                true
        ));
    }

    private void setCorrectionSectionExpanded(
            View header,
            View content,
            ImageView indicator,
            boolean expanded,
            boolean animate
    ) {
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        float rotation = expanded ? 180f : 0f;
        if (animate) {
            indicator.animate().rotation(rotation).setDuration(160L).start();
        } else {
            indicator.setRotation(rotation);
        }
        header.setContentDescription(getString(expanded
                ? R.string.collapse_button_corrections
                : R.string.expand_button_corrections));
    }

    private void showLanguageDialog() {
        String[] names = getResources().getStringArray(R.array.language_names);
        int checked = currentLanguageIndex();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.choose_language)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which != checked) {
                        AppCompatDelegate.setApplicationLocales(
                                which == 0
                                        ? LocaleListCompat.getEmptyLocaleList()
                                        : LocaleListCompat.forLanguageTags(LANGUAGE_TAGS[which])
                        );
                    }
                })
                .show();
    }

    private int currentLanguageIndex() {
        LocaleListCompat selected = AppCompatDelegate.getApplicationLocales();
        if (selected.isEmpty()) {
            return 0;
        }
        Locale locale = selected.get(0);
        String language = locale == null ? "" : locale.getLanguage();
        if ("ja".equals(language)) {
            return 3;
        }
        if ("en".equals(language)) {
            return 2;
        }
        return 1;
    }

    private void selectMode(BridgeMode mode) {
        if (bridgeService == null) {
            Toast.makeText(this, R.string.service_connecting_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        bridgeService.setMode(mode);
    }

    private void updateButtonSwaps() {
        if (renderingButtonSwaps || bridgeService == null) {
            return;
        }
        bridgeService.setButtonSwaps(swapABSwitch.isChecked(), swapXYSwitch.isChecked());
    }

    private void updateCompatButtonSwaps() {
        if (renderingButtonSwaps || bridgeService == null) {
            return;
        }
        bridgeService.setCompatButtonSwaps(
                compatLeftSwapABSwitch.isChecked(),
                compatLeftSwapXYSwitch.isChecked(),
                compatRightSwapABSwitch.isChecked(),
                compatRightSwapXYSwitch.isChecked()
        );
    }

    @Override
    public void onBridgeChanged(BridgeSnapshot snapshot) {
        OutputSnapshot output = snapshot.output();
        statusTitle.setText(stageTitle(output.stage(), output.mode()));
        statusTitle.setTextColor(stageColor(output.stage()));
        statusDetail.setText(output.detail());

        boolean left = (output.deviceMask() & JoyConEvdevMapper.SIDE_LEFT) != 0;
        boolean right = (output.deviceMask() & JoyConEvdevMapper.SIDE_RIGHT) != 0;
        deviceLine.setText(getString(
                R.string.controller_status_format,
                getString(left ? R.string.status_connected : R.string.status_disconnected),
                getString(right ? R.string.status_connected : R.string.status_disconnected)
        ));
        String uid = output.shizukuUid() < 0 ? "—" : Integer.toString(output.shizukuUid());
        metaLine.setText(getString(
                R.string.developer_session_format,
                uid,
                getString(output.grabbed() ? R.string.grabbed : R.string.released),
                output.injectedEvents()
        ));
        logView.setText(snapshot.logLines().isEmpty()
                ? getString(R.string.developer_no_logs)
                : String.join("\n", snapshot.logLines()));

        renderingButtonSwaps = true;
        swapABSwitch.setChecked(snapshot.swapAB());
        swapXYSwitch.setChecked(snapshot.swapXY());
        compatLeftSwapABSwitch.setChecked(snapshot.compatLeftSwapAB());
        compatLeftSwapXYSwitch.setChecked(snapshot.compatLeftSwapXY());
        compatRightSwapABSwitch.setChecked(snapshot.compatRightSwapAB());
        compatRightSwapXYSwitch.setChecked(snapshot.compatRightSwapXY());
        renderingButtonSwaps = false;

        for (Map.Entry<BridgeMode, MaterialButton> entry : modeButtons.entrySet()) {
            boolean selected = entry.getKey() == output.mode();
            entry.getValue().setText(selected ? R.string.current_mode : R.string.switch_mode);
            entry.getValue().setEnabled(!selected);
        }
        retryButton.setEnabled(output.stage() != OutputStage.BINDING);
    }

    private void renderWaiting(boolean disconnected) {
        statusTitle.setText(disconnected
                ? R.string.status_service_disconnected
                : R.string.status_service_connected);
        statusTitle.setTextColor(MaterialColors.getColor(
                this,
                disconnected
                        ? androidx.appcompat.R.attr.colorError
                        : com.google.android.material.R.attr.colorSecondary,
                Color.WHITE
        ));
    }

    private String stageTitle(OutputStage stage, BridgeMode mode) {
        return switch (stage) {
            case ACTIVE -> getString(R.string.status_active_format, modeLabel(mode));
            case READY -> getString(R.string.status_ready_format, modeLabel(mode));
            case BINDING -> getString(R.string.status_switching);
            case PERMISSION_REQUIRED -> getString(R.string.status_permission);
            case SHIZUKU_STOPPED -> getString(R.string.status_shizuku_stopped);
            case ERROR -> getString(R.string.status_error);
        };
    }

    private String modeLabel(BridgeMode mode) {
        return getString(switch (mode) {
            case COMPAT_DUAL -> R.string.mode_compat;
            case COMBINED -> R.string.mode_combined;
            case NATIVE_DUAL -> R.string.mode_native;
        });
    }

    private int stageColor(OutputStage stage) {
        int colorAttribute = switch (stage) {
            case ACTIVE, READY -> com.google.android.material.R.attr.colorTertiary;
            case ERROR -> androidx.appcompat.R.attr.colorError;
            case BINDING, PERMISSION_REQUIRED, SHIZUKU_STOPPED ->
                    com.google.android.material.R.attr.colorSecondary;
        };
        return MaterialColors.getColor(this, colorAttribute, Color.WHITE);
    }
}
