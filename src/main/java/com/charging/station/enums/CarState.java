package com.charging.station.enums;

/**
 * 车辆状态枚举
 */
public enum CarState {
    IDLE("空闲"),
    WAITING("等候区排队中"),
    CALLED("已叫号"),
    QUEUED_AT_PILE("充电桩前队列等待中"),
    CHARGING("充电中"),
    FINISHED("已完成"),
    CANCELLED("已取消"),
    INTERRUPTED("故障中断");

    private final String description;

    CarState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
