package com.charging.station.mapper;

import com.charging.station.domain.ChargingRequest;
import com.charging.station.domain.Vehicle;
import com.charging.station.enums.CarState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 充电请求 Mapper
 * 根据设计文档 3.1 节定义
 */
@Mapper
public interface RequestMapper {

    /**
     * 根据车辆编号查询充电请求
     */
    ChargingRequest getChargingRequest(@Param("carId") String carId);

    /**
     * 根据车辆编号查询活跃的充电请求
     */
    ChargingRequest getActiveRequestByCarId(@Param("carId") String carId);

    /**
     * 插入新的充电请求
     */
    void insertChargingRequest(ChargingRequest request);

    /**
     * 更新充电量
     */
    void updateRequestAmount(ChargingRequest request);

    /**
     * 更新充电模式
     */
    void updateRequestMode(ChargingRequest request);

    /**
     * 更新请求状态
     */
    void updateRequestStatus(ChargingRequest request);

    /**
     * 查询所有车辆
     */
    List<Vehicle> selectAllVehicles();

    /**
     * 插入车辆（如果不存在）
     */
    void insertVehicleIfNotExists(@Param("vehicleId") String vehicleId,
                                   @Param("plateNumber") String plateNumber,
                                   @Param("vehicleType") String vehicleType,
                                   @Param("batteryCapacity") Double batteryCapacity);

    /**
     * 根据充电桩ID查询所有排队和充电中的请求
     */
    List<ChargingRequest> getRequestsByPileId(@Param("pileId") String pileId);

    /**
     * 查询等候区中的车辆（按请求时间排序）
     */
    List<ChargingRequest> getWaitingRequests(@Param("requestMode") String requestMode);

    /**
     * 查询车辆电池总容量（用于校验充电量上限）
     */
    Double getVehicleCapacity(@Param("carId") String carId);

    /**
     * 同步更新车辆当前状态 car_state
     */
    void updateVehicleState(@Param("carId") String carId, @Param("carState") String carState);
}
