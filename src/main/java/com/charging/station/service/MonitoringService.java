package com.charging.station.service;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.ChargingQueue;
import com.charging.station.enums.PileStatus;
import com.charging.station.mapper.MaintenanceMapper;
import com.charging.station.mapper.PileMapper;
import com.charging.station.mapper.QueueMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        // 排队号从 F1/T1 重新编号；仿真时钟（若启用）重新锚定回配置的起点，场景从头开始
        com.charging.station.util.QueueNumberGenerator.reset();
        com.charging.station.util.SimClock.reanchor();
    }

    /** 充电桩功率口径：快充 30 度/h、慢充 10 度/h（与验收用例一致） */
    private static final double FAST_POWER = 30.0;
    private static final double TRICKLE_POWER = 10.0;

    /**
     * 验收参数重配（FastChargingPileNum / TrickleChargingPileNum / WaitingAreaSize / ChargingQueueLen）。
     * <p>重建充电桩拓扑属于全站结构变更：先清空全部充电业务数据，再按新数量重建快/慢充电桩及其桩前队列，
     * 设置等候区容量与每桩排队队列长度 M。调用方须持全站锁（与调度循环/故障注入互斥）。
     *
     * @param fastNum      快充桩数（>=0）
     * @param trickleNum   慢充桩数（>=0，且快+慢>=1）
     * @param waitingSize  等候区车位容量（每模式，>=1）
     * @param queueLen     每个充电桩排队队列长度 M（>=1）
     */
    @Transactional
    public void reconfigureStation(int fastNum, int trickleNum, int waitingSize, int queueLen) {
        if (fastNum < 0 || trickleNum < 0) {
            throw new IllegalArgumentException("充电桩数不能为负");
        }
        if (fastNum + trickleNum < 1) {
            throw new IllegalArgumentException("至少需要 1 个充电桩");
        }
        if (fastNum > 20 || trickleNum > 20) {
            throw new IllegalArgumentException("单类型充电桩数上限 20");
        }
        if (waitingSize < 1 || waitingSize > 100) {
            throw new IllegalArgumentException("等候区车位容量需在 1 ~ 100");
        }
        if (queueLen < 1 || queueLen > 20) {
            throw new IllegalArgumentException("充电桩排队队列长度 M 需在 1 ~ 20");
        }

        // 1. 清空充电业务数据（详单/账单/会话/请求），解除对充电桩的外键引用
        maintenanceMapper.deleteAllDetails();
        maintenanceMapper.deleteAllBills();
        maintenanceMapper.deleteAllSessions();
        maintenanceMapper.deleteAllRequests();
        // 2. 删除桩前队列与充电桩（桩前队列外键指向充电桩，先删队列）
        maintenanceMapper.deleteAllChargingQueues();
        maintenanceMapper.deleteAllPiles();
        // 3. 按新数量重建快/慢充电桩及桩前队列
        for (int i = 1; i <= fastNum; i++) {
            createPile("P_F" + i, "F" + i, "FAST", FAST_POWER, queueLen);
        }
        for (int i = 1; i <= trickleNum; i++) {
            createPile("P_T" + i, "T" + i, "TRICKLE", TRICKLE_POWER, queueLen);
        }
        // 4. 设置等候区容量并清零，复位车辆，重排号、重锚仿真时钟
        maintenanceMapper.setWaitingAreaCapacity(waitingSize);
        maintenanceMapper.resetVehicles();
        com.charging.station.util.QueueNumberGenerator.reset();
        com.charging.station.util.SimClock.reanchor();
    }

    private void createPile(String pileId, String pileNo, String type, double power, int queueLen) {
        ChargingPile p = new ChargingPile();
        p.setPileId(pileId);
        p.setPileNo(pileNo);
        p.setPileType(type);
        p.setStatus(PileStatus.IDLE);
        p.setRatedPower(power);
        p.setQueueCapacityM(queueLen);
        p.setIsSchedulable(true);
        pileMapper.insertPile(p);

        ChargingQueue q = new ChargingQueue();
        q.setQueueId("CQ_" + pileNo);
        q.setPileId(pileId);
        q.setQueueLenM(queueLen);
        q.setCurrentLength(0);
        queueMapper.insertChargingQueue(q);
    }

    /** 当前验收参数快照（供控制台预填与展示） */
    public Map<String, Object> getStationConfig() {
        List<ChargingPile> piles = pileMapper.getAllChargingPiles();
        int fastNum = (int) piles.stream().filter(p -> "FAST".equals(p.getPileType())).count();
        int trickleNum = (int) piles.stream().filter(p -> "TRICKLE".equals(p.getPileType())).count();
        int queueLen = piles.stream().filter(p -> p.getQueueCapacityM() != null)
                .mapToInt(ChargingPile::getQueueCapacityM).max().orElse(2);
        Integer waitingSize = null;
        com.charging.station.domain.WaitingQueue wq =
                queueMapper.getWaitingQueue(com.charging.station.enums.RequestMode.FAST);
        if (wq != null) {
            waitingSize = wq.getMaxCapacity();
        }
        Map<String, Object> m = new HashMap<>();
        m.put("fastNum", fastNum);
        m.put("trickleNum", trickleNum);
        m.put("waitingSize", waitingSize != null ? waitingSize : 10);
        m.put("queueLen", queueLen);
        return m;
    }
}
