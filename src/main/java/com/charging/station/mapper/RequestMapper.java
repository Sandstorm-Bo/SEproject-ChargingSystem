package com.charging.station.mapper;

import com.charging.station.domain.ChargingRequest;
import com.charging.station.enums.CarState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
