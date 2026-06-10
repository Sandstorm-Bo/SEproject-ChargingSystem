package com.charging.station.domain;

import java.time.LocalDateTime;

/**
 * 详单领域对象
 * 根据设计文档 3.2 节定义
 */
public class DetailedList {

    private String detailId;                // 详单编号
    private String billId;                  // 所属账单编号
    private String pileId;                  // 充电桩编号
    private Double chargeAmount;            // 充电电量（度）
    private Double chargeDuration;          // 充电时长（分钟）
    private LocalDateTime startTime;        // 开始时间
    private LocalDateTime endTime;          // 结束时间
    private Double chargeFee;               // 充电费用
    private Double serviceFee;              // 服务费用
    private Double subtotalFee;             // 小计费用

    // Constructors
    public DetailedList() {}

    // Getters and Setters
    public String getDetailId() {
        return detailId;
    }

    public void setDetailId(String detailId) {
        this.detailId = detailId;
    }

    public String getBillId() {
        return billId;
    }

    public void setBillId(String billId) {
        this.billId = billId;
    }

    public String getPileId() {
        return pileId;
    }

    public void setPileId(String pileId) {
        this.pileId = pileId;
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

    public Double getSubtotalFee() {
        return subtotalFee;
    }

    public void setSubtotalFee(Double subtotalFee) {
        this.subtotalFee = subtotalFee;
    }

    /**
     * 根据充电会话创建详单
     */
    public static DetailedList createFromSession(ChargingSession session, String billId) {
        DetailedList detail = new DetailedList();
        detail.setBillId(billId);
        detail.setPileId(session.getPileId());
        detail.setChargeAmount(session.getChargeAmount());
        detail.setChargeDuration(session.getChargeDuration());
        detail.setStartTime(session.getStartTime());
        detail.setEndTime(session.getEndTime());
        detail.setChargeFee(session.getChargeFee());
        detail.setServiceFee(session.getServiceFee());
        detail.setSubtotalFee(session.getTotalFee());
        return detail;
    }
}
