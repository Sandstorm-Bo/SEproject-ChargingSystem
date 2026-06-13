package com.charging.station;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.ChargingRequest;
import com.charging.station.domain.ChargingSession;
import com.charging.station.dto.ChargingRequestDTO;
import com.charging.station.enums.CarState;
import com.charging.station.enums.PileStatus;
import com.charging.station.enums.RequestMode;
import com.charging.station.mapper.BillMapper;
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
 * 故障调度回归测试——对齐第一次报告业务规则 6.3：
 * 「正在该桩充电的车辆按已完成电量计费并标记为中断（终态）；尚未开始充电但受影响的请求按公平原则重新调度。」
 * 即：充电车 = 出账 + INTERRUPTED 终态，不重排剩余电量；只有排队（未充电）车迁回等候区重排。
 * 同时覆盖：故障幂等、充满瞬间故障、排队车重排公平性。
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "charging.scheduler.enabled=false")
public class FaultRequeueTest {

    private static final String CAR_A = "京A10001";
    private static final String CAR_B = "京A10002";
    private static final String FAST_PILE = "P_F1";
    private static final String FAST_PILE_2 = "P_F2";

    @Autowired private ChargingApplicationService chargingApplicationService;
    @Autowired private DispatchService dispatchService;
    @Autowired private MonitoringService monitoringService;
    @Autowired private RequestMapper requestMapper;
    @Autowired private QueueMapper queueMapper;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private PileMapper pileMapper;
    @Autowired private BillMapper billMapper;

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

    private void submitFast(String carId, double amount) {
        ChargingRequestDTO dto = new ChargingRequestDTO();
        dto.setCarId(carId);
        dto.setRequestAmount(amount);
        dto.setRequestMode("FAST");
        chargingApplicationService.submitChargingRequest(dto);
    }

    @Test
    @DisplayName("业务规则6.3：故障中断的充电车按已充量出账并标记中断(终态)，不重排剩余电量")
    public void faultedChargingCarBilledAndMarkedInterrupted() {
        monitoringService.resetAll();
        dispatchService.handlePileFaultByPriority(FAST_PILE_2); // F2 故障 → A 确定地进 F1
        driveToCharging(CAR_A, 50.0, FAST_PILE);

        String result = dispatchService.handlePileFaultByPriority(FAST_PILE);
        assertTrue(result.contains("完成"));

        assertEquals(PileStatus.FAULT, pileMapper.getPile(FAST_PILE).getStatus());
        assertNull(sessionMapper.getActiveSessionByCarId(CAR_A), "中断后不应再有活动会话");

        // 核心：充电车标记为中断(终态)，不回等候区、不重排
        ChargingRequest req = requestMapper.getChargingRequest(CAR_A);
        assertEquals(CarState.INTERRUPTED, req.getRequestStatus(), "充电车应标记中断终态，而非回到等候区");
        assertEquals(0, queueMapper.getWaitingQueue(RequestMode.FAST).getQueueLength(),
                "充电车不应回到等候区（剩余电量不重排）");
        // 已按已充量出账：生成一条详单
        assertFalse(billMapper.getDetailsByCarId(CAR_A).isEmpty(), "应为已充部分生成一条详单");

        // 恢复另一桩后，处于终态的充电车不应被重新调度
        dispatchService.recoverPileAndRedispatch(FAST_PILE_2);
        assertEquals(CarState.INTERRUPTED, requestMapper.getChargingRequest(CAR_A).getRequestStatus(),
                "终态中断车不应被恢复流程重新调度");
    }

    @Test
    @DisplayName("对同一充电桩重复触发故障应幂等，不重复出账")
    public void faultIsIdempotent() {
        monitoringService.resetAll();
        dispatchService.handlePileFaultByPriority(FAST_PILE_2);
        driveToCharging(CAR_A, 50.0, FAST_PILE);

        dispatchService.handlePileFaultByPriority(FAST_PILE);
        CarState st1 = requestMapper.getChargingRequest(CAR_A).getRequestStatus();
        int details1 = billMapper.getDetailsByCarId(CAR_A).size();

        // 第二次故障调用（HTTP 与定时注入并发场景）：直接返回，不重复处理
        String second = dispatchService.handlePileFaultByPriority(FAST_PILE);
        assertTrue(second.contains("已处于故障状态"));

        assertEquals(st1, requestMapper.getChargingRequest(CAR_A).getRequestStatus(), "状态不应因重复故障改变");
        assertEquals(details1, billMapper.getDetailsByCarId(CAR_A).size(), "不应因重复故障重复出账");
    }

    @Test
    @DisplayName("故障瞬间恰好充满的车辆按正常完成处理（FINISHED）")
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
            assertEquals(CarState.FINISHED, req.getRequestStatus(), "已充满应记为完成");
            assertEquals(0, queueMapper.getWaitingQueue(RequestMode.FAST).getQueueLength());
            assertNull(sessionMapper.getActiveSessionByCarId(CAR_A));
        } finally {
            ChargingSession.TIME_ACCELERATION = saved;
        }
    }

    @Test
    @DisplayName("时间顺序故障：充电车标记中断(终态)，同桩排队(未充电)车迁回等候区并在恢复后重排")
    public void chargingCarTerminatedWhileQueuedCarRequeued() {
        monitoringService.resetAll();
        dispatchService.handlePileFaultByPriority(FAST_PILE_2); // 仅 F1 可用
        driveToCharging(CAR_A, 50.0, FAST_PILE);                // A 在 F1 充电

        submitFast(CAR_B, 30.0);
        dispatchService.dispatchWhenEmptySlot();                // B 进入 F1 排队区（A 在充、队列有空位）
        ChargingRequest reqB = requestMapper.getChargingRequest(CAR_B);
        assertEquals(CarState.QUEUED_AT_PILE, reqB.getRequestStatus());
        assertEquals(FAST_PILE, reqB.getPileId());

        dispatchService.handlePileFaultByTimeOrder(FAST_PILE);
        // 充电车 A：终态中断、不重排
        assertEquals(CarState.INTERRUPTED, requestMapper.getChargingRequest(CAR_A).getRequestStatus());
        // 排队车 B：未开始充电，迁回等候区
        assertEquals(CarState.WAITING, requestMapper.getChargingRequest(CAR_B).getRequestStatus());

        // 恢复 F2：B 被重新调度入桩；A 仍为终态
        dispatchService.recoverPileAndRedispatch(FAST_PILE_2);
        assertEquals(CarState.QUEUED_AT_PILE, requestMapper.getChargingRequest(CAR_B).getRequestStatus());
        assertEquals(FAST_PILE_2, requestMapper.getChargingRequest(CAR_B).getPileId());
        assertEquals(CarState.INTERRUPTED, requestMapper.getChargingRequest(CAR_A).getRequestStatus());
    }
}
