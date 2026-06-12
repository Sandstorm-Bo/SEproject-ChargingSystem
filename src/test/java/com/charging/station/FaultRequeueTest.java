package com.charging.station;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.ChargingRequest;
import com.charging.station.domain.ChargingSession;
import com.charging.station.domain.WaitingQueue;
import com.charging.station.dto.ChargingRequestDTO;
import com.charging.station.enums.CarState;
import com.charging.station.enums.PileStatus;
import com.charging.station.enums.RequestMode;
import com.charging.station.mapper.PileMapper;
import com.charging.station.mapper.QueueMapper;
import com.charging.station.mapper.RequestMapper;
import com.charging.station.mapper.SessionMapper;
import com.charging.station.service.ChargingApplicationService;
import com.charging.station.service.DispatchService;
import com.charging.station.service.MonitoringService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 故障重新入队回归测试：
 * 充电桩故障时，正在充电的车辆必须带着「剩余未充电量」回到等候区并参与后续调度，
 * 而不是停留在 INTERRUPTED 终态被调度算法遗忘（既往缺陷）。
 * 同时覆盖：故障幂等、充满瞬间故障、时间顺序重排的公平性。
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "charging.scheduler.enabled=false")
public class FaultRequeueTest {

    private static final String CAR_A = "京A10001";
    private static final String CAR_B = "京A10002";
    private static final String FAST_PILE = "P_F1";
    private static final String FAST_PILE_2 = "P_F2";

    @Autowired
    private ChargingApplicationService chargingApplicationService;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private MonitoringService monitoringService;

    @Autowired
    private RequestMapper requestMapper;

    @Autowired
    private QueueMapper queueMapper;

    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private PileMapper pileMapper;

    /** 提交快充申请并通过正常调度流程把该车推进到 CHARGING 状态（在指定桩上） */
    private void driveToCharging(String carId, double amount, String pileId) {
        ChargingRequestDTO dto = new ChargingRequestDTO();
        dto.setCarId(carId);
        dto.setRequestAmount(amount);
        dto.setRequestMode("FAST");
        chargingApplicationService.submitChargingRequest(dto);

        dispatchService.dispatchWhenEmptySlot();
        ChargingRequest req = requestMapper.getChargingRequest(carId);
        assertEquals(CarState.QUEUED_AT_PILE, req.getRequestStatus(), "调度后应进入桩排队区");
        assertEquals(pileId, req.getPileId(), "应被分配到预期充电桩");

        chargingApplicationService.callVehicle(carId);
        chargingApplicationService.startCharging(carId, pileId);
        assertEquals(CarState.CHARGING, requestMapper.getChargingRequest(carId).getRequestStatus());
    }

    @Test
    @DisplayName("故障中断的充电车辆应带剩余电量回到等候区，并在桩恢复后被重新调度")
    public void faultDuringChargingRequeuesRemainingAmount() {
        monitoringService.resetAll();
        // 先令 P_F2 故障，保证车辆确定地被分配到 P_F1，且故障后无桩可去、必须停留在等候区
        dispatchService.handlePileFaultByPriority(FAST_PILE_2);

        driveToCharging(CAR_A, 50.0, FAST_PILE);

        String result = dispatchService.handlePileFaultByPriority(FAST_PILE);
        assertTrue(result.contains("完成"));

        // 桩故障
        ChargingPile pile = pileMapper.getPile(FAST_PILE);
        assertEquals(PileStatus.FAULT, pile.getStatus());

        // 会话已中断（不再有活动会话），账单由中断流程生成
        assertNull(sessionMapper.getActiveSessionByCarId(CAR_A), "中断后不应再有活动会话");

        // 核心断言：请求回到等候区，剩余电量 > 0 且不超过原请求量，桩位已清空
        ChargingRequest req = requestMapper.getChargingRequest(CAR_A);
        assertEquals(CarState.WAITING, req.getRequestStatus(), "被中断车辆应回到等候区而非滞留 INTERRUPTED");
        assertNull(req.getPileId(), "重新入队后不应再关联故障桩");
        assertTrue(req.getRequestAmount() > 0 && req.getRequestAmount() <= 50.0,
                "请求电量应更新为剩余未充电量，实际: " + req.getRequestAmount());

        // 等候区计数同步 +1，管理端/客户端基于该状态即可看到此车
        WaitingQueue wq = queueMapper.getWaitingQueue(RequestMode.FAST);
        assertEquals(1, wq.getQueueLength(), "等候区长度应包含被中断车辆");

        // 桩恢复后自动重新调度：车辆离开等候区，被分配到恢复的桩
        dispatchService.recoverPileAndRedispatch(FAST_PILE_2);
        req = requestMapper.getChargingRequest(CAR_A);
        assertEquals(CarState.QUEUED_AT_PILE, req.getRequestStatus(), "恢复后应被重新调度");
        assertEquals(FAST_PILE_2, req.getPileId());
        assertEquals(0, queueMapper.getWaitingQueue(RequestMode.FAST).getQueueLength());
    }

    @Test
    @DisplayName("对同一充电桩重复触发故障应幂等，不会把车辆重复入队")
    public void faultIsIdempotent() {
        monitoringService.resetAll();
        dispatchService.handlePileFaultByPriority(FAST_PILE_2);
        driveToCharging(CAR_A, 50.0, FAST_PILE);

        dispatchService.handlePileFaultByPriority(FAST_PILE);
        ChargingRequest first = requestMapper.getChargingRequest(CAR_A);
        int queueLen = queueMapper.getWaitingQueue(RequestMode.FAST).getQueueLength();

        // 第二次故障调用（HTTP 与定时注入并发场景）：直接返回，不重复重排
        String second = dispatchService.handlePileFaultByPriority(FAST_PILE);
        assertTrue(second.contains("已处于故障状态"));

        ChargingRequest after = requestMapper.getChargingRequest(CAR_A);
        assertEquals(first.getRequestStatus(), after.getRequestStatus());
        assertEquals(first.getRequestAmount(), after.getRequestAmount(), 1e-9, "剩余电量不应被二次扣减");
        assertEquals(queueLen, queueMapper.getWaitingQueue(RequestMode.FAST).getQueueLength(),
                "等候区长度不应因重复故障调用而漂移");
    }

    @Test
    @DisplayName("故障瞬间恰好充满的车辆按正常完成处理，不再入队")
    public void fullyChargedAtFaultIsFinished() throws InterruptedException {
        double saved = ChargingSession.TIME_ACCELERATION;
        try {
            monitoringService.resetAll();
            dispatchService.handlePileFaultByPriority(FAST_PILE_2);
            // 600 倍速下 30kW 充 1 度只需 0.2 真实秒，睡 500ms 保证已充满
            ChargingSession.TIME_ACCELERATION = 600.0;
            driveToCharging(CAR_A, 1.0, FAST_PILE);
            Thread.sleep(500);

            dispatchService.handlePileFaultByPriority(FAST_PILE);

            ChargingRequest req = requestMapper.getChargingRequest(CAR_A);
            assertEquals(CarState.FINISHED, req.getRequestStatus(), "已充满的车辆应标记完成而非重新排队");
            assertEquals(0, queueMapper.getWaitingQueue(RequestMode.FAST).getQueueLength());
            assertNull(sessionMapper.getActiveSessionByCarId(CAR_A));
        } finally {
            ChargingSession.TIME_ACCELERATION = saved;
        }
    }

    @Test
    @DisplayName("时间顺序故障重排保持公平：先提交的被中断车辆排在后提交的等候车辆之前")
    public void timeOrderRequeueKeepsFairness() throws InterruptedException {
        monitoringService.resetAll();
        dispatchService.handlePileFaultByPriority(FAST_PILE_2);
        driveToCharging(CAR_A, 50.0, FAST_PILE);

        // request_time 为秒级精度，间隔 >1s 保证先后顺序可比较
        Thread.sleep(1100);
        ChargingRequestDTO dto = new ChargingRequestDTO();
        dto.setCarId(CAR_B);
        dto.setRequestAmount(30.0);
        dto.setRequestMode("FAST");
        chargingApplicationService.submitChargingRequest(dto);

        dispatchService.handlePileFaultByTimeOrder(FAST_PILE);
        assertEquals(CarState.WAITING, requestMapper.getChargingRequest(CAR_A).getRequestStatus());
        assertEquals(CarState.WAITING, requestMapper.getChargingRequest(CAR_B).getRequestStatus());
        assertEquals(2, queueMapper.getWaitingQueue(RequestMode.FAST).getQueueLength());

        // 恢复后两车都进入 P_F2（容量 2）：先提交的被中断车辆 A 必须排在 B 之前
        dispatchService.recoverPileAndRedispatch(FAST_PILE_2);
        ChargingRequest reqA = requestMapper.getChargingRequest(CAR_A);
        ChargingRequest reqB = requestMapper.getChargingRequest(CAR_B);
        assertEquals(CarState.QUEUED_AT_PILE, reqA.getRequestStatus());
        assertEquals(CarState.QUEUED_AT_PILE, reqB.getRequestStatus());
        assertEquals(FAST_PILE_2, reqA.getPileId());
        assertTrue(reqA.getQueuePosition() < reqB.getQueuePosition(),
                "被中断车辆（提交更早）应排在后提交车辆之前: A=" + reqA.getQueuePosition() + ", B=" + reqB.getQueuePosition());
    }
}
