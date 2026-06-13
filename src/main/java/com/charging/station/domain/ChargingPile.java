package com.charging.station.domain;

import com.charging.station.enums.PileStatus;
import java.time.LocalDateTime;

/**
 * 充电桩领域对象
 * 根据设计文档 3.1 节定义
 */
public class ChargingPile {

    private String pileId;                    // 充电桩编号
    private String pileNo;                    // 桩号（F1, F2, T1等）
    private String pileType;                  // 快充/慢充
    private PileStatus status;                // 状态
    private Double ratedPower;                // 额定功率（度/小时）
    private Integer queueCapacityM;           // 桩后队列长度 M
    private Boolean isWorking;                // 是否正常工作
    private Integer totalChargeCount;         // 累计充电次数
    private Double totalChargeDuration;       // 累计充电时长（分钟）
    private Double totalChargeAmount;         // 累计充电量（度）
    private Boolean isSchedulable;            // 是否可参与调度
    private String faultReason;               // 故障原因
    private LocalDateTime lastRunTime;        // 最近启动时间
    private LocalDateTime lastStopTime;       // 最近停止时间

    // Constructors
    public ChargingPile() {}

    // Getters and Setters
    public String getPileId() {
        return pileId;
    }

    public void setPileId(String pileId) {
        this.pileId = pileId;
    }

    public String getPileNo() {
        return pileNo;
    }

    public void setPileNo(String pileNo) {
        this.pileNo = pileNo;
    }

    public String getPileType() {
        return pileType;
    }

    public void setPileType(String pileType) {
        this.pileType = pileType;
    }

    public PileStatus getStatus() {
        return status;
    }

    public void setStatus(PileStatus status) {
        this.status = status;
    }

    public Double getRatedPower() {
        return ratedPower;
    }

    public void setRatedPower(Double ratedPower) {
        this.ratedPower = ratedPower;
    }

    public Integer getQueueCapacityM() {
        return queueCapacityM;
    }

    public void setQueueCapacityM(Integer queueCapacityM) {
        this.queueCapacityM = queueCapacityM;
    }

    public Boolean getIsWorking() {
        return isWorking;
    }

    public void setIsWorking(Boolean isWorking) {
        this.isWorking = isWorking;
    }

    public Integer getTotalChargeCount() {
        return totalChargeCount;
    }

    public void setTotalChargeCount(Integer totalChargeCount) {
        this.totalChargeCount = totalChargeCount;
    }

    public Double getTotalChargeDuration() {
        return totalChargeDuration;
    }

    public void setTotalChargeDuration(Double totalChargeDuration) {
        this.totalChargeDuration = totalChargeDuration;
    }

    public Double getTotalChargeAmount() {
        return totalChargeAmount;
    }

    public void setTotalChargeAmount(Double totalChargeAmount) {
        this.totalChargeAmount = totalChargeAmount;
    }

    public Boolean getIsSchedulable() {
        return isSchedulable;
    }

    public void setIsSchedulable(Boolean isSchedulable) {
        this.isSchedulable = isSchedulable;
    }

    public String getFaultReason() {
        return faultReason;
    }

    public void setFaultReason(String faultReason) {
        this.faultReason = faultReason;
    }

    public LocalDateTime getLastRunTime() {
        return lastRunTime;
    }

    public void setLastRunTime(LocalDateTime lastRunTime) {
        this.lastRunTime = lastRunTime;
    }

    public LocalDateTime getLastStopTime() {
        return lastStopTime;
    }

    public void setLastStopTime(LocalDateTime lastStopTime) {
        this.lastStopTime = lastStopTime;
    }

    /**
     * 启动充电桩
     */
    public void powerOn() {
        if (this.status == PileStatus.FAULT) {
            throw new IllegalStateException("故障桩不能启动");
        }
        this.status = PileStatus.POWERED;
    }

    /**
     * 运行充电桩
     */
    public void run() {
        if (this.status == PileStatus.FAULT) {
            throw new IllegalStateException("故障桩不能运行");
        }
        this.status = PileStatus.IDLE;
        this.isSchedulable = true;
        this.lastRunTime = com.charging.station.util.SimClock.nowVirtual();
    }

    /**
     * 关闭充电桩
     */
    public void powerOff() {
        this.status = PileStatus.OFFLINE;
        this.isSchedulable = false;
        this.lastStopTime = com.charging.station.util.SimClock.nowVirtual();
    }

    /**
     * 标记为故障
     */
    public void markFault(String reason) {
        this.status = PileStatus.FAULT;
        this.isSchedulable = false;
        this.faultReason = reason;
    }

    /**
     * 从故障中恢复
     */
    public void recover() {
        this.status = PileStatus.IDLE;
        this.isSchedulable = true;
        this.faultReason = null;
    }

    /**
     * 判断是否可以接受新车辆
     */
    public boolean canAcceptNewVehicle() {
        return this.isSchedulable
            && (this.status == PileStatus.IDLE || this.status == PileStatus.BUSY)
            && this.status != PileStatus.FAULT
            && this.status != PileStatus.OFFLINE;
    }

    /**
     * 开始充电（状态变更）
     */
    public void startCharging() {
        this.status = PileStatus.BUSY;
    }

    /**
     * 结束充电（状态变更）
     */
    public void finishCharging(Double chargedAmount, Double duration) {
        this.status = PileStatus.IDLE;
        this.totalChargeCount++;
        this.totalChargeAmount += chargedAmount;
        this.totalChargeDuration += duration;
    }

    /**
     * 设置参数
     */
    public void setParameters(Double powerKw) {
        if (powerKw == null || powerKw <= 0) {
            throw new IllegalArgumentException("充电桩功率必须大于 0");
        }
        this.ratedPower = powerKw;
    }
}
