package com.charging.station.mapper;

import com.charging.station.domain.ChargingSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 充电会话 Mapper
 * 根据设计文档 3.1 节定义
 */
@Mapper
public interface SessionMapper {

    /**
     * 根据车辆编号查询活跃的充电会话
     */
    ChargingSession getActiveSessionByCarId(@Param("carId") String carId);

    /**
     * 根据充电桩编号查询活跃的充电会话
     */
    ChargingSession getActiveSessionByPileId(@Param("pileId") String pileId);

    /**
     * 插入新的充电会话
     */
    void insertChargingSession(ChargingSession session);

    /**
     * 更新充电会话
     */
    void updateChargingSession(ChargingSession session);
}
