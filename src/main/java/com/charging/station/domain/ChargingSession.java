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
     * 演示用时间加速因子：1 真实秒 = TIME_ACCELERATION 模拟秒。
     * 取 120 时，一辆快充充满 30 度（30kW 需 1 模拟小时）约 30 真实秒完成。
     */
    public static double TIME_ACCELERATION = 120.0;

    /** 自开始充电以来经过的“模拟小时数” */
    private double simulatedHours(LocalDateTime currentTime) {
        if (startTime == null || currentTime == null) {
            return 0.0;
        }
        double realSeconds = java.time.Duration.between(startTime, currentTime).toMillis() / 1000.0;
        return realSeconds * TIME_ACCELERATION / 3600.0;
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
        this.endTime = LocalDateTime.now();
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
        this.endTime = LocalDateTime.now();
        this.chargeAmount = currentAmount;
        this.chargeDuration = currentDuration;
        this.chargeFee = chargeFee;
        this.serviceFee = serviceFee;
        this.totalFee = chargeFee + serviceFee;
        this.sessionStatus = "中断";
    }
}
