package com.charging.station.domain;

import java.time.LocalDateTime;

/**
 * 调度记录领域对象（对应设计文档 DispatchRecordMapper 保存的调度历史）
 */
public class DispatchRecord {

    private String dispatchId;       // 调度记录编号
    private String dispatchType;     // 调度类型：SINGLE_BATCH_MIN_TOTAL / FULL_STATION_BATCH / FAULT_PRIORITY / FAULT_TIME_ORDER / RECOVER
    private String detail;           // 调度方案摘要
    private Integer carCount;        // 涉及车辆数
    private LocalDateTime createdAt; // 记录时间

    public DispatchRecord() {}

    public DispatchRecord(String dispatchId, String dispatchType, String detail, Integer carCount) {
        this.dispatchId = dispatchId;
        this.dispatchType = dispatchType;
        this.detail = detail;
        this.carCount = carCount;
    }

    public String getDispatchId() { return dispatchId; }
    public void setDispatchId(String dispatchId) { this.dispatchId = dispatchId; }

    public String getDispatchType() { return dispatchType; }
    public void setDispatchType(String dispatchType) { this.dispatchType = dispatchType; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public Integer getCarCount() { return carCount; }
    public void setCarCount(Integer carCount) { this.carCount = carCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
