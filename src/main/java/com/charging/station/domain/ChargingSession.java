package com.charging.station.domain;

import java.time.LocalDateTime;

/**
 * 充电会话领域对象
 * 根据设计文档 3.1 节定义
 */
public class ChargingSession {

    private String sessionId;              // 会话编号
    private String carId;                  // 车辆编号
    private String pileId;                 // 充电桩编号
    private Double requestAmount;          // 请求充电量
    private LocalDateTime startTime;       // 开始时间
    private LocalDateTime endTime;         // 结束时间
    private String sessionStatus;          // 进行中/已完成/中断
    private Double chargeAmount;           // 实际充电量
    private Double chargeDuration;         // 充电时长（分钟）
    private Double chargeFee;              // 充电费用
    private Double serviceFee;             // 服务费用
    private Double totalFee;               // 总费用

    // Constructors
    public ChargingSession() {}

    // Getters and Setters
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getCarId() {
        return carId;
    }

    public void setCarId(String carId) {
        this.carId = carId;
    }

    public String getPileId() {
        return pileId;
    }

    public void setPileId(String pileId) {
        this.pileId = pileId;
    }

    public Double getRequestAmount() {
        return requestAmount;
    }

    public void setRequestAmount(Double requestAmount) {
        this.requestAmount = requestAmount;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public String getSessionStatus() {
        return sessionStatus;
    }

    public void setSessionStatus(String sessionStatus) {
        this.sessionStatus = sessionStatus;
    }

    public Double getChargeAmount() {
        return chargeAmount;
    }

    public void setChargeAmount(Double chargeAmount) {
        this.chargeAmount = chargeAmount;
    }

    public Double getChargeDuration() {
        return chargeDuration;
    }

    public void setChargeDuration(Double chargeDuration) {
        this.chargeDuration = chargeDuration;
    }

    public Double getChargeFee() {
        return chargeFee;
    }

    public void setChargeFee(Double chargeFee) {
        this.chargeFee = chargeFee;
    }

    public Double getServiceFee() {
        return serviceFee;
    }

    public void setServiceFee(Double serviceFee) {
        this.serviceFee = serviceFee;
    }

    public Double getTotalFee() {
        return totalFee;
    }

    public void setTotalFee(Double totalFee) {
        this.totalFee = totalFee;
    }

    /**
     * 时间加速因子：1 真实秒 = TIME_ACCELERATION 仿真秒。
     * 仅由 {@link com.charging.station.util.SimClock} 用于推进虚拟时钟；取 120 时，
     * 一辆快充充满 30 度（30kW 需 1 仿真小时）约 30 真实秒完成。
     * <p>注意：充电量/时长由“虚拟时间差”直接推导，本字段不再在此处二次相乘，
     * 否则会与已是加速时间轴的虚拟时刻叠加导致双重加速。
     */
    public static double TIME_ACCELERATION = 120.0;

    /**
     * 自开始充电以来经过的“仿真小时数”。
     * startTime 与 currentTime 都已是 {@link com.charging.station.util.SimClock} 的虚拟时刻，
     * 其差值本身即为仿真时长，直接换算成小时即可（不再乘加速因子）。
     */
    private double simulatedHours(LocalDateTime currentTime) {
        if (startTime == null || currentTime == null) {
            return 0.0;
        }
        double virtualSeconds = java.time.Duration.between(startTime, currentTime).toMillis() / 1000.0;
        // MySQL DATETIME 回读可能有最多 0.5s 误差，经过时间必须钳为非负，
        // 否则充电量为负、故障中断时“剩余电量”会大于原请求量
        return Math.max(0.0, virtualSeconds) / 3600.0;
    }

    /**
     * 计算实时充电量（基于功率和加速后的时长，封顶为请求量）
     */
    public Double calculateCurrentAmount(Double powerKw, LocalDateTime currentTime) {
        if (powerKw == null) {
            return 0.0;
        }
        double charged = powerKw * simulatedHours(currentTime);
        return Math.min(charged, requestAmount);
    }

    /**
     * 计算实时充电时长（模拟分钟）
     */
    public Double calculateCurrentDuration(LocalDateTime currentTime) {
        return simulatedHours(currentTime) * 60.0;
    }

    /**
     * 结束会话
     */
    public void finish(Double finalAmount, Double finalDuration, Double chargeFee, Double serviceFee) {
        // 结束时刻由「开始时刻 + 充电时长」推导，确保详单的起止时刻之差严格等于充电时长。
        // 不直接取当前虚拟时刻：秒级调度器最多滞后一个 tick 才发现“已充满”，会让结束时刻比真正充满
        // 晚最多一个仿真 tick，造成“时长对、时刻差几分钟”的不自洽。
        this.endTime = endTimeFromDuration(finalDuration);
        this.chargeAmount = finalAmount;
        this.chargeDuration = finalDuration;
        this.chargeFee = chargeFee;
        this.serviceFee = serviceFee;
        this.totalFee = chargeFee + serviceFee;
        this.sessionStatus = "已完成";
    }

    /**
     * 中断会话（故障）
     */
    public void interrupt(Double currentAmount, Double currentDuration, Double chargeFee, Double serviceFee) {
        // 同 finish：结束时刻 = 开始时刻 + 已充时长，保证详单起止时刻与时长自洽
        this.endTime = endTimeFromDuration(currentDuration);
        this.chargeAmount = currentAmount;
        this.chargeDuration = currentDuration;
        this.chargeFee = chargeFee;
        this.serviceFee = serviceFee;
        this.totalFee = chargeFee + serviceFee;
        this.sessionStatus = "中断";
    }

    /** 开始时刻 + 时长(分钟) → 结束时刻（虚拟时间轴）；缺开始时刻时退化为当前虚拟时刻 */
    private LocalDateTime endTimeFromDuration(Double durationMinutes) {
        if (startTime == null || durationMinutes == null) {
            return com.charging.station.util.SimClock.nowVirtual();
        }
        return startTime.plus(java.time.Duration.ofMillis(Math.round(durationMinutes * 60000.0)));
    }
}
