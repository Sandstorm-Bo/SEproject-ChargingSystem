package com.charging.station.mapper;

import com.charging.station.domain.*;
import com.charging.station.enums.RequestMode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 队列 Mapper
 * 根据设计文档 3.1 节定义
 */
@Mapper
public interface QueueMapper {

    /**
     * 按模式查询等候队列
     */
    WaitingQueue getWaitingQueue(@Param("queueType") RequestMode queueType);

    /**
     * 更新等候队列
     */
    void updateWaitingQueue(WaitingQueue queue);

    /**
     * 按充电桩编号查询充电桩前队列
     */
    ChargingQueue getChargingQueueByPileId(@Param("pileId") String pileId);

    /**
     * 更新充电桩前队列
     */
    void updateChargingQueue(ChargingQueue queue);

    /**
     * 查询所有充电桩队列（含请求）
     */
    List<ChargingQueue> getAllChargingQueuesWithRequests();

    /**
     * 查询当前计费策略
     */
    TariffPolicy getCurrentTariffPolicy();

    /**
     * 保存计费策略
     */
    void saveTariffPolicy(TariffPolicy policy);
}
