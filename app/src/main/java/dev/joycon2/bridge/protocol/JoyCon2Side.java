package dev.joycon2.bridge.protocol;

import java.util.Locale;

public enum JoyCon2Side {
    LEFT("Links"),
    RIGHT("Rechts"),
    UNKNOWN("Seite unbekannt");

    private final String germanLabel;

    JoyCon2Side(String germanLabel) {
        this.germanLabel = germanLabel;
    }

    public String germanLabel() {
        return germanLabel;
    }

    public static JoyCon2Side fromName(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.contains("(l)") || normalized.contains(" left") || normalized.endsWith(" l")) {
            return LEFT;
        }
        if (normalized.contains("(r)") || normalized.contains(" right") || normalized.endsWith(" r")) {
            return RIGHT;
        }
        return UNKNOWN;
    }

    public static JoyCon2Side fromProductId(int productId) {
        return switch (productId & 0xFFFF) {
            case 0x6605, 0x2006, 0x0620 -> LEFT;
            case 0x6705, 0x2007, 0x0720 -> RIGHT;
            default -> UNKNOWN;
        };
    }
}
