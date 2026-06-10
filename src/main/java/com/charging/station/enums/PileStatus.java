package com.charging.station.enums;

/**
 * 充电桩状态枚举
 */
public enum PileStatus {
    OFFLINE("离线"),
    POWERED("已上电"),
    IDLE("空闲"),
    BUSY("忙碌"),
    FAULT("故障"),
    CLOSED("关闭");

    private final String description;

    PileStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
