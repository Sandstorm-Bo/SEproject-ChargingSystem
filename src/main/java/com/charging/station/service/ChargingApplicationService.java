package com.charging.station.service;

import com.charging.station.domain.*;
import com.charging.station.dto.ChargingRequestDTO;
import com.charging.station.enums.CarState;
import com.charging.station.enums.RequestMode;
import com.charging.station.mapper.*;
import com.charging.station.util.QueueNumberGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    /**
     * 提交充电申请
     * 对应系统事件: E_chargingRequest(carId, amount, mode)
     */
    @Transactional
    public ChargingRequest submitChargingRequest(ChargingRequestDTO dto) {
        // 1. 前置条件检查
        String carId = dto.getCarId().trim().toUpperCase();
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
        request.setRequestTime(LocalDateTime.now());
        request.setRequestStatus(CarState.WAITING);

        // 3. 持久化充电请求
        requestMapper.insertChargingRequest(request);

        // 4. 加入等候队列
        waitingQueue.enqueue(request);
        queueMapper.updateWaitingQueue(waitingQueue);

        // 5. 触发调度
        dispatchService.dispatchWhenEmptySlot();

        return request;
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

        // 2. 调用领域对象方法修改
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

        // 2. 从原队列移除
        WaitingQueue oldQueue = queueMapper.getWaitingQueue(request.getRequestMode());
        oldQueue.removeRequest(carId);
        queueMapper.updateWaitingQueue(oldQueue);

        // 3. 修改模式并生成新排队号
        String newQueueNum = QueueNumberGenerator.generate(newMode);
        request.modifyMode(newMode, newQueueNum);

        // 4. 加入新队列
        WaitingQueue newQueue = queueMapper.getWaitingQueue(newMode);
        newQueue.enqueue(request);
        queueMapper.updateWaitingQueue(newQueue);

        // 5. 持久化
        requestMapper.updateRequestMode(request);

        // 6. 触发调度
        dispatchService.dispatchWhenEmptySlot();

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
        return request;
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
        if (!queue.isHead(carId)) {
            throw new IllegalStateException("车辆必须位于充电桩队首");
        }

        // 2. 创建充电会话
        ChargingSession session = new ChargingSession();
        session.setSessionId("SES-" + UUID.randomUUID().toString().substring(0, 8));
        session.setCarId(carId);
        session.setPileId(pileId);
        session.setRequestAmount(request.getRequestAmount());
        session.setStartTime(LocalDateTime.now());
        session.setSessionStatus("进行中");

        // 3. 更新请求状态
        request.startCharging(pileId);
        requestMapper.updateRequestStatus(request);

        // 4. 更新充电桩状态
        pile.startCharging();
        pileMapper.updatePileState(pile);

        // 5. 从队列移除
        queue.releaseHead();
        queueMapper.updateChargingQueue(queue);

        // 6. 持久化会话
        sessionMapper.insertChargingSession(session);

        // 7. 触发调度（队列空出位置）
        dispatchService.dispatchWhenEmptySlot();

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

        // 计算实时充电量和费用
        ChargingPile pile = pileMapper.getPile(session.getPileId());
        Double currentAmount = session.calculateCurrentAmount(pile.getRatedPower(), LocalDateTime.now());
        Double currentDuration = session.calculateCurrentDuration(LocalDateTime.now());

        // 这里简化处理，实际应该结合 TariffPolicy 计算
        session.setChargeAmount(currentAmount);
        session.setChargeDuration(currentDuration);

        return session;
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

        // 3. 计算最终充电量和费用
        Double finalAmount = session.calculateCurrentAmount(pile.getRatedPower(), LocalDateTime.now());
        Double finalDuration = session.calculateCurrentDuration(LocalDateTime.now());
        Double chargeFee = policy.calculateChargeFee(finalAmount, session.getStartTime().toLocalTime());
        Double serviceFee = policy.calculateServiceFee(finalAmount);

        // 4. 结束会话
        session.finish(finalAmount, finalDuration, chargeFee, serviceFee);
        sessionMapper.updateChargingSession(session);

        // 5. 更新充电请求状态
        ChargingRequest request = requestMapper.getChargingRequest(carId);
        request.markFinished();
        requestMapper.updateRequestStatus(request);

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

        return bill;
    }
}
