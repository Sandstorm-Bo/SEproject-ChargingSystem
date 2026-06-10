package com.charging.station.domain;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 计费策略领域对象
 * 根据设计文档 3.1 节定义
 */
public class TariffPolicy {

    private String policyId;                // 策略编号
    private String status;                  // 配置状态
    private LocalDateTime updateTime;       // 最近更新时间

    // 峰平谷电价
    private Double peakPrice;               // 峰时电价（元/度）
    private Double flatPrice;               // 平时电价（元/度）
    private Double valleyPrice;             // 谷时电价（元/度）

    // 时段配置（默认）
    private LocalTime peakStartTime = LocalTime.of(10, 0);      // 峰时开始 10:00
    private LocalTime peakEndTime = LocalTime.of(15, 0);        // 峰时结束 15:00
    private LocalTime flatStartTime1 = LocalTime.of(7, 0);      // 平时开始1 07:00
    private LocalTime flatEndTime1 = LocalTime.of(10, 0);       // 平时结束1 10:00
    private LocalTime flatStartTime2 = LocalTime.of(15, 0);     // 平时开始2 15:00
    private LocalTime flatEndTime2 = LocalTime.of(21, 0);       // 平时结束2 21:00
    // 谷时：21:00-07:00（其他时间）

    // 服务费单价
    private Double serviceFeePerKwh;        // 服务费（元/度）

    // Constructors
    public TariffPolicy() {}

    // Getters and Setters
    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Double getPeakPrice() {
        return peakPrice;
    }

    public void setPeakPrice(Double peakPrice) {
        this.peakPrice = peakPrice;
    }

    public Double getFlatPrice() {
        return flatPrice;
    }

    public void setFlatPrice(Double flatPrice) {
        this.flatPrice = flatPrice;
    }

    public Double getValleyPrice() {
        return valleyPrice;
    }

    public void setValleyPrice(Double valleyPrice) {
        this.valleyPrice = valleyPrice;
    }

    public LocalTime getPeakStartTime() {
        return peakStartTime;
    }

    public void setPeakStartTime(LocalTime peakStartTime) {
        this.peakStartTime = peakStartTime;
    }

    public LocalTime getPeakEndTime() {
        return peakEndTime;
    }

    public void setPeakEndTime(LocalTime peakEndTime) {
        this.peakEndTime = peakEndTime;
    }

    public LocalTime getFlatStartTime1() {
        return flatStartTime1;
    }

    public void setFlatStartTime1(LocalTime flatStartTime1) {
        this.flatStartTime1 = flatStartTime1;
    }

    public LocalTime getFlatEndTime1() {
        return flatEndTime1;
    }

    public void setFlatEndTime1(LocalTime flatEndTime1) {
        this.flatEndTime1 = flatEndTime1;
    }

    public LocalTime getFlatStartTime2() {
        return flatStartTime2;
    }

    public void setFlatStartTime2(LocalTime flatStartTime2) {
        this.flatStartTime2 = flatStartTime2;
    }

    public LocalTime getFlatEndTime2() {
        return flatEndTime2;
    }

    public void setFlatEndTime2(LocalTime flatEndTime2) {
        this.flatEndTime2 = flatEndTime2;
    }

    public Double getServiceFeePerKwh() {
        return serviceFeePerKwh;
    }

    public void setServiceFeePerKwh(Double serviceFeePerKwh) {
        this.serviceFeePerKwh = serviceFeePerKwh;
    }

    /**
     * 根据时间判断当前时段的电价
     */
    public Double getPriceByTime(LocalTime time) {
        if (time == null) {
            time = LocalTime.now();
        }

        // 峰时
        if (time.isAfter(peakStartTime) && time.isBefore(peakEndTime)) {
            return peakPrice;
        }

        // 平时
        if ((time.isAfter(flatStartTime1) && time.isBefore(flatEndTime1)) ||
            (time.isAfter(flatStartTime2) && time.isBefore(flatEndTime2))) {
            return flatPrice;
        }

        // 谷时（其他时间）
        return valleyPrice;
    }

    /**
     * 计算充电费用（按时段加权平均）
     * 简化版：假设整个充电时段使用同一电价
     */
    public Double calculateChargeFee(Double amount, LocalTime startTime) {
        Double pricePerKwh = getPriceByTime(startTime);
        return amount * pricePerKwh;
    }

    /**
     * 计算服务费
     */
    public Double calculateServiceFee(Double amount) {
        return amount * serviceFeePerKwh;
    }

    /**
     * 计算总费用
     */
    public Double calculateTotalFee(Double amount, LocalTime startTime) {
        Double chargeFee = calculateChargeFee(amount, startTime);
        Double serviceFee = calculateServiceFee(amount);
        return chargeFee + serviceFee;
    }
}
