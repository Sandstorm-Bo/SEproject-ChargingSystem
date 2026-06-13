package com.charging.station.service;

import com.charging.station.domain.*;
import com.charging.station.dto.ChargingRequestDTO;
import com.charging.station.enums.CarState;
import com.charging.station.enums.RequestMode;
import com.charging.station.mapper.*;
import com.charging.station.util.QueueNumberGenerator;
import com.charging.station.util.SimClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 充电申请服务
 * 根据设计文档 2.1 节实现
 */
@Service
public class ChargingApplicationService {

    @Autowired
    private RequestMapper requestMapper;

    @Autowired
    private QueueMapper queueMapper;

    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private PileMapper pileMapper;

    @Autowired
    private BillMapper billMapper;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private SchedulerTrigger schedulerTrigger;

    /**
     * 提交充电申请
     * 对应系统事件: E_chargingRequest(carId, amount, mode)
     */
    @Transactional
    public ChargingRequest submitChargingRequest(ChargingRequestDTO dto) {
        // 0. 确保车辆存在（动态创建）
        String carId = dto.getCarId().trim().toUpperCase();
        ensureVehicleExists(carId, dto.getBatteryCapacity());
        // 登录用户提交时把车辆挂到该用户名下（详单按用户查询的依据）
        if (dto.getUserId() != null && !dto.getUserId().trim().isEmpty()) {
            requestMapper.updateVehicleUser(carId, dto.getUserId().trim());
        }

        // 充电量不能超过电池容量
        Double cap = dto.getBatteryCapacity();
        if (cap != null && dto.getRequestAmount() != null && dto.getRequestAmount() > cap) {
            throw new IllegalStateException("充电量不能超过电池容量");
        }

        // 1. 前置条件检查
        ChargingRequest existingRequest = requestMapper.getActiveRequestByCarId(carId);
        if (existingRequest != null) {
            throw new IllegalStateException("该车辆已有活跃的充电请求");
        }

        WaitingQueue waitingQueue = queueMapper.getWaitingQueue(dto.getRequestModeEnum());
        if (waitingQueue == null || waitingQueue.isFull()) {
            throw new IllegalStateException("等候区已满");
        }

        // 2. 创建充电请求对象
        ChargingRequest request = new ChargingRequest();
        request.setRequestId("REQ-" + UUID.randomUUID().toString().substring(0, 8));
        request.setCarId(carId);
        request.setRequestAmount(dto.getRequestAmount());
        request.setRequestMode(dto.getRequestModeEnum());
        request.setQueueNum(QueueNumberGenerator.generate(dto.getRequestModeEnum()));
        request.setRequestTime(SimClock.nowVirtual());
        request.setRequestStatus(CarState.WAITING);

        // 3. 持久化充电请求
        requestMapper.insertChargingRequest(request);
        requestMapper.updateVehicleState(carId, CarState.WAITING.name());

        // 4. 加入等候队列
        waitingQueue.enqueue(request);
        queueMapper.updateWaitingQueue(waitingQueue);

        // 5. 不在此处同步调度：调度由 ChargingScheduler 定时统一触发，
        //    使车辆提交后先停留在等候区（WAITING），保留可修改充电量/模式的窗口
        // 事件驱动：提交后（事务提交时）触发一次对账——若有空闲匹配桩则立即叫号开充，否则停留等候区
        schedulerTrigger.afterCommitReconcile();
        return request;
    }

    /**
     * 确保车辆存在，不存在则创建
     */
    private void ensureVehicleExists(String carId, Double batteryCapacity) {
        try {
            // 尝试插入车辆，如果已存在则忽略错误
            requestMapper.insertVehicleIfNotExists(carId, carId, "电动汽车",
                batteryCapacity != null ? batteryCapacity : 60.0);
        } catch (Exception e) {
            // 车辆已存在，忽略
        }
    }

    /**
     * 修改充电量
     * 对应系统事件: Modify_Amount(carId, amount)
     */
    @Transactional
    public ChargingRequest modifyAmount(String carId, Double amount) {
        // 1. 前置条件检查
        ChargingRequest request = requestMapper.getChargingRequest(carId);
        if (request == null) {
            throw new IllegalArgumentException("未找到该车辆的充电请求");
        }

        // 充电量不能超过电池容量（操作契约前置条件）
        Double cap = requestMapper.getVehicleCapacity(carId);
        if (cap != null && amount != null && amount > cap) {
            throw new IllegalArgumentException("充电量不能超过电池容量");
        }

        // 2. 调用领域对象方法修改（校验 WAITING 状态与正数）
        request.modifyAmount(amount);

        // 3. 持久化
        requestMapper.updateRequestAmount(request);

        return request;
    }

    /**
     * 修改充电模式
     * 对应系统事件: Modify_Mode(carId, mode)
     */
    @Transactional
    public ChargingRequest modifyMode(String carId, String mode) {
        // 1. 前置条件检查
        ChargingRequest request = requestMapper.getChargingRequest(carId);
        if (request == null) {
            throw new IllegalArgumentException("未找到该车辆的充电请求");
        }

        RequestMode newMode = RequestMode.fromString(mode);

        // 2. 从原等候队列计数中移除
        //    注意：从 DB 加载的 WaitingQueue 其 requests 列表为空，removeRequest 不会递减计数，
        //    因此这里直接对队列长度计数做递减，避免等候区计数漂移。
        WaitingQueue oldQueue = queueMapper.getWaitingQueue(request.getRequestMode());
        oldQueue.setQueueLength(Math.max(0, oldQueue.getQueueLength() - 1));
        queueMapper.updateWaitingQueue(oldQueue);

        // 3. 修改模式并生成新排队号
        String newQueueNum = QueueNumberGenerator.generate(newMode);
        request.modifyMode(newMode, newQueueNum);

        // 4. 加入新等候队列（enqueue 递增其长度计数）
        WaitingQueue newQueue = queueMapper.getWaitingQueue(newMode);
        newQueue.enqueue(request);
        queueMapper.updateWaitingQueue(newQueue);

        // 5. 持久化
        requestMapper.updateRequestMode(request);

        // 6. 改模式后（事务提交时）触发对账：新模式下若有空闲桩可立即分配（如快充满改慢充而慢充有空位）
        schedulerTrigger.afterCommitReconcile();
        return request;
    }

    /**
     * 查看车辆排队状态
     * 对应系统事件: Query_Car_State(carId)
     */
    public ChargingRequest queryCarState(String carId) {
        ChargingRequest request = requestMapper.getChargingRequest(carId);
        if (request == null) {
            throw new IllegalArgumentException("未找到该车辆的充电请求");
        }
        // 计算前方等待车辆数（操作契约返回值 car_Number_before_position）
        request.setCarsBeforeCount(countCarsBefore(request));
        return request;
    }

    /** 计算请求前方的等待车辆数：等候区按同模式提交时间先后，桩排队区按队列位置 */
    private int countCarsBefore(ChargingRequest request) {
        CarState st = request.getRequestStatus();
        if (st == CarState.WAITING && request.getRequestMode() != null) {
            int ahead = 0;
            for (ChargingRequest r : requestMapper.getWaitingRequests(request.getRequestMode().toString())) {
                if (r.getCarId().equals(request.getCarId())) {
                    return ahead;
                }
                ahead++;
            }
            return ahead;
        }
        if (st == CarState.QUEUED_AT_PILE || st == CarState.CALLED) {
            if (request.getPileId() == null) {
                return 0;
            }
            int ahead = 0;
            for (ChargingRequest r : requestMapper.getRequestsByPileId(request.getPileId())) {
                if (r.getCarId().equals(request.getCarId())) {
                    return ahead;
                }
                if (r.getRequestStatus() == CarState.QUEUED_AT_PILE
                        || r.getRequestStatus() == CarState.CALLED
                        || r.getRequestStatus() == CarState.CHARGING) {
                    ahead++;
                }
            }
            return ahead;
        }
        return 0;
    }

    /**
     * 叫号：将到达充电桩队首的车辆（QUEUED_AT_PILE）置为 CALLED 中间态。
     * 由调度器在桩空闲、车辆到达队首时调用，下一调度周期再正式开始充电。
     */
    @Transactional
    public void callVehicle(String carId) {
        ChargingRequest request = requestMapper.getChargingRequest(carId);
        if (request == null || request.getRequestStatus() != CarState.QUEUED_AT_PILE) {
            return;
        }
        request.markCalled();
        requestMapper.updateRequestStatus(request);
        requestMapper.updateVehicleState(carId, CarState.CALLED.name());
    }

    /**
     * 开始充电
     * 对应系统事件: Start_Charging(carId, pileId)
     */
    @Transactional
    public ChargingSession startCharging(String carId, String pileId) {
        // 1. 前置条件检查
        ChargingRequest request = requestMapper.getChargingRequest(carId);
        if (request == null) {
            throw new IllegalArgumentException("未找到该车辆的充电请求");
        }

        ChargingPile pile = pileMapper.getPile(pileId);
        if (pile == null) {
            throw new IllegalArgumentException("充电桩不存在");
        }

        ChargingQueue queue = queueMapper.getChargingQueueByPileId(pileId);
        // 队首判定基于 DB 中该桩 QUEUED_AT_PILE/CALLED 的请求（getRequestsByPileId 已按 queue_position 升序），
        // 不能依赖从 DB 加载的 ChargingQueue.requests —— 该列表始终为空，会导致 isHead 恒为 false
        ChargingRequest head = null;
        for (ChargingRequest r : requestMapper.getRequestsByPileId(pileId)) {
            if (r.getRequestStatus() == CarState.QUEUED_AT_PILE || r.getRequestStatus() == CarState.CALLED) {
                head = r;
                break;
            }
        }
        if (head == null || !head.getCarId().equals(carId)) {
            throw new IllegalStateException("车辆必须位于充电桩队首");
        }
        // 防止重复占用：该桩已有进行中的会话则不再开始
        if (sessionMapper.getActiveSessionByPileId(pileId) != null) {
            throw new IllegalStateException("充电桩正在充电中");
        }

        // 2. 创建充电会话
        ChargingSession session = new ChargingSession();
        session.setSessionId("SES-" + UUID.randomUUID().toString().substring(0, 8));
        session.setCarId(carId);
        session.setPileId(pileId);
        session.setRequestAmount(request.getRequestAmount());
        session.setStartTime(SimClock.nowVirtual());
        session.setSessionStatus("进行中");

        // 3. 更新请求状态
        request.startCharging(pileId);
        requestMapper.updateRequestStatus(request);
        requestMapper.updateVehicleState(carId, CarState.CHARGING.name());

        // 4. 更新充电桩状态
        pile.startCharging();
        pileMapper.updatePileState(pile);

        // 5. 该车离开桩排队区（计数直接递减，腾出排队位；releaseHead 依赖空的内存队列不会递减）
        queue.setCurrentLength(Math.max(0, queue.getCurrentLength() - 1));
        queueMapper.updateChargingQueue(queue);

        // 6. 持久化会话
        sessionMapper.insertChargingSession(session);

        // 7. 触发调度（队列空出位置）
        dispatchService.dispatchWhenEmptySlot();

        // 事件驱动：开充后（事务提交时）对账——为本会话排定“充满”定时器、并填补腾出的排队位
        schedulerTrigger.afterCommitReconcile();
        return session;
    }

    /**
     * 查看充电状态
     * 对应系统事件: Query_Charging_State(carId)
     */
    public ChargingSession queryChargingState(String carId) {
        ChargingSession session = sessionMapper.getActiveSessionByCarId(carId);
        if (session == null) {
            throw new IllegalArgumentException("该车辆没有正在进行的充电会话");
        }

        // 计算实时充电量、时长与费用（结合 TariffPolicy，操作契约要求返回实时费用快照）
        ChargingPile pile = pileMapper.getPile(session.getPileId());
        Double currentAmount = session.calculateCurrentAmount(pile.getRatedPower(), SimClock.nowVirtual());
        Double currentDuration = session.calculateCurrentDuration(SimClock.nowVirtual());
        session.setChargeAmount(currentAmount);
        session.setChargeDuration(currentDuration);

        TariffPolicy policy = queueMapper.getCurrentTariffPolicy();
        if (policy != null) {
            // 电价时段按充电开始的虚拟时刻判定（startTime 已是仿真时间轴上的时刻）
            Double chargeFee = policy.calculateChargeFee(currentAmount, session.getStartTime().toLocalTime(), pile.getRatedPower());
            Double serviceFee = policy.calculateServiceFee(currentAmount);
            session.setChargeFee(chargeFee);
            session.setServiceFee(serviceFee);
            session.setTotalFee(chargeFee + serviceFee);
        }

        return session;
    }

    /**
     * 取消充电请求（支持WAITING和QUEUED_AT_PILE状态）
     */
    @Transactional
    public void cancelChargingRequest(String carId) {
        ChargingRequest request = requestMapper.getChargingRequest(carId);
        if (request == null) {
            throw new IllegalArgumentException("未找到充电请求");
        }

        CarState status = request.getRequestStatus();
        if (status == null) {
            throw new IllegalStateException("车辆状态为空");
        }

        if (status == CarState.WAITING) {
            WaitingQueue waitingQueue = queueMapper.getWaitingQueue(request.getRequestMode());
            if (waitingQueue != null) {
                waitingQueue.setQueueLength(Math.max(0, waitingQueue.getQueueLength() - 1));
                queueMapper.updateWaitingQueue(waitingQueue);
            }
            request.markCancelled();
            requestMapper.updateRequestStatus(request);
            requestMapper.updateVehicleState(carId, CarState.CANCELLED.name());
        } else if (status == CarState.QUEUED_AT_PILE || status == CarState.CALLED) {
            String pileId = request.getPileId();
            if (pileId != null) {
                ChargingQueue queue = queueMapper.getChargingQueueByPileId(pileId);
                if (queue != null) {
                    queue.setCurrentLength(Math.max(0, queue.getCurrentLength() - 1));
                    queueMapper.updateChargingQueue(queue);
                }
            }
            request.markCancelled();
            requestMapper.updateRequestStatus(request);
            requestMapper.updateVehicleState(carId, CarState.CANCELLED.name());
            dispatchService.dispatchWhenEmptySlot();
        } else {
            throw new IllegalStateException("当前状态" + status + "无法取消");
        }
        // 事件驱动：取消腾出等候/排队位后（事务提交时）对账，让后续车辆即时顶上
        schedulerTrigger.afterCommitReconcile();
    }

    /**
     * 结束充电
     * 对应系统事件: End_Charging(carId, pileId)
     */
    @Transactional
    public Bill endCharging(String carId, String pileId) {
        // 1. 获取充电会话
        ChargingSession session = sessionMapper.getActiveSessionByCarId(carId);
        if (session == null || !session.getPileId().equals(pileId)) {
            throw new IllegalArgumentException("车辆与充电桩上的活动会话不匹配");
        }

        // 2. 获取充电桩和计费策略
        ChargingPile pile = pileMapper.getPile(pileId);
        TariffPolicy policy = queueMapper.getCurrentTariffPolicy();

        // 3. 计算最终充电量和费用（启用跨时段分段计费）
        Double finalAmount = session.calculateCurrentAmount(pile.getRatedPower(), SimClock.nowVirtual());
        Double finalDuration = finalAmount / pile.getRatedPower() * 60.0; // 时长由充电量/功率推导（分钟）
        Double chargeFee = policy.calculateChargeFee(finalAmount, session.getStartTime().toLocalTime(), pile.getRatedPower());
        Double serviceFee = policy.calculateServiceFee(finalAmount);

        // 4. 结束会话
        session.finish(finalAmount, finalDuration, chargeFee, serviceFee);
        sessionMapper.updateChargingSession(session);

        // 5. 更新充电请求状态
        ChargingRequest request = requestMapper.getChargingRequest(carId);
        request.markFinished();
        requestMapper.updateRequestStatus(request);
        requestMapper.updateVehicleState(carId, CarState.FINISHED.name());

        // 6. 更新充电桩状态
        pile.finishCharging(finalAmount, finalDuration);
        pileMapper.updatePileState(pile);

        // 7. 生成账单和详单
        Bill bill = Bill.createFromSession(session);
        bill.setBillId("BILL-" + UUID.randomUUID().toString().substring(0, 8));
        billMapper.insertBill(bill);

        DetailedList detail = DetailedList.createFromSession(session, bill.getBillId());
        detail.setDetailId("DTL-" + UUID.randomUUID().toString().substring(0, 8));
        billMapper.insertDetailedList(detail);

        // 8. 触发调度
        dispatchService.dispatchWhenEmptySlot();

        // 事件驱动：结束后（事务提交时）对账——腾出的桩立即叫下一辆开充并排定其“充满”定时器
        schedulerTrigger.afterCommitReconcile();
        return bill;
    }
}
