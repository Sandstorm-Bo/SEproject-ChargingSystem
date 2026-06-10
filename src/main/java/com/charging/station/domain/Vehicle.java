package com.charging.station.domain;

/**
 * 车辆领域对象
 * 根据设计文档 3.1 节定义
 */
public class Vehicle {

    private String vehicleId;          // 车辆编号
    private String plateNumber;        // 车牌号
    private String vehicleType;        // 车辆类型
    private Double batteryCapacity;    // 电池总容量（度）

    public Vehicle() {}

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public String getPlateNumber() {
        return plateNumber;
    }

    public void setPlateNumber(String plateNumber) {
        this.plateNumber = plateNumber;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(String vehicleType) {
        this.vehicleType = vehicleType;
    }

    public Double getBatteryCapacity() {
        return batteryCapacity;
    }

    public void setBatteryCapacity(Double batteryCapacity) {
        this.batteryCapacity = batteryCapacity;
    }

    public boolean isValidRequestAmount(Double amount) {
        if (amount == null || amount <= 0) {
            return false;
        }
        if (amount > batteryCapacity) {
            return false;
        }
        return true;
    }
}
