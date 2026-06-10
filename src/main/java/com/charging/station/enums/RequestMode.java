package com.charging.station.enums;

/**
 * 充电模式枚举
 */
public enum RequestMode {
    FAST("快充", "F"),
    TRICKLE("慢充", "T");

    private final String description;
    private final String prefix;

    RequestMode(String description, String prefix) {
        this.description = description;
        this.prefix = prefix;
    }

    public String getDescription() {
        return description;
    }

    public String getPrefix() {
        return prefix;
    }

    public static RequestMode fromString(String mode) {
        if (mode == null) return null;
        String upper = mode.toUpperCase();
        if (upper.equals("FAST") || upper.equals("F") || upper.equals("快充")) {
            return FAST;
        }
        if (upper.equals("TRICKLE") || upper.equals("T") || upper.equals("慢充")) {
            return TRICKLE;
        }
        throw new IllegalArgumentException("无效的充电模式: " + mode);
    }
}
