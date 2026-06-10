package com.charging.station.domain;

import java.time.LocalDateTime;

/**
 * 账单领域对象
 * 根据设计文档 3.2 节定义
 */
public class Bill {

    private String billId;                  // 账单编号
    private String carId;                   // 车辆编号
    private LocalDateTime date;             // 账单日期
    private Double totalChargeFee;          // 总充电费用
    private Double totalServiceFee;         // 总服务费用
    private Double totalFee;                // 总费用
    private String billStatus;              // 账单状态

    // Constructors
    public Bill() {}

    // Getters and Setters
    public String getBillId() {
        return billId;
    }

    public void setBillId(String billId) {
        this.billId = billId;
    }

    public String getCarId() {
        return carId;
    }

    public void setCarId(String carId) {
        this.carId = carId;
    }

    public LocalDateTime getDate() {
        return date;
    }

    public void setDate(LocalDateTime date) {
        this.date = date;
    }

    public Double getTotalChargeFee() {
        return totalChargeFee;
    }

    public void setTotalChargeFee(Double totalChargeFee) {
        this.totalChargeFee = totalChargeFee;
    }

    public Double getTotalServiceFee() {
        return totalServiceFee;
    }

    public void setTotalServiceFee(Double totalServiceFee) {
        this.totalServiceFee = totalServiceFee;
    }

    public Double getTotalFee() {
        return totalFee;
    }

    public void setTotalFee(Double totalFee) {
        this.totalFee = totalFee;
    }

    public String getBillStatus() {
        return billStatus;
    }

    public void setBillStatus(String billStatus) {
        this.billStatus = billStatus;
    }

    /**
     * 根据充电会话创建账单
     */
    public static Bill createFromSession(ChargingSession session) {
        Bill bill = new Bill();
        bill.setCarId(session.getCarId());
        bill.setDate(session.getEndTime());
        bill.setTotalChargeFee(session.getChargeFee());
        bill.setTotalServiceFee(session.getServiceFee());
        bill.setTotalFee(session.getTotalFee());
        bill.setBillStatus("已生成");
        return bill;
    }
}
