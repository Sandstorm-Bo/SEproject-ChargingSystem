package com.charging.station.service;

import com.charging.station.domain.*;
import com.charging.station.enums.CarState;
import com.charging.station.enums.RequestMode;
import com.charging.station.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
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

    @Autowired
    private FaultMapper faultMapper;

    @Autowired
    private DispatchRecordMapper dispatchRecordMapper;

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

        // 从等候区获取车辆并为每辆车选择最优充电桩
        for (RequestMode mode : RequestMode.values()) {
            // 直接从数据库查询WAITING状态的车辆
            List<ChargingRequest> waitingRequests = requestMapper.getWaitingRequests(mode.toString());

            for (ChargingRequest candidate : waitingRequests) {
                // 找到该模式的所有可用桩，计算最短完成时间
                ChargingPile bestPile = null;
                double minCompletionTime = Double.MAX_VALUE;

                for (ChargingPile pile : availablePiles) {
                    if (!pile.getPileType().equals(mode.toString())) {
                        continue; // 桩类型不匹配
                    }

                    ChargingQueue queue = queueMapper.getChargingQueueByPileId(pile.getPileId());
                    if (!queue.hasRoom()) {
                        continue; // 队列已满
                    }

                    // 计算完成时间 = 等待时间 + 自己的充电时间
                    double waitingTime = calculateQueueWaitingTime(queue, pile.getRatedPower());
                    double chargingTime = candidate.getRequestAmount() / pile.getRatedPower();
                    double completionTime = waitingTime + chargingTime;

                    if (completionTime < minCompletionTime) {
                        minCompletionTime = completionTime;
                        bestPile = pile;
                    }
                }

                if (bestPile == null) {
                    break; // 没有可用桩了
                }

                // 分配到最优桩
                ChargingQueue bestQueue = queueMapper.getChargingQueueByPileId(bestPile.getPileId());
                bestQueue.enqueue(candidate);
                queueMapper.updateChargingQueue(bestQueue);

                candidate.assignToPile(bestPile.getPileId(), bestQueue.getCurrentLength());
                requestMapper.updateRequestStatus(candidate);
                requestMapper.updateVehicleState(candidate.getCarId(), CarState.QUEUED_AT_PILE.name());

                // 更新等候区长度
                WaitingQueue waitingQueue = queueMapper.getWaitingQueue(mode);
                waitingQueue.setQueueLength(waitingQueue.getQueueLength() - 1);
                queueMapper.updateWaitingQueue(waitingQueue);
            }
        }
    }

    /**
     * 计算充电桩前队列的等待时间
     */
    private double calculateQueueWaitingTime(ChargingQueue queue, double ratedPower) {
        double totalWaitingTime = 0.0;

        // 获取队列中所有车辆的充电请求
        List<ChargingRequest> queuedRequests = requestMapper.getRequestsByPileId(queue.getPileId());

        for (ChargingRequest req : queuedRequests) {
            CarState st = req.getRequestStatus();
            // getRequestStatus() 返回的是 CarState 枚举，必须用枚举比较；
            // 原先用 "CHARGING".equals(枚举) 恒为 false，导致等待时间恒为 0、调度退化
            if (st == CarState.CHARGING || st == CarState.QUEUED_AT_PILE || st == CarState.CALLED) {
                double chargingTime = req.getRequestAmount() / ratedPower;
                totalWaitingTime += chargingTime;
            }
        }

        return totalWaitingTime;
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
        recordFault(pileId, "PRIORITY", "优先级故障调度");

        // 2. 处理正在充电的车辆
        ChargingSession activeSession = sessionMapper.getActiveSessionByPileId(pileId);
        ChargingRequest interruptedRequest = null;
        if (activeSession != null) {
            // 中断会话并生成账单
            interruptedRequest = interruptSessionAndGenerateBill(activeSession, faultPile);
        }

        // 3. 获取故障桩排队区中尚未开始充电的车辆。
        //    必须从 DB 实查：queueMapper.getChargingQueueByPileId 不会加载 requests 列表，
        //    faultQueue.drain() 恒返回空，导致排队车辆被遗弃（既往缺陷）。
        List<ChargingRequest> affectedRequests = new ArrayList<>(queuedRequestsAtPile(pileId));
        if (interruptedRequest != null) {
            affectedRequests.add(0, interruptedRequest);
        }
        resetPileQueueLength(pileId);

        // 4. 受影响车辆按原相对顺序优先迁回等候区。其 requestTime 早于新到车辆，
        //    dispatchWhenEmptySlot 按 requestTime 升序重新分配时自然优先于等候区其他车辆。
        requeueToWaiting(affectedRequests, RequestMode.fromString(faultPile.getPileType()));

        // 5. 记录调度并触发重新调度
        recordDispatchRaw("FAULT_PRIORITY", "pile=" + pileId + ", requeued=" + affectedRequests.size(), affectedRequests.size());
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
        recordFault(pileId, "TIME_ORDER", "时间顺序故障调度");

        // 2. 处理正在充电的车辆
        ChargingSession activeSession = sessionMapper.getActiveSessionByPileId(pileId);
        ChargingRequest interruptedRequest = null;
        if (activeSession != null) {
            interruptedRequest = interruptSessionAndGenerateBill(activeSession, faultPile);
        }

        // 3. 获取故障桩排队区中尚未开始充电的车辆（从 DB 实查，原因同优先级调度）
        RequestMode mode = RequestMode.fromString(faultPile.getPileType());
        List<ChargingRequest> affectedRequests = new ArrayList<>(queuedRequestsAtPile(pileId));
        if (interruptedRequest != null) {
            affectedRequests.add(interruptedRequest);
        }
        resetPileQueueLength(pileId);

        // 4. 收集同类型其他桩排队区中尚未开始充电的车辆（正在充电的车不动）
        List<ChargingPile> sameModePiles = pileMapper.getAllChargingPiles().stream()
                .filter(p -> !p.getPileId().equals(pileId) && p.getPileType().equals(faultPile.getPileType()))
                .collect(Collectors.toList());

        for (ChargingPile pile : sameModePiles) {
            affectedRequests.addAll(queuedRequestsAtPile(pile.getPileId()));
            resetPileQueueLength(pile.getPileId());
        }

        // 5. 同类型未充电车辆合并后按提交时间（排队号）顺序重排，迁回等候区
        affectedRequests.sort(Comparator.comparing(ChargingRequest::getRequestTime,
                Comparator.nullsLast(Comparator.naturalOrder())));
        requeueToWaiting(affectedRequests, mode);

        // 6. 记录调度并触发重新调度
        recordDispatchRaw("FAULT_TIME_ORDER", "pile=" + pileId + ", requeued=" + affectedRequests.size(), affectedRequests.size());
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
        faultMapper.markRecovered(pileId, java.time.LocalDateTime.now());
        recordDispatchRaw("RECOVER", "pile=" + pileId, 0);

        // 2. 触发重新调度
        dispatchWhenEmptySlot();

        return "充电桩恢复并重新调度完成";
    }

    // ============ 故障调度公共辅助 ============

    /** 取某桩排队区中尚未开始充电（QUEUED_AT_PILE / CALLED）的请求（从 DB 实查） */
    private List<ChargingRequest> queuedRequestsAtPile(String pileId) {
        return requestMapper.getRequestsByPileId(pileId).stream()
                .filter(r -> r.getRequestStatus() == CarState.QUEUED_AT_PILE
                        || r.getRequestStatus() == CarState.CALLED)
                .collect(Collectors.toList());
    }

    /** 清零某充电桩排队区计数（正在充电的车不计入该计数） */
    private void resetPileQueueLength(String pileId) {
        ChargingQueue q = queueMapper.getChargingQueueByPileId(pileId);
        if (q != null) {
            q.setCurrentLength(0);
            queueMapper.updateChargingQueue(q);
        }
    }

    /** 将受影响车辆迁回等候区（置 WAITING、同步车辆状态、等候区计数 +N），随后由 dispatchWhenEmptySlot 重新分配 */
    private void requeueToWaiting(List<ChargingRequest> affected, RequestMode mode) {
        if (affected == null || affected.isEmpty()) {
            return;
        }
        for (ChargingRequest request : affected) {
            request.moveToWaiting();
            requestMapper.updateRequestStatus(request);
            requestMapper.updateVehicleState(request.getCarId(), CarState.WAITING.name());
        }
        WaitingQueue waitingQueue = queueMapper.getWaitingQueue(mode);
        if (waitingQueue != null) {
            waitingQueue.setQueueLength(waitingQueue.getQueueLength() + affected.size());
            queueMapper.updateWaitingQueue(waitingQueue);
        }
    }

    // ============ 中断会话并生成账单 ============

    /**
     * 中断会话并生成账单（私有方法）
     */
    private ChargingRequest interruptSessionAndGenerateBill(ChargingSession session, ChargingPile pile) {
        // 计算当前充电量和费用（启用跨时段分段计费）
        TariffPolicy policy = queueMapper.getCurrentTariffPolicy();
        Double currentAmount = session.calculateCurrentAmount(pile.getRatedPower(), java.time.LocalDateTime.now());
        Double currentDuration = currentAmount / pile.getRatedPower() * 60.0;
        Double chargeFee = policy.calculateChargeFee(currentAmount, session.getStartTime().toLocalTime(), pile.getRatedPower());
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

        // 将被中断车辆的请求改为剩余电量；调用方随后把它重新放回等待区参与调度。
        ChargingRequest req = requestMapper.getChargingRequest(session.getCarId());
        if (req != null) {
            double requested = req.getRequestAmount() != null ? req.getRequestAmount() : 0.0;
            double remaining = Math.max(0.0, requested - currentAmount);
            if (remaining <= 1e-6) {
                req.markFinished();
                requestMapper.updateRequestStatus(req);
                requestMapper.updateVehicleState(session.getCarId(), CarState.FINISHED.name());
                return null;
            }
            req.interruptByFault(remaining);
            requestMapper.updateRequestAmount(req);
            requestMapper.updateRequestStatus(req);
            requestMapper.updateVehicleState(session.getCarId(), CarState.INTERRUPTED.name());
            return req;
        }
        return null;
    }

    // ============ 故障 / 调度 审计记录 ============

    private void recordFault(String pileId, String strategy, String reason) {
        FaultRecord fr = new FaultRecord();
        fr.setFaultId("FLT-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        fr.setPileId(pileId);
        fr.setStrategy(strategy);
        fr.setFaultReason(reason);
        fr.setOccurredAt(java.time.LocalDateTime.now());
        fr.setStatus("OPEN");
        faultMapper.insertFaultRecord(fr);
    }

    private void recordDispatchRaw(String type, String detail, int count) {
        if (detail != null && detail.length() > 990) {
            detail = detail.substring(0, 990);
        }
        dispatchRecordMapper.insertDispatchRecord(new DispatchRecord(
                "DSP-" + java.util.UUID.randomUUID().toString().substring(0, 8), type, detail, count));
    }

    private void recordDispatch(String type, DispatchPlan plan) {
        String detail = plan.getAssignments().stream()
                .map(a -> a.getCarId() + "->" + a.getPileId())
                .collect(Collectors.joining(", "));
        recordDispatchRaw(type, "总完成时长(h)=" + String.format("%.2f", plan.getTotalDuration()) + "; " + detail,
                plan.getAssignments().size());
    }

    // ============ 2.8 扩展调度：最小化一批车辆的总充电完成时长 ============

    /**
     * 单次调度（dispatchSingleBatchMinTotalDuration）：同模式下取前 emptySlots 辆候选车，
     * 按最短总完成时长分配到该模式可用桩，返回方案。
     */
    @Transactional
    public DispatchPlan dispatchSingleBatchMinTotalDuration(RequestMode mode, int emptySlots) {
        DispatchPlan plan = batchAssignMinTotalDuration(mode, emptySlots);
        recordDispatch("SINGLE_BATCH_MIN_TOTAL", plan);
        return plan;
    }

    /**
     * 批量调度（dispatchFullStationBatchMinTotalDuration）：对全站所有等候车辆，
     * 按模式分别在对应桩上生成最短总完成时长方案，返回整站方案。
     */
    @Transactional
    public DispatchPlan dispatchFullStationBatchMinTotalDuration() {
        DispatchPlan combined = new DispatchPlan();
        combined.setMode("MIXED");
        double total = 0.0;
        for (RequestMode mode : RequestMode.values()) {
            DispatchPlan p = batchAssignMinTotalDuration(mode, Integer.MAX_VALUE);
            combined.getAssignments().addAll(p.getAssignments());
            total += p.getTotalDuration();
        }
        combined.setTotalDuration(total);
        recordDispatch("FULL_STATION_BATCH", combined);
        return combined;
    }

    /**
     * 按"最短总完成时长"把等候区某模式的车批量分配到该模式可用桩。
     * 候选车按充电时长升序（SPT），每辆分配给当前累计负载最小且有空位的桩，
     * 由调度理论，SPT + 选最小负载机器可使一批作业的总完成时间最小。
     */
    private DispatchPlan batchAssignMinTotalDuration(RequestMode mode, int maxCars) {
        DispatchPlan plan = new DispatchPlan();
        plan.setMode(mode.toString());

        List<ChargingRequest> waiting = requestMapper.getWaitingRequests(mode.toString());
        if (waiting.isEmpty()) {
            return plan;
        }
        if (maxCars >= 0 && maxCars < waiting.size()) {
            waiting = new ArrayList<>(waiting.subList(0, maxCars));
        }

        // 收集该模式可用桩的当前负载与剩余空位
        List<PileSlot> slots = new ArrayList<>();
        for (ChargingPile pile : pileMapper.getAllChargingPiles()) {
            if (!pile.getPileType().equals(mode.toString()) || !pile.canAcceptNewVehicle()) {
                continue;
            }
            ChargingQueue q = queueMapper.getChargingQueueByPileId(pile.getPileId());
            int room = q.getQueueLenM() - q.getCurrentLength();
            if (room <= 0) {
                continue;
            }
            slots.add(new PileSlot(pile, room, calculateQueueWaitingTime(q, pile.getRatedPower())));
        }
        if (slots.isEmpty()) {
            return plan;
        }

        // SPT：按充电量升序（同模式功率相同，等价于按时长升序）
        waiting.sort(Comparator.comparingDouble(ChargingRequest::getRequestAmount));

        double total = 0.0;
        for (ChargingRequest car : waiting) {
            PileSlot best = null;
            for (PileSlot s : slots) {
                if (s.room > 0 && (best == null || s.load < best.load)) {
                    best = s;
                }
            }
            if (best == null) {
                break; // 无空位可分配
            }
            double dur = car.getRequestAmount() / best.pile.getRatedPower();
            double completion = best.load + dur;

            ChargingQueue q = queueMapper.getChargingQueueByPileId(best.pile.getPileId());
            q.enqueue(car);
            queueMapper.updateChargingQueue(q);
            car.assignToPile(best.pile.getPileId(), q.getCurrentLength());
            requestMapper.updateRequestStatus(car);
            requestMapper.updateVehicleState(car.getCarId(), CarState.QUEUED_AT_PILE.name());

            WaitingQueue wq = queueMapper.getWaitingQueue(mode);
            wq.setQueueLength(Math.max(0, wq.getQueueLength() - 1));
            queueMapper.updateWaitingQueue(wq);

            best.load = completion;
            best.room -= 1;
            total += completion;
            plan.addAssignment(car.getCarId(), car.getQueueNum(), best.pile.getPileId(),
                    q.getCurrentLength(), dur, completion);
        }
        plan.setTotalDuration(total);
        return plan;
    }

    /** 批量调度内部：桩的可用空位与累计负载 */
    private static class PileSlot {
        final ChargingPile pile;
        int room;
        double load;

        PileSlot(ChargingPile pile, int room, double load) {
            this.pile = pile;
            this.room = room;
            this.load = load;
        }
    }
}
