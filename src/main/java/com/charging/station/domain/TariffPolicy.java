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
    private LocalTime peakStartTime1 = LocalTime.of(10, 0);     // 峰时开始1 10:00
    private LocalTime peakEndTime1 = LocalTime.of(15, 0);       // 峰时结束1 15:00
    private LocalTime peakStartTime2 = LocalTime.of(18, 0);     // 峰时开始2 18:00
    private LocalTime peakEndTime2 = LocalTime.of(21, 0);       // 峰时结束2 21:00
    private LocalTime flatStartTime1 = LocalTime.of(7, 0);      // 平时开始1 07:00
    private LocalTime flatEndTime1 = LocalTime.of(10, 0);       // 平时结束1 10:00
    private LocalTime flatStartTime2 = LocalTime.of(15, 0);     // 平时开始2 15:00
    private LocalTime flatEndTime2 = LocalTime.of(18, 0);       // 平时结束2 18:00
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
        return peakStartTime1;
    }

    public void setPeakStartTime(LocalTime peakStartTime) {
        this.peakStartTime1 = peakStartTime;
    }

    public LocalTime getPeakEndTime() {
        return peakEndTime1;
    }

    public void setPeakEndTime(LocalTime peakEndTime) {
        this.peakEndTime1 = peakEndTime;
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
     * 峰时 [10:00,15:00) ∪ [18:00,21:00)
     * 平时 [07:00,10:00) ∪ [15:00,18:00) ∪ [21:00,23:00)
     * 谷时 [23:00,24:00) ∪ [00:00,07:00)
     * 采用左闭右开区间，避免整点边界被漏判成谷时。
     */
    public Double getPriceByTime(LocalTime time) {
        if (time == null) {
            time = LocalTime.now();
        }
        int s = time.toSecondOfDay();

        // 峰时
        if (inRange(s, 10, 15) || inRange(s, 18, 21)) {
            return peakPrice;
        }

        // 平时（含此前漏掉的 21:00-23:00）
        if (inRange(s, 7, 10) || inRange(s, 15, 18) || inRange(s, 21, 23)) {
            return flatPrice;
        }

        // 谷时（其余时间）
        return valleyPrice;
    }

    /** 判断秒数 s 是否落在 [startHour:00, endHour:00) */
    private static boolean inRange(int s, int startHour, int endHour) {
        return s >= startHour * 3600 && s < endHour * 3600;
    }

    /**
     * 计算充电费用（支持跨时段分段计费）
     */
    public Double calculateChargeFee(Double amount, LocalTime startTime, Double chargePower) {
        if (chargePower == null || chargePower == 0) {
            // 降级：使用起始时间单一电价
            return amount * getPriceByTime(startTime);
        }

        // 计算充电总时长（小时）
        double totalHours = amount / chargePower;

        // 分段计算
        double totalFee = 0.0;
        double remainingHours = totalHours;
        LocalTime currentTime = startTime;

        while (remainingHours > 0.001) { // 精度控制
            // 获取当前时段电价
            Double currentPrice = getPriceByTime(currentTime);

            // 计算到下一个时段边界的时间
            LocalTime nextBoundary = getNextBoundary(currentTime);
            double hoursToNextBoundary = calculateHoursToNext(currentTime, nextBoundary);

            // 本时段实际充电时长
            double hoursInThisPeriod = Math.min(remainingHours, hoursToNextBoundary);

            // 本时段充电量
            double amountInThisPeriod = hoursInThisPeriod * chargePower;

            // 本时段费用
            totalFee += amountInThisPeriod * currentPrice;

            // 更新剩余时间和当前时间
            remainingHours -= hoursInThisPeriod;
            currentTime = nextBoundary;
        }

        return totalFee;
    }

    /**
     * 获取下一个时段边界
     */
    private LocalTime getNextBoundary(LocalTime current) {
        // 所有时段边界：7:00, 10:00, 15:00, 18:00, 21:00, 23:00
        int[] hours = {7, 10, 15, 18, 21, 23};
        int s = current.toSecondOfDay();

        for (int h : hours) {
            if (s < h * 3600) {
                return LocalTime.of(h, 0);
            }
        }

        // 已过当日最后边界（23:00），下一个边界是次日 7:00
        return LocalTime.of(7, 0);
    }

    /**
     * 计算到下一个时间点的小时数
     */
    private double calculateHoursToNext(LocalTime from, LocalTime to) {
        if (to.isAfter(from)) {
            return (to.toSecondOfDay() - from.toSecondOfDay()) / 3600.0;
        } else {
            // 跨天
            return (86400 - from.toSecondOfDay() + to.toSecondOfDay()) / 3600.0;
        }
    }

    /**
     * 计算充电费用（简化版，保持向后兼容）
     */
    public Double calculateChargeFee(Double amount, LocalTime startTime) {
        // 使用起始时间单一电价（向后兼容）
        return amount * getPriceByTime(startTime);
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
