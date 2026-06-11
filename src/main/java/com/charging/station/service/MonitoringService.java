package com.charging.station.service;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.ChargingQueue;
import com.charging.station.mapper.MaintenanceMapper;
import com.charging.station.mapper.PileMapper;
import com.charging.station.mapper.QueueMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private MaintenanceMapper maintenanceMapper;

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

    /**
     * 一键重置充电业务数据（等价 reset_db.sh）：
     * 清空详单/账单/会话/请求，复位等候区与桩前队列长度，并将所有充电桩复位为空闲可调度。
     * 保留充电桩与车辆配置，便于两次演示之间快速清场。
     */
    @Transactional
    public void resetAll() {
        maintenanceMapper.deleteAllDetails();
        maintenanceMapper.deleteAllBills();
        maintenanceMapper.deleteAllSessions();
        maintenanceMapper.deleteAllRequests();
        maintenanceMapper.resetWaitingQueues();
        maintenanceMapper.resetChargingQueues();
        maintenanceMapper.resetPiles();
        maintenanceMapper.resetVehicles();
    }
}
