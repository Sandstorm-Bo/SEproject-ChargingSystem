package com.charging.station.service;

import com.charging.station.domain.*;
import com.charging.station.enums.RequestMode;
import com.charging.station.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 调度服务
 * 根据设计文档 2.6/2.7/2.8 节实现
 */
@Service
public class DispatchService {

    @Autowired
    private PileMapper pileMapper;

    @Autowired
    private QueueMapper queueMapper;

    @Autowired
    private RequestMapper requestMapper;

    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private BillMapper billMapper;

    /**
     * 空位出现时执行调度
     * 设计文档中所有交互图里触发调度的地方都调用此方法
     */
    @Transactional
    public void dispatchWhenEmptySlot() {
        // 获取所有可调度的充电桩
        List<ChargingPile> availablePiles = pileMapper.getAllChargingPiles().stream()
                .filter(ChargingPile::canAcceptNewVehicle)
                .collect(Collectors.toList());

        if (availablePiles.isEmpty()) {
            return;
        }

        // 对每个桩尝试从等候区调度车辆
        for (ChargingPile pile : availablePiles) {
            ChargingQueue queue = queueMapper.getChargingQueueByPileId(pile.getPileId());
            if (queue.hasRoom()) {
                // 从等候区获取匹配模式的车辆
                RequestMode mode = RequestMode.fromString(pile.getPileType());
                WaitingQueue waitingQueue = queueMapper.getWaitingQueue(mode);

                ChargingRequest candidate = waitingQueue.peek(mode);
                if (candidate != null) {
                    // 移出等候区
                    waitingQueue.poll(mode);
                    queueMapper.updateWaitingQueue(waitingQueue);

                    // 加入充电桩队列
                    queue.enqueue(candidate);
                    queueMapper.updateChargingQueue(queue);

                    // 更新请求状态
                    candidate.assignToPile(pile.getPileId(), queue.getCurrentLength());
                    requestMapper.updateRequestStatus(candidate);
                }
            }
        }
    }

    /**
     * 优先级故障调度
     * 对应系统事件: handlePileFaultByPriority(pileId)
     */
    @Transactional
    public String handlePileFaultByPriority(String pileId) {
        // 1. 标记充电桩故障
        ChargingPile faultPile = pileMapper.getPile(pileId);
        faultPile.markFault("优先级故障调度");
        pileMapper.updatePileState(faultPile);

        // 2. 处理正在充电的车辆
        ChargingSession activeSession = sessionMapper.getActiveSessionByPileId(pileId);
        if (activeSession != null) {
            // 中断会话并生成账单
            interruptSessionAndGenerateBill(activeSession, faultPile);
        }

        // 3. 获取故障队列的车辆
        ChargingQueue faultQueue = queueMapper.getChargingQueueByPileId(pileId);
        List<ChargingRequest> affectedRequests = faultQueue.drain();
        queueMapper.updateChargingQueue(faultQueue);

        // 4. 将受影响车辆重新加入等候区（优先）
        for (ChargingRequest request : affectedRequests) {
            request.moveToWaiting();
            requestMapper.updateRequestStatus(request);

            WaitingQueue waitingQueue = queueMapper.getWaitingQueue(request.getRequestMode());
            waitingQueue.enqueueFront(List.of(request));
            queueMapper.updateWaitingQueue(waitingQueue);
        }

        // 5. 触发重新调度
        dispatchWhenEmptySlot();

        return "故障调度完成";
    }

    /**
     * 时间顺序故障调度
     * 对应系统事件: handlePileFaultByTimeOrder(pileId)
     */
    @Transactional
    public String handlePileFaultByTimeOrder(String pileId) {
        // 1. 标记充电桩故障
        ChargingPile faultPile = pileMapper.getPile(pileId);
        faultPile.markFault("时间顺序故障调度");
        pileMapper.updatePileState(faultPile);

        // 2. 处理正在充电的车辆
        ChargingSession activeSession = sessionMapper.getActiveSessionByPileId(pileId);
        if (activeSession != null) {
            interruptSessionAndGenerateBill(activeSession, faultPile);
        }

        // 3. 获取故障队列车辆
        ChargingQueue faultQueue = queueMapper.getChargingQueueByPileId(pileId);
        List<ChargingRequest> affectedRequests = faultQueue.drain();
        queueMapper.updateChargingQueue(faultQueue);

        // 4. 收集同类型其他桩的未充电车辆
        RequestMode mode = RequestMode.fromString(faultPile.getPileType());
        List<ChargingPile> sameModePiles = pileMapper.getAllChargingPiles().stream()
                .filter(p -> !p.getPileId().equals(pileId) && p.getPileType().equals(faultPile.getPileType()))
                .collect(Collectors.toList());

        for (ChargingPile pile : sameModePiles) {
            ChargingQueue queue = queueMapper.getChargingQueueByPileId(pile.getPileId());
            List<ChargingRequest> queuedRequests = queue.drain();
            affectedRequests.addAll(queuedRequests);
            queueMapper.updateChargingQueue(queue);
        }

        // 5. 按时间顺序排序并重新入队
        affectedRequests.sort((a, b) -> a.getRequestTime().compareTo(b.getRequestTime()));

        WaitingQueue waitingQueue = queueMapper.getWaitingQueue(mode);
        for (ChargingRequest request : affectedRequests) {
            request.moveToWaiting();
            requestMapper.updateRequestStatus(request);
            waitingQueue.enqueue(request);
        }
        queueMapper.updateWaitingQueue(waitingQueue);

        // 6. 触发重新调度
        dispatchWhenEmptySlot();

        return "时间顺序调度完成";
    }

    /**
     * 故障恢复
     * 对应系统事件: recoverPileAndRedispatch(pileId)
     */
    @Transactional
    public String recoverPileAndRedispatch(String pileId) {
        // 1. 恢复充电桩
        ChargingPile pile = pileMapper.getPile(pileId);
        pile.recover();
        pileMapper.updatePileState(pile);

        // 2. 触发重新调度
        dispatchWhenEmptySlot();

        return "充电桩恢复并重新调度完成";
    }

    /**
     * 中断会话并生成账单（私有方法）
     */
    private void interruptSessionAndGenerateBill(ChargingSession session, ChargingPile pile) {
        // 计算当前充电量和费用
        TariffPolicy policy = queueMapper.getCurrentTariffPolicy();
        Double currentAmount = session.calculateCurrentAmount(pile.getRatedPower(), java.time.LocalDateTime.now());
        Double currentDuration = session.calculateCurrentDuration(java.time.LocalDateTime.now());
        Double chargeFee = policy.calculateChargeFee(currentAmount, session.getStartTime().toLocalTime());
        Double serviceFee = policy.calculateServiceFee(currentAmount);

        // 中断会话
        session.interrupt(currentAmount, currentDuration, chargeFee, serviceFee);
        sessionMapper.updateChargingSession(session);

        // 生成账单
        Bill bill = Bill.createFromSession(session);
        bill.setBillId("BILL-INT-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        billMapper.insertBill(bill);

        DetailedList detail = DetailedList.createFromSession(session, bill.getBillId());
        detail.setDetailId("DTL-INT-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        billMapper.insertDetailedList(detail);
    }
}
