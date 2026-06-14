package com.charging.station.service;

import com.charging.station.domain.*;
import com.charging.station.enums.CarState;
import com.charging.station.enums.PileStatus;
import com.charging.station.enums.RequestMode;
import com.charging.station.mapper.*;
import com.charging.station.util.QueueNumberGenerator;
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

    @Autowired
    private SchedulerTrigger schedulerTrigger;

    /**
     * §27 故障在充车处理策略（运行期可切，验收用）：
     * TERMINAL=按已充量出账并置中断终态、剩余电量不重排（默认；§27"停止计费/一条详单" + §28"等候队列"/§29"尚未充电的车辆"字面口径）；
     * REQUEUE =按已充量出账后，剩余电量随该车最高优先重新调度续充。
     */
    public enum InterruptPolicy { TERMINAL, REQUEUE }

    private static volatile InterruptPolicy interruptPolicy = InterruptPolicy.TERMINAL;

    public static void setInterruptPolicy(InterruptPolicy p) {
        interruptPolicy = p;
    }

    public static InterruptPolicy getInterruptPolicy() {
        return interruptPolicy;
    }

    /**
     * §7 当前生效的故障调度策略（服务器端持久持有、可显式修改；验收"随机选择启用哪种"即设置此项）：
     * PRIORITY=优先级调度(§28) / TIME_ORDER=时间顺序调度(§29)。默认 PRIORITY（验收默认优先级）。
     */
    public enum FaultStrategy { PRIORITY, TIME_ORDER }

    private static volatile FaultStrategy activeFaultStrategy = FaultStrategy.PRIORITY;

    public static void setActiveFaultStrategy(FaultStrategy s) {
        activeFaultStrategy = s;
    }

    public static FaultStrategy getActiveFaultStrategy() {
        return activeFaultStrategy;
    }

    /**
     * 按【当前生效的故障调度策略】处理桩故障——故障事件不带策略参数时走此入口，
     * 由服务器端 activeFaultStrategy 决定用 §28 优先级还是 §29 时间顺序。
     */
    @Transactional
    public String handlePileFault(String pileId) {
        return activeFaultStrategy == FaultStrategy.TIME_ORDER
                ? handlePileFaultByTimeOrder(pileId)
                : handlePileFaultByPriority(pileId);
    }

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
            // §7a/§7b：该模式只要还有车滞留在故障桩排队队列里（尚未被重排走），
            // 就【暂停等候区叫号服务】——故障队列全部清空后才恢复对该模式叫号。
            if (hasStrandedFaultCars(mode)) {
                continue;
            }
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
            if (st == CarState.CHARGING) {
                // 正在充电的车按「剩余未充电量」估时；按全量估会高估等待时间，
                // 导致新车避开快充完的桩、选错"最短完成时长"的桩
                ChargingSession session = sessionMapper.getActiveSessionByPileId(queue.getPileId());
                double remaining = req.getRequestAmount();
                if (session != null) {
                    double charged = session.calculateCurrentAmount(ratedPower, com.charging.station.util.SimClock.nowVirtual());
                    remaining = Math.max(0.0, session.getRequestAmount() - charged);
                }
                totalWaitingTime += remaining / ratedPower;
            } else if (st == CarState.QUEUED_AT_PILE || st == CarState.CALLED) {
                totalWaitingTime += req.getRequestAmount() / ratedPower;
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
        // 0. 幂等保护：桩已处于故障态则直接返回，防止 HTTP 与定时注入并发触发两次重排
        ChargingPile faultPile = pileMapper.getPile(pileId);
        if (faultPile == null) {
            throw new IllegalArgumentException("充电桩不存在: " + pileId);
        }
        if (faultPile.getStatus() == PileStatus.FAULT) {
            return "充电桩已处于故障状态，无需重复调度";
        }

        // 1. 标记充电桩故障
        faultPile.markFault("优先级故障调度");
        pileMapper.updatePileState(faultPile);
        recordFault(pileId, "PRIORITY", "优先级故障调度");

        // 2. 中断正在充电的车辆：按已充量计费出一条详单（§27）。剩余电量去向由 interruptPolicy 决定：
        //    TERMINAL（默认）置中断终态、不重排；REQUEUE 时剩余量随该车最高优先续充。
        RequestMode mode = RequestMode.fromString(faultPile.getPileType());
        ChargingRequest interrupted = null;
        ChargingSession activeSession = sessionMapper.getActiveSessionByPileId(pileId);
        if (activeSession != null) {
            interrupted = interruptSessionAndGenerateBill(activeSession, faultPile,
                    interruptPolicy == InterruptPolicy.REQUEUE);
        }

        // 3. 组成故障重排组：故障桩排队区中"尚未开始充电"的车（从 DB 实查，原因见 queuedRequestsAtPile）；
        //    REQUEUE 时还含被中断的在充车（剩余量、号最小→最高优先）。TERMINAL 时 interrupted 恒为 null。
        List<ChargingRequest> faultGroup = new ArrayList<>();
        if (interrupted != null) {
            faultGroup.add(interrupted);
        }
        faultGroup.addAll(queuedRequestsAtPile(pileId));

        // 4. §7a 优先级：暂停等候区叫号期间，故障队列车按【排队号码】优先占用同类型桩空位，
        //    严格优先于等候区已有车辆；【安置不下的留在故障桩排队队列里继续等】（不回灌等候区），
        //    待其它同类型桩腾出空位时再由 drainFaultQueues 按号优先移入。
        int remaining = assignFaultGroupKeepLeftoversAtPile(faultGroup, mode, pileId);

        // 5. 记录调度并恢复等候区叫号（仍有滞留车时 dispatchWhenEmptySlot 会跳过该模式）
        recordDispatchRaw("FAULT_PRIORITY",
                "pile=" + pileId + ", group=" + faultGroup.size() + ", keptAtFaultPile=" + remaining,
                faultGroup.size());
        dispatchWhenEmptySlot();

        schedulerTrigger.afterCommitReconcile();
        return "故障调度完成";
    }

    /**
     * 时间顺序故障调度
     * 对应系统事件: handlePileFaultByTimeOrder(pileId)
     */
    @Transactional
    public String handlePileFaultByTimeOrder(String pileId) {
        // 0. 幂等保护：同优先级调度
        ChargingPile faultPile = pileMapper.getPile(pileId);
        if (faultPile == null) {
            throw new IllegalArgumentException("充电桩不存在: " + pileId);
        }
        if (faultPile.getStatus() == PileStatus.FAULT) {
            return "充电桩已处于故障状态，无需重复调度";
        }

        // 1. 标记充电桩故障
        faultPile.markFault("时间顺序故障调度");
        pileMapper.updatePileState(faultPile);
        recordFault(pileId, "TIME_ORDER", "时间顺序故障调度");

        // 2. 中断正在充电的车辆：按已充量计费出一条详单（§27）。剩余量去向由 interruptPolicy 决定。
        RequestMode mode = RequestMode.fromString(faultPile.getPileType());
        ChargingRequest interrupted = null;
        ChargingSession activeSession = sessionMapper.getActiveSessionByPileId(pileId);
        if (activeSession != null) {
            interrupted = interruptSessionAndGenerateBill(activeSession, faultPile,
                    interruptPolicy == InterruptPolicy.REQUEUE);
        }

        // 3. 故障桩排队区中"尚未开始充电"的车（REQUEUE 时含被中断的在充车）
        List<ChargingRequest> faultGroup = new ArrayList<>();
        if (interrupted != null) {
            faultGroup.add(interrupted);
        }
        faultGroup.addAll(queuedRequestsAtPile(pileId));

        // 4. §7b：把其它同类型桩中"尚未充电"的车一并并入（正在充电的车不动）
        List<ChargingPile> sameModePiles = pileMapper.getAllChargingPiles().stream()
                .filter(p -> !p.getPileId().equals(pileId) && p.getPileType().equals(faultPile.getPileType()))
                .collect(Collectors.toList());

        for (ChargingPile pile : sameModePiles) {
            faultGroup.addAll(queuedRequestsAtPile(pile.getPileId()));
            resetPileQueueLength(pile.getPileId());
        }

        // 5. §7b：合并组按【排队号码】先后顺序优先重排（暂停等候区叫号）；
        //    【安置不下的留在故障桩排队队列里继续等】（不回灌等候区），其它同类型桩腾位后由 drainFaultQueues 续排。
        int remaining = assignFaultGroupKeepLeftoversAtPile(faultGroup, mode, pileId);

        // 6. 记录调度并恢复等候区叫号（仍有滞留车时 dispatchWhenEmptySlot 会跳过该模式）
        recordDispatchRaw("FAULT_TIME_ORDER",
                "pile=" + pileId + ", group=" + faultGroup.size() + ", keptAtFaultPile=" + remaining,
                faultGroup.size());
        dispatchWhenEmptySlot();

        schedulerTrigger.afterCommitReconcile();
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
        faultMapper.markRecovered(pileId, com.charging.station.util.SimClock.nowVirtual());

        // 2. 规范要求：故障恢复时若其他同类型桩队列中尚有排队车辆，需要统一重新调度。
        //    收集同类型所有桩排队区中尚未开始充电的车辆（正在充电的车不动），
        //    按提交时间排序迁回等候区，再由 dispatchWhenEmptySlot 含恢复桩在内重新分配。
        RequestMode mode = RequestMode.fromString(pile.getPileType());
        List<ChargingRequest> recoverGroup = new ArrayList<>();
        for (ChargingPile p : pileMapper.getAllChargingPiles()) {
            if (!p.getPileType().equals(pile.getPileType())) {
                continue;
            }
            List<ChargingRequest> queued = queuedRequestsAtPile(p.getPileId());
            if (!queued.isEmpty()) {
                recoverGroup.addAll(queued);
                resetPileQueueLength(p.getPileId());
            }
        }
        // §7c：同类型桩中"尚未充电"的车按【排队号码】先后顺序优先重排（含新恢复的桩，暂停等候区叫号）；
        //      安置不下的回等候区，再恢复叫号。
        List<ChargingRequest> leftovers = assignGroupPrioritized(recoverGroup, mode);
        requeueToWaiting(leftovers, mode);

        // 3. 记录调度并恢复等候区叫号
        recordDispatchRaw("RECOVER", "pile=" + pileId + ", requeued=" + recoverGroup.size(),
                recoverGroup.size());
        dispatchWhenEmptySlot();

        schedulerTrigger.afterCommitReconcile();
        return "充电桩恢复并重新调度完成";
    }

    // ============ 管理端手动指派（YL-024 指定充电桩） ============

    /**
     * 管理员把等候区车辆手动指派到指定充电桩。
     * 校验：车辆在等候区(WAITING)、模式与桩类型匹配、桩可调度、桩排队队列有空位。
     */
    @Transactional
    public String assignCarToPile(String carId, String pileId) {
        ChargingRequest request = requestMapper.getActiveRequestByCarId(carId.trim().toUpperCase());
        if (request == null) {
            throw new IllegalArgumentException("未找到该车辆的活跃充电请求: " + carId);
        }
        if (request.getRequestStatus() != CarState.WAITING) {
            throw new IllegalStateException("车辆不在等候区（当前状态 " + request.getRequestStatus() + "），无法指派");
        }
        ChargingPile pile = pileMapper.getPile(pileId);
        if (pile == null) {
            throw new IllegalArgumentException("充电桩不存在: " + pileId);
        }
        if (!pile.getPileType().equals(request.getRequestMode().toString())) {
            throw new IllegalStateException("充电模式不匹配：车辆为 " + request.getRequestMode()
                    + "，目标桩为 " + pile.getPileType());
        }
        if (!pile.canAcceptNewVehicle()) {
            throw new IllegalStateException("充电桩当前不可用（" + pile.getStatus() + "）");
        }
        ChargingQueue queue = queueMapper.getChargingQueueByPileId(pileId);
        if (queue == null || !queue.hasRoom()) {
            throw new IllegalStateException("该桩排队队列已满");
        }

        queue.enqueue(request);
        queueMapper.updateChargingQueue(queue);
        request.assignToPile(pileId, queue.getCurrentLength());
        requestMapper.updateRequestStatus(request);
        requestMapper.updateVehicleState(request.getCarId(), CarState.QUEUED_AT_PILE.name());

        WaitingQueue waitingQueue = queueMapper.getWaitingQueue(request.getRequestMode());
        if (waitingQueue != null) {
            waitingQueue.setQueueLength(Math.max(0, waitingQueue.getQueueLength() - 1));
            queueMapper.updateWaitingQueue(waitingQueue);
        }
        recordDispatchRaw("MANUAL_ASSIGN", "car=" + request.getCarId() + " -> pile=" + pileId, 1);
        schedulerTrigger.afterCommitReconcile();
        return request.getCarId() + " 已指派到 " + pile.getPileNo() + "（队位 " + request.getQueuePosition() + "）";
    }

    // ============ 管理端启停充电桩 ============

    /**
     * 关闭充电桩（管理员正常停用，非故障）。
     * 与故障调度同样的安全语义：正在充电的车辆按已充部分出账单、剩余电量重新入队，
     * 桩前排队车辆迁回等候区重新调度；随后充电桩置为 OFFLINE 不再参与调度。
     */
    @Transactional
    public ChargingPile closePile(String pileId) {
        ChargingPile pile = pileMapper.getPile(pileId);
        if (pile == null) {
            throw new IllegalArgumentException("充电桩不存在: " + pileId);
        }
        if (pile.getStatus() == PileStatus.OFFLINE || pile.getStatus() == PileStatus.CLOSED) {
            return pile; // 已关闭，幂等
        }

        // 中断正在充电的车辆（已充部分出账单，剩余电量随请求重新入队）
        ChargingRequest interrupted = null;
        ChargingSession activeSession = sessionMapper.getActiveSessionByPileId(pileId);
        if (activeSession != null) {
            interrupted = interruptSessionAndGenerateBill(activeSession, pile, true);
        }

        // 桩前排队车辆迁回等候区
        List<ChargingRequest> affectedRequests = new ArrayList<>();
        if (interrupted != null) {
            affectedRequests.add(interrupted);
        }
        affectedRequests.addAll(queuedRequestsAtPile(pileId));
        resetPileQueueLength(pileId);
        requeueToWaiting(affectedRequests, RequestMode.fromString(pile.getPileType()));

        // 关闭充电桩并触发重新调度
        pile.powerOff();
        pileMapper.updatePileState(pile);
        recordDispatchRaw("PILE_CLOSE", "pile=" + pileId + ", requeued=" + affectedRequests.size(),
                affectedRequests.size());
        dispatchWhenEmptySlot();
        schedulerTrigger.afterCommitReconcile();
        return pile;
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

    /**
     * §6 选桩口径：在"同模式、可调度、排队区有空位"的桩中，选完成时长（等待时间 + 自身充电时间）最短者；
     * 无可用桩返回 null。与 {@link #dispatchWhenEmptySlot()} 内联选桩同一算法，供故障/恢复的优先重排复用。
     */
    private ChargingPile selectBestRoomyPile(ChargingRequest car, String mode) {
        ChargingPile best = null;
        double minCompletion = Double.MAX_VALUE;
        for (ChargingPile pile : pileMapper.getAllChargingPiles()) {
            if (!pile.canAcceptNewVehicle() || !pile.getPileType().equals(mode)) {
                continue;
            }
            ChargingQueue queue = queueMapper.getChargingQueueByPileId(pile.getPileId());
            if (queue == null || !queue.hasRoom()) {
                continue;
            }
            double completion = calculateQueueWaitingTime(queue, pile.getRatedPower())
                    + car.getRequestAmount() / pile.getRatedPower();
            if (completion < minCompletion) {
                minCompletion = completion;
                best = pile;
            }
        }
        return best;
    }

    /**
     * §28/§29/§7c「优先级」内核——暂停等候区叫号期间，把故障/恢复重排组按【排队号码先后】
     * 逐车直接安置到同类型有空位的桩（不经等候区中转，从而严格优先于等候区已有车辆）。
     * <p>先把整组从原桩摘下（置 WAITING、清空桩归属），以免选桩估时把"还挂在原桩"的本组车重复计入；
     * 每安置一辆即落库，后续车的等待时间据此累加。返回安置不下的车（由调用方放回等候区，叫号恢复后再处理）。
     */
    private List<ChargingRequest> assignGroupPrioritized(List<ChargingRequest> group, RequestMode mode) {
        List<ChargingRequest> leftovers = new ArrayList<>();
        if (group == null || group.isEmpty()) {
            return leftovers;
        }
        // 摘下整组：状态置 WAITING、桩归属清空（此时尚未计入等候区计数，安置成功者不再入账，落单者由调用方入账）
        for (ChargingRequest car : group) {
            car.moveToWaiting();
            requestMapper.updateRequestStatus(car);
            requestMapper.updateVehicleState(car.getCarId(), CarState.WAITING.name());
        }
        group.sort(Comparator.comparingInt(DispatchService::queueSeq));
        for (ChargingRequest car : group) {
            ChargingPile best = selectBestRoomyPile(car, mode.toString());
            if (best == null) {
                leftovers.add(car);
                continue;
            }
            ChargingQueue queue = queueMapper.getChargingQueueByPileId(best.getPileId());
            queue.enqueue(car);
            queueMapper.updateChargingQueue(queue);
            car.assignToPile(best.getPileId(), queue.getCurrentLength());
            requestMapper.updateRequestStatus(car);
            requestMapper.updateVehicleState(car.getCarId(), CarState.QUEUED_AT_PILE.name());
        }
        return leftovers;
    }

    /**
     * §7 故障队列优先重排核心：把一组"尚未充电"的车按【排队号码】先后，逐车安置到同类型【有空位】的桩；
     * 安置不下的【留在故障桩的排队队列里继续等】（不回灌等候区）。返回仍滞留在故障桩的车辆数。
     * <p>与 {@link #assignGroupPrioritized} 的唯一区别：落单车不进等候区，而是挂回故障桩排队队列——
     * 这样既符合 §7a/§7b"暂停等候区叫号、待故障队列全部调度完毕再恢复"的口径，监控上也能看到这些车
     * 仍在故障桩队列里等待（不会凭空跑去等候区，避免与"溢出"语义混淆）。
     * <p>先把整组从原桩摘下（置 WAITING、清桩归属），以免选桩估时把"还挂在原桩"的本组车重复计入；
     * 故障桩本身不可调度，故留守车不会被再次选回。
     */
    private int assignFaultGroupKeepLeftoversAtPile(List<ChargingRequest> group, RequestMode mode, String faultPileId) {
        if (group == null || group.isEmpty()) {
            ChargingQueue fq = queueMapper.getChargingQueueByPileId(faultPileId);
            if (fq != null && fq.getCurrentLength() != 0) {
                fq.setCurrentLength(0);
                queueMapper.updateChargingQueue(fq);
            }
            return 0;
        }
        for (ChargingRequest car : group) {
            car.moveToWaiting();
            requestMapper.updateRequestStatus(car);
            requestMapper.updateVehicleState(car.getCarId(), CarState.WAITING.name());
        }
        group.sort(Comparator.comparingInt(DispatchService::queueSeq));
        int kept = 0;
        for (ChargingRequest car : group) {
            ChargingPile best = selectBestRoomyPile(car, mode.toString());
            if (best != null) {
                ChargingQueue queue = queueMapper.getChargingQueueByPileId(best.getPileId());
                queue.enqueue(car);
                queueMapper.updateChargingQueue(queue);
                car.assignToPile(best.getPileId(), queue.getCurrentLength());
                requestMapper.updateRequestStatus(car);
                requestMapper.updateVehicleState(car.getCarId(), CarState.QUEUED_AT_PILE.name());
            } else {
                // 无空位：留在故障桩排队队列继续等（保留原排队号，恢复/腾位后仍按号最优先重排）
                kept++;
                car.assignToPile(faultPileId, kept);
                requestMapper.updateRequestStatus(car);
                requestMapper.updateVehicleState(car.getCarId(), CarState.QUEUED_AT_PILE.name());
            }
        }
        ChargingQueue fq = queueMapper.getChargingQueueByPileId(faultPileId);
        if (fq != null) {
            fq.setCurrentLength(kept);
            queueMapper.updateChargingQueue(fq);
        }
        return kept;
    }

    /** 某模式下是否仍有车滞留在【故障桩】排队队列里（用于 §7a/§7b 暂停该模式等候区叫号）。 */
    private boolean hasStrandedFaultCars(RequestMode mode) {
        for (ChargingPile p : pileMapper.getAllChargingPiles()) {
            if (p.getStatus() == PileStatus.FAULT
                    && p.getPileType().equals(mode.toString())
                    && !queuedRequestsAtPile(p.getPileId()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * §7a/§7b 反应式续排：每当其它同类型桩腾出空位（一次对账内），把仍滞留在故障桩排队队列里的车
     * 按【排队号码】优先移入。被 {@link ChargingScheduler} 对账时优先于等候区叫号调用，
     * 故障队列全部清空后，{@link #dispatchWhenEmptySlot()} 才恢复对该模式叫号。
     */
    @Transactional
    public void drainFaultQueues() {
        for (ChargingPile p : pileMapper.getAllChargingPiles()) {
            if (p.getStatus() != PileStatus.FAULT) {
                continue;
            }
            List<ChargingRequest> stranded = queuedRequestsAtPile(p.getPileId());
            if (stranded.isEmpty()) {
                continue;
            }
            assignFaultGroupKeepLeftoversAtPile(stranded, RequestMode.fromString(p.getPileType()), p.getPileId());
        }
    }

    /** 解析排队号数值后缀（F1→1、T12→12），用于"按排队号码先后顺序"排序；号缺失/异常者排最后。 */
    private static int queueSeq(ChargingRequest r) {
        String qn = r != null ? r.getQueueNum() : null;
        if (qn == null) {
            return Integer.MAX_VALUE;
        }
        String digits = qn.replaceAll("\\D", "");
        return digits.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(digits);
    }

    // ============ 中断会话并生成账单 ============

    /** 剩余电量小于该阈值（度）视为已充满 */
    private static final double REMAINING_EPSILON = 0.01;

    /**
     * 中断会话并对已充部分生成账单（本次充电过程对应一条详单）。
     * <p>{@code requeueRemaining=false}（充电桩故障，对应第一次报告业务规则 6.3）：正在该桩充电的
     * 车辆按已完成电量计费并【标记为中断】(终态)，剩余电量不重新入队；仅"尚未开始充电"的排队车
     * 由调用方按公平原则重排。
     * <p>{@code requeueRemaining=true}（管理员手动关桩，属自有功能、规范未覆盖）：把剩余电量作为新的
     * 请求量持久化并返回，由调用方放回等候区继续充电。
     * 返回 null 的情况：故障(终态离开)；剩余 &lt; 0.01 度按充满处理；请求已不在 CHARGING(并发结束/取消)。
     */
    private ChargingRequest interruptSessionAndGenerateBill(ChargingSession session, ChargingPile pile,
                                                            boolean requeueRemaining) {
        // 计算当前充电量和费用（启用跨时段分段计费）
        TariffPolicy policy = queueMapper.getCurrentTariffPolicy();
        Double currentAmount = session.calculateCurrentAmount(pile.getRatedPower(), com.charging.station.util.SimClock.nowVirtual());
        Double currentDuration = currentAmount / pile.getRatedPower() * 60.0;
        Double chargeFee = policy.calculateChargeFee(currentAmount, session.getStartTime().toLocalTime(), pile.getRatedPower());
        Double serviceFee = policy.calculateServiceFee(currentAmount);

        // 中断会话
        session.interrupt(currentAmount, currentDuration, chargeFee, serviceFee);
        sessionMapper.updateChargingSession(session);

        // 生成账单（本次充电过程对应一条详单）
        Bill bill = Bill.createFromSession(session);
        bill.setBillId("BILL-INT-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        billMapper.insertBill(bill);

        DetailedList detail = DetailedList.createFromSession(session, bill.getBillId());
        detail.setDetailId("DTL-INT-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        billMapper.insertDetailedList(detail);

        ChargingRequest req = requestMapper.getChargingRequest(session.getCarId());
        if (req == null || req.getRequestStatus() != CarState.CHARGING) {
            return null;
        }

        double remaining = Math.max(0.0,
                (req.getRequestAmount() != null ? req.getRequestAmount() : 0.0) - currentAmount);
        remaining = Math.round(remaining * 100) / 100.0;

        if (!requeueRemaining) {
            // 故障(业务规则 6.3)：充电车按已充量计费并标记为中断(终态)，剩余电量不重排；恰好充满则记为完成
            if (remaining < REMAINING_EPSILON) {
                req.markFinished();
                requestMapper.updateVehicleState(session.getCarId(), CarState.FINISHED.name());
            } else {
                req.interruptByFault(req.getRequestAmount()); // 状态置 INTERRUPTED 终态，请求量不变
                requestMapper.updateVehicleState(session.getCarId(), CarState.INTERRUPTED.name());
            }
            requestMapper.updateRequestStatus(req);
            return null;
        }

        // 管理员手动关桩(自有功能)：剩余电量重新入队，由调用方 requeueToWaiting 放回等候区继续充电。
        if (remaining < REMAINING_EPSILON) {
            req.markFinished();
            requestMapper.updateRequestStatus(req);
            requestMapper.updateVehicleState(session.getCarId(), CarState.FINISHED.name());
            return null;
        }
        // requestTime 保持原值不变，等候区按提交时间排序时它天然排在后来车辆之前（公平性保证）。
        req.interruptByFault(remaining);
        requestMapper.updateRequestAmount(req);
        requestMapper.updateRequestStatus(req);
        requestMapper.updateVehicleState(session.getCarId(), CarState.INTERRUPTED.name());
        return req;
    }

    // ============ 故障 / 调度 审计记录 ============

    private void recordFault(String pileId, String strategy, String reason) {
        FaultRecord fr = new FaultRecord();
        fr.setFaultId("FLT-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        fr.setPileId(pileId);
        fr.setStrategy(strategy);
        fr.setFaultReason(reason);
        fr.setOccurredAt(com.charging.station.util.SimClock.nowVirtual());
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
     * 批量调度（dispatchFullStationBatchMinTotalDuration）：规范 2.8 b。
     * <p>规则：(1) <b>不区分快/慢模式，任意车可分配任意类型充电桩</b>；(2) 最小化一批车辆的总完成时长
     * （所有车累计等待 + 累计充电时间）；(3) 仅当“到站车辆数 == 全部车位数(充电区 + 等候区)”时才触发。
     * <p>{@code force=true} 跳过车位门槛（演示用，非验收口径）。
     */
    @Transactional
    public DispatchPlan dispatchFullStationBatchMinTotalDuration(boolean force) {
        int arrived = countArrivedCars();
        int capacity = totalStationCapacity();
        if (!force && arrived < capacity) {
            throw new IllegalStateException("批量调度需到站车辆达到全部车位数 " + capacity
                    + "（充电区+等候区），当前仅 " + arrived + " 辆");
        }

        // 所有等候区车辆（不区分模式），合为一组
        List<ChargingRequest> waiting = new ArrayList<>();
        for (RequestMode m : RequestMode.values()) {
            waiting.addAll(requestMapper.getWaitingRequests(m.toString()));
        }
        DispatchPlan plan = batchAssignAnyPileMinTotalDuration(waiting);
        recordDispatch("FULL_STATION_BATCH", plan);
        return plan;
    }

    /** 在站车辆数：等候区 + 桩前排队 + 充电中（用于批量调度的触发门槛判定） */
    public int countArrivedCars() {
        int n = 0;
        for (RequestMode m : RequestMode.values()) {
            n += requestMapper.getWaitingRequests(m.toString()).size();
        }
        for (ChargingPile p : pileMapper.getAllChargingPiles()) {
            for (ChargingRequest r : requestMapper.getRequestsByPileId(p.getPileId())) {
                CarState st = r.getRequestStatus();
                if (st == CarState.QUEUED_AT_PILE || st == CarState.CALLED || st == CarState.CHARGING) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 全部车位数 = 充电区(每桩 1 正充 + M 排队) + 等候区(共用单一区域，容量 N) */
    public int totalStationCapacity() {
        int cap = 0;
        for (ChargingPile p : pileMapper.getAllChargingPiles()) {
            ChargingQueue q = queueMapper.getChargingQueueByPileId(p.getPileId());
            cap += 1 + (q != null ? q.getQueueLenM() : 0);
        }
        // 等候区是快/慢「共用的单一物理区域」，容量取 N（两条逻辑队列的 max_capacity 同为 N）；
        // 不能把两条队列各 N 相加成 2N，否则与准入「按 WAITING 总数判满 N」的口径不一致、8b 批量门槛会偏大。
        WaitingQueue wq = queueMapper.getWaitingQueue(RequestMode.FAST);
        if (wq != null && wq.getMaxCapacity() != null) {
            cap += wq.getMaxCapacity();
        }
        return cap;
    }

    /**
     * 把一组车（不区分模式）按“最短总完成时长”分配到<b>任意类型</b>可用桩。
     * 桩功率因类型而异（快 30 / 慢 10），属异速并行机：按 SPT(电量升序) 取车，
     * 每辆分配到使其完成时刻(桩当前负载 + 自身充电时长)最小的桩——使一批车的总完成时长最短。
     * 车辆被分到与原模式不同类型的桩时，按桩类型改写其模式（8b 客户请求只指定电量，按桩计费）。
     */
    private DispatchPlan batchAssignAnyPileMinTotalDuration(List<ChargingRequest> waiting) {
        DispatchPlan plan = new DispatchPlan();
        plan.setMode("MIXED");
        if (waiting == null || waiting.isEmpty()) {
            return plan;
        }

        List<PileSlot> slots = new ArrayList<>();
        for (ChargingPile pile : pileMapper.getAllChargingPiles()) {
            if (!pile.canAcceptNewVehicle()) {
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

        waiting.sort(Comparator.comparingDouble(ChargingRequest::getRequestAmount)); // SPT
        double total = 0.0;
        for (ChargingRequest car : waiting) {
            PileSlot best = null;
            double bestCompletion = Double.MAX_VALUE;
            for (PileSlot s : slots) {
                if (s.room <= 0) {
                    continue;
                }
                double completion = s.load + car.getRequestAmount() / s.pile.getRatedPower();
                if (completion < bestCompletion) {
                    bestCompletion = completion;
                    best = s;
                }
            }
            if (best == null) {
                break; // 无空位可分配
            }
            double dur = car.getRequestAmount() / best.pile.getRatedPower();

            // 从原模式等候区计数减一
            RequestMode oldMode = car.getRequestMode();
            WaitingQueue oldWq = queueMapper.getWaitingQueue(oldMode);
            if (oldWq != null) {
                oldWq.setQueueLength(Math.max(0, oldWq.getQueueLength() - 1));
                queueMapper.updateWaitingQueue(oldWq);
            }
            // 车辆模式与目标桩类型不同：按桩类型改写模式（任意车任意桩）
            RequestMode pileMode = RequestMode.fromString(best.pile.getPileType());
            if (oldMode != pileMode) {
                car.modifyMode(pileMode, QueueNumberGenerator.generate(pileMode));
                requestMapper.updateRequestMode(car);
            }

            ChargingQueue q = queueMapper.getChargingQueueByPileId(best.pile.getPileId());
            q.enqueue(car);
            queueMapper.updateChargingQueue(q);
            car.assignToPile(best.pile.getPileId(), q.getCurrentLength());
            requestMapper.updateRequestStatus(car);
            requestMapper.updateVehicleState(car.getCarId(), CarState.QUEUED_AT_PILE.name());

            best.load = bestCompletion;
            best.room -= 1;
            total += bestCompletion;
            plan.addAssignment(car.getCarId(), car.getQueueNum(), best.pile.getPileId(),
                    q.getCurrentLength(), dur, bestCompletion);
        }
        plan.setTotalDuration(total);
        return plan;
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
