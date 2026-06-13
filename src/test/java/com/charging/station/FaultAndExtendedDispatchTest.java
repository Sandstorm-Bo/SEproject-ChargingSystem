package com.charging.station;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.ChargingRequest;
import com.charging.station.domain.DispatchPlan;
import com.charging.station.dto.ChargingRequestDTO;
import com.charging.station.enums.CarState;
import com.charging.station.enums.RequestMode;
import com.charging.station.mapper.QueueMapper;
import com.charging.station.mapper.RequestMapper;
import com.charging.station.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 故障两种调度策略的区别、故障恢复重调度，以及 2.8 扩展调度（单次/批量 最短总完成时长）。
 *
 * 关键区别（需求 2.7）：
 *  - 优先级调度：只重排"故障桩"队列的车，其它同类型桩的排队车不动；
 *  - 时间顺序调度：把"其它同类型桩中尚未充电的车" + 故障桩队列车 合为一组，按排队号(提交时间)重排。
 * 用同一布局对比两种策略对"其它桩车辆"的影响，以证明区别确实实现。
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "charging.scheduler.enabled=false")
@DisplayName("故障两策略区别 + 故障恢复 + 2.8 扩展调度")
public class FaultAndExtendedDispatchTest {

    @Autowired private ChargingApplicationService app;
    @Autowired private DispatchService dispatch;
    @Autowired private MonitoringService monitoring;
    @Autowired private RequestMapper requestMapper;
    @Autowired private QueueMapper queueMapper;

    @BeforeEach
    public void setUp() { monitoring.resetAll(); }

    private void submit(String car, double amount, String mode) {
        ChargingRequestDTO dto = new ChargingRequestDTO();
        dto.setCarId(car); dto.setRequestAmount(amount); dto.setRequestMode(mode);
        dto.setBatteryCapacity(200.0);
        app.submitChargingRequest(dto);
    }

    private CarState state(String car) { return requestMapper.getChargingRequest(car).getRequestStatus(); }
    private String pile(String car) { return requestMapper.getChargingRequest(car).getPileId(); }

    /**
     * 布局：T1=[c1,c2]（满），T2=[c3]（1 空位），T3 故障不可用。c1&lt;c2&lt;c3 提交时间递增。
     * 用手动指派精确摆放（assignCarToPile 不触发全局重调度，避免 reshuffle 打乱布局）。
     */
    private void buildTwoPileLayout() throws InterruptedException {
        dispatch.handlePileFaultByPriority("P_T3"); // T3 全程不可用
        submit("CX1", 20, "TRICKLE"); Thread.sleep(1100);
        submit("CX2", 20, "TRICKLE"); Thread.sleep(1100);
        submit("CX3", 20, "TRICKLE");
        dispatch.assignCarToPile("CX1", "P_T1");
        dispatch.assignCarToPile("CX2", "P_T1");    // T1 满 [c1,c2]
        dispatch.assignCarToPile("CX3", "P_T2");    // T2 [c3]，尚余 1 空位
        assertEquals("P_T1", pile("CX1"));
        assertEquals("P_T1", pile("CX2"));
        assertEquals("P_T2", pile("CX3"));
    }

    @Test
    @DisplayName("优先级故障：只重排故障桩的车，其它桩(T2)的车不受影响")
    void priorityFaultLeavesOtherPilesUntouched() throws InterruptedException {
        buildTwoPileLayout();
        dispatch.handlePileFaultByPriority("P_T1"); // 仅 c1,c2 被重排；c3 应留在 T2 不动
        assertEquals("P_T2", pile("CX3"), "优先级策略不应触碰其它桩的车");
        assertEquals(CarState.QUEUED_AT_PILE, state("CX3"));
        // c1,c2 被迁出故障桩：只有 T2 一个空位 → 较早的 c1 进 T2，c2 回等候区
        assertEquals("P_T2", pile("CX1"));
        assertEquals(CarState.WAITING, state("CX2"), "无空位的 c2 应留等候区");
    }

    @Test
    @DisplayName("时间顺序故障：其它桩(T2)的未充电车也被并入重排")
    void timeOrderFaultPullsOtherPileCars() throws InterruptedException {
        buildTwoPileLayout();
        dispatch.handlePileFaultByTimeOrder("P_T1"); // c1,c2(T1) + c3(T2) 合并按时间序重排
        // 唯一可用桩 T2(M=2)：按提交序 c1,c2 占满，c3 被挤回等候区——证明 c3 确实被并入重排
        assertEquals(CarState.QUEUED_AT_PILE, state("CX1"));
        assertEquals(CarState.QUEUED_AT_PILE, state("CX2"));
        assertEquals("P_T2", pile("CX1"));
        assertEquals("P_T2", pile("CX2"));
        assertEquals(CarState.WAITING, state("CX3"), "被并入重排的 c3 因时间最晚被挤回等候区");
    }

    @Test
    @DisplayName("故障恢复：恢复桩后，原积压在其它同类型桩/等候区的车被重新调度到恢复桩")
    void recoveryRedispatchesToRecoveredPile() throws InterruptedException {
        dispatch.handlePileFaultByPriority("P_T1"); // T1 故障
        dispatch.handlePileFaultByPriority("P_T3"); // T3 不可用 → 慢充仅 T2
        submit("CY1", 20, "TRICKLE"); Thread.sleep(1100);
        submit("CY2", 20, "TRICKLE");
        dispatch.dispatchWhenEmptySlot();           // c1,c2 → T2（满）
        submit("CY3", 20, "TRICKLE");
        dispatch.dispatchWhenEmptySlot();           // c3 无位 → 等候区
        assertEquals(CarState.WAITING, state("CY3"), "恢复前 c3 应滞留等候区");

        dispatch.recoverPileAndRedispatch("P_T1");  // 恢复 T1 → 触发同类型重调度
        assertEquals(CarState.QUEUED_AT_PILE, state("CY3"), "恢复后积压车应被重新调度入桩");
        long onT1 = java.util.stream.Stream.of("CY1", "CY2", "CY3")
                .filter(c -> "P_T1".equals(pile(c))).count();
        assertTrue(onT1 >= 1, "恢复的 T1 应被利用（至少 1 辆车进入 T1）");
    }

    // ============ 2.8 扩展调度（选做）============

    @Test
    @DisplayName("2.8 单次调度：同模式按 SPT 分配到两快充桩，最小化总完成时长")
    void singleBatchAssignsBySptMinTotal() {
        // 4 辆快充：80/20/60/40 度；2 快充桩各 M=2 = 4 位
        submit("SB1", 80, "FAST");
        submit("SB2", 20, "FAST");
        submit("SB3", 60, "FAST");
        submit("SB4", 40, "FAST");
        DispatchPlan plan = dispatch.dispatchSingleBatchMinTotalDuration(RequestMode.FAST, 4);

        assertEquals(4, plan.getAssignments().size(), "4 辆应全部被分配");
        // SPT(20,40,60,80) 贪心分到当前最小负载桩：
        // 20→F1(.667) 40→F2(1.333) 60→F1(2.667) 80→F2(4.0)；总完成时长=.667+1.333+2.667+4.0=8.667h
        assertEquals(8.667, plan.getTotalDuration(), 0.02, "总完成时长应为 SPT 最优解");
        for (String c : new String[]{"SB1", "SB2", "SB3", "SB4"}) {
            assertEquals(CarState.QUEUED_AT_PILE, state(c), c + " 应已入桩");
        }
    }

    @Test
    @DisplayName("2.8b 全站批量调度：未达全部车位数时拒绝触发（充电区+等候区车位门槛）")
    void fullStationBatchGateBlocksUntilFull() {
        submit("FB1", 60, "FAST");
        submit("FB2", 30, "FAST");
        submit("FB3", 20, "TRICKLE");
        submit("FB4", 10, "TRICKLE");
        // 车位数 = 5 桩×(1正充+2排队) + 等候区(10+10) = 15 + 20 = 35；当前到站 4 辆
        assertEquals(4, dispatch.countArrivedCars(), "到站车辆数");
        assertEquals(35, dispatch.totalStationCapacity(), "全部车位数=充电区+等候区");
        assertThrows(IllegalStateException.class,
                () -> dispatch.dispatchFullStationBatchMinTotalDuration(false),
                "未达全部车位数应拒绝批量调度");
    }

    @Test
    @DisplayName("2.8b 全站批量调度：不区分模式、任意车任意桩、总完成时长最短（force 跳过门槛）")
    void fullStationBatchAnyPileMinTotal() {
        submit("FB1", 60, "FAST");
        submit("FB2", 30, "FAST");
        submit("FB3", 20, "TRICKLE");
        submit("FB4", 10, "TRICKLE");
        DispatchPlan plan = dispatch.dispatchFullStationBatchMinTotalDuration(true);

        assertEquals(4, plan.getAssignments().size(), "全部 4 辆应被分配");
        for (String c : new String[]{"FB1", "FB2", "FB3", "FB4"}) {
            assertEquals(CarState.QUEUED_AT_PILE, state(c), c + " 应已入桩");
        }
        // 任意车任意桩：原慢充请求(FB3/FB4)被分到更快的快充桩以最小化总时长
        assertTrue(pile("FB3").startsWith("P_F"), "8b 不区分模式：慢充请求可进快充桩");
        assertTrue(pile("FB4").startsWith("P_F"), "8b 不区分模式：慢充请求可进快充桩");
        // SPT(10,20,30,60) 任意桩贪心全部入快充桩：0.333+0.667+1.333+2.667 = 5.0h
        assertEquals(5.0, plan.getTotalDuration(), 0.02, "总完成时长应为任意桩 SPT 贪心解");
        assertEquals(0, queueMapper.getWaitingQueue(RequestMode.FAST).getQueueLength());
        assertEquals(0, queueMapper.getWaitingQueue(RequestMode.TRICKLE).getQueueLength());
    }
}
