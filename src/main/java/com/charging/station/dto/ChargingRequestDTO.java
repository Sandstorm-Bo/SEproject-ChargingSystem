package com.charging.station.dto;

import com.charging.station.enums.RequestMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 充电申请请求 DTO
 */
public class ChargingRequestDTO {

    @NotBlank(message = "车辆编号不能为空")
    private String carId;

    @NotNull(message = "充电量不能为空")
    @Positive(message = "充电量必须大于0")
    private Double requestAmount;

    @NotNull(message = "充电模式不能为空")
    private String requestMode;

    @Positive(message = "电池容量必须大于0")
    private Double batteryCapacity;

    /** 提交申请的登录用户编号（可选，登录后由客户端携带） */
    private String userId;

    public ChargingRequestDTO() {}

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getCarId() {
        return carId;
    }

    public void setCarId(String carId) {
        this.carId = carId;
    }

    public Double getRequestAmount() {
        return requestAmount;
    }

    public void setRequestAmount(Double requestAmount) {
        this.requestAmount = requestAmount;
    }

    public String getRequestMode() {
        return requestMode;
    }

    public void setRequestMode(String requestMode) {
        this.requestMode = requestMode;
    }

    public Double getBatteryCapacity() {
        return batteryCapacity;
    }

    public void setBatteryCapacity(Double batteryCapacity) {
        this.batteryCapacity = batteryCapacity;
    }

    public RequestMode getRequestModeEnum() {
        return RequestMode.fromString(requestMode);
    }
}
