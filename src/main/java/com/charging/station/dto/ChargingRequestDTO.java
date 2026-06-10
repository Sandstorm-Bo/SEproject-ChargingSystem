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
    private String requestMode;  // FAST 或 TRICKLE

    // Constructors
    public ChargingRequestDTO() {}

    // Getters and Setters
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

    public RequestMode getRequestModeEnum() {
        return RequestMode.fromString(requestMode);
    }
}
