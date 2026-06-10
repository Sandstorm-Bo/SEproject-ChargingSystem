package com.charging.station.service;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.ChargingQueue;
import com.charging.station.mapper.PileMapper;
import com.charging.station.mapper.QueueMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 监控服务
 * 根据设计文档 2.4/2.5 节实现
 */
@Service
public class MonitoringService {

    @Autowired
    private PileMapper pileMapper;

    @Autowired
    private QueueMapper queueMapper;

    /**
     * 查看充电桩状态
     * 对应系统事件: Query_PileState(pileId)
     */
    public ChargingPile queryPileState(String pileId) {
        ChargingPile pile = pileMapper.getPile(pileId);
        if (pile == null) {
            throw new IllegalArgumentException("充电桩不存在");
        }
        return pile;
    }

    /**
     * 查看所有充电桩状态
     */
    public List<ChargingPile> queryAllPileStates() {
        return pileMapper.getAllChargingPiles();
    }

    /**
     * 查看队列状态
     * 对应系统事件: Query_QueueState(queuelist)
     */
    public List<ChargingQueue> queryQueueState() {
        return queueMapper.getAllChargingQueuesWithRequests();
    }
}
