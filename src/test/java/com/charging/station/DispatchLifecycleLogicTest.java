package com.charging.station;

import com.charging.station.domain.*;
import com.charging.station.dto.ChargingRequestDTO;
import com.charging.station.enums.CarState;
import com.charging.station.enums.RequestMode;
import com.charging.station.mapper.*;
import com.charging.station.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 调度与生命周期逻辑测试（集成，关闭秒级调度器以保证确定性，全程在事务内执行并回滚）。
 *
 * 覆盖需求中"无歧义"的核心规则：
 *  - 选桩：同模式、有空位中取"完成时长最短"，平局取桩序 F1<F2<T1<T2<T3
 *  - 容量：等候区 N（快/慢共用，WAITING 总数 10）满则拒绝；桩前队列 M=2 满则不再分配
 *  - 修改场景：等候区可改量/改模式（改模式重排到队尾），充电区一律拒绝
 *  - 取消：等候区/桩排队可取消；充电中经 cancel 接口被拒（须走结束充电）
 *  - 前车数量、结束充电出账与详单字段、报表聚合
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "charging.scheduler.enabled=false")
@DisplayName("调度与生命周期逻辑")
public class DispatchLifecycleLogicTest {

    @Autowired private ChargingApplicationService app;
    @Autowired private DispatchService dispatch;
    @Autowired private MonitoringService monitoring;
    @Autowired private BillingService billing;
    @Autowired private RequestMapper requestMapper;
    @Autowired private QueueMapper queueMapper;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private PileMapper pileMapper;
    @Autowired private MaintenanceMapper maintenanceMapper;

    @BeforeEach
    public void setUp() {
        // 清场：清数据、桩复位 IDLE 可调度、等候区/桩队列计数清零、排队号与仿真时钟复位
        monitoring.resetAll();
    }

    private ChargingRequest submit(String car, double amount, String mode) {
        ChargingRequestDTO dto = new ChargingRequestDTO();
        dto.setCarId(car);
        dto.setRequestAmount(amount);
        dto.setRequestMode(mode);
        dto.setBatteryCapacity(200.0); // 足够大，避免触发"超过电池容量"
        return app.submitChargingRequest(dto);
    }

    /** 推进到 CHARGING，返回所在桩 */
    private String driveToCharging(String car, double amount, String mode) {
        submit(car, amount, mode);
        dispatch.dispatchWhenEmptySlot();
        ChargingRequest req = requestMapper.getChargingRequest(car);
        assertEquals(CarState.QUEUED_AT_PILE, req.getRequestStatus(), "调度后应进入桩排队区");
        String pileId = req.getPileId();
        app.callVehicle(car);
        app.startCharging(car, pileId);
        assertEquals(CarState.CHARGING, requestMapper.getChargingRequest(car).getRequestStatus());
        return pileId;
    }

    private int waitingLen(RequestMode m) {
        return queueMapper.getWaitingQueue(m).getQueueLength();
    }

    private int pileQueueLen(String pileId) {
        return queueMapper.getChargingQueueByPileId(pileId).getCurrentLength();
    }

    // ============ 配置一致性 ============

    @Test
    @DisplayName("线上电价配置符合需求：峰1.0/平0.7/谷0.4/服务0.8")
    void liveTariffMatchesSpec() {
        TariffPolicy p = queueMapper.getCurrentTariffPolicy();
        assertNotNull(p, "应存在生效电价策略");
        assertEquals(1.0, p.getPeakPrice(), 1e-9, "峰时必须为 1.0（非 1.2）");
        assertEquals(0.7, p.getFlatPrice(), 1e-9);
        assertEquals(0.4, p.getValleyPrice(), 1e-9);
        assertEquals(0.8, p.getServiceFeePerKwh(), 1e-9);
    }

    @Test
    @DisplayName("桩配置符合需求：2 快充(30kW) + 3 慢充(10kW)")
    void pileConfigMatchesSpec() {
        List<ChargingPile> piles = pileMapper.getAllChargingPiles();
        long fast = piles.stream().filter(p -> "FAST".equals(p.getPileType())).count();
        long slow = piles.stream().filter(p -> "TRICKLE".equals(p.getPileType())).count();
        assertEquals(2, fast, "应有 2 个快充桩");
        assertEquals(3, slow, "应有 3 个慢充桩");
        piles.stream().filter(p -> "FAST".equals(p.getPileType()))
                .forEach(p -> assertEquals(30.0, p.getRatedPower(), 1e-9, "快充 30kW"));
        piles.stream().filter(p -> "TRICKLE".equals(p.getPileType()))
                .forEach(p -> assertEquals(10.0, p.getRatedPower(), 1e-9, "慢充 10kW"));
    }

    // ============ 排队号 ============

    @Test
    @DisplayName("提交按模式生成顺序排队号 F1/F2/T1")
    void queueNumberOnSubmit() {
        assertEquals("F1", submit("TST-Q-1", 30, "FAST").getQueueNum());
        assertEquals("F2", submit("TST-Q-2", 30, "FAST").getQueueNum());
        assertEquals("T1", submit("TST-Q-3", 30, "TRICKLE").getQueueNum());
    }

    // ============ 选桩调度 ============

    @Test
    @DisplayName("空桩平局取桩序最前：单车快充进 F1")
    void dispatchTieBreakPrefersF1() {
        submit("TST-D-1", 60, "FAST");
        dispatch.dispatchWhenEmptySlot();
        assertEquals("P_F1", requestMapper.getChargingRequest("TST-D-1").getPileId());
    }

    @Test
    @DisplayName("慢充只能进慢充桩（同模式匹配）")
    void dispatchMatchesModeOnly() {
        submit("TST-D-2", 20, "TRICKLE");
        dispatch.dispatchWhenEmptySlot();
        ChargingRequest r = requestMapper.getChargingRequest("TST-D-2");
        assertEquals("P_T1", r.getPileId(), "慢充应进入慢充桩 T1");
    }

    @Test
    @DisplayName("选完成时长最短的桩：F1 已负载，后车进空闲的 F2")
    void dispatchPicksShorterCompletion() {
        submit("TST-D-3", 90, "FAST");          // 90/30 = 3h 负载
        dispatch.dispatchWhenEmptySlot();        // → F1
        assertEquals("P_F1", requestMapper.getChargingRequest("TST-D-3").getPileId());
        submit("TST-D-4", 30, "FAST");           // F1 完成时长 3+1=4h，F2 仅 1h → 选 F2
        dispatch.dispatchWhenEmptySlot();
        assertEquals("P_F2", requestMapper.getChargingRequest("TST-D-4").getPileId());
    }

    @Test
    @DisplayName("两桩负载相等时平局取桩序：第三辆回到 F1")
    void dispatchTieBreakEqualLoad() {
        submit("TST-D-5", 60, "FAST"); dispatch.dispatchWhenEmptySlot();   // F1
        submit("TST-D-6", 60, "FAST"); dispatch.dispatchWhenEmptySlot();   // F2
        submit("TST-D-7", 60, "FAST"); dispatch.dispatchWhenEmptySlot();   // F1,F2 各 2h 平局 → F1
        assertEquals("P_F1", requestMapper.getChargingRequest("TST-D-7").getPileId());
    }

    // ============ 容量 ============

    @Test
    @DisplayName("等候区容量 N=10：第 11 辆快充被拒")
    void waitingAreaCapacityRejects11th() {
        for (int i = 1; i <= 10; i++) {
            submit("TST-W-" + i, 30, "FAST");
        }
        assertEquals(10, waitingLen(RequestMode.FAST), "等候区应已满 10");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> submit("TST-W-11", 30, "FAST"));
        assertTrue(ex.getMessage().contains("等候区已满"), "应提示等候区已满");
    }

    @Test
    @DisplayName("等候区 N=10 为快/慢共用单一区域：6 快+4 慢占满后，第 11 辆(任意模式)被拒")
    void waitingAreaSharedCapacityRejects11thMixed() {
        for (int i = 1; i <= 6; i++) submit("TSTM-F-" + i, 30, "FAST");
        for (int i = 1; i <= 4; i++) submit("TSTM-T-" + i, 20, "TRICKLE");
        assertEquals(6, waitingLen(RequestMode.FAST), "快充等候 6");
        assertEquals(4, waitingLen(RequestMode.TRICKLE), "慢充等候 4");
        // 快/慢共用等候区已满 10：再来一辆（无论快慢）都应被拒——而非各自再放到 10
        assertTrue(assertThrows(IllegalStateException.class,
                () -> submit("TSTM-T-5", 10, "TRICKLE"),
                "共用等候区满 10 后第 11 辆(慢充)应被拒").getMessage().contains("等候区已满"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> submit("TSTM-F-7", 10, "FAST"),
                "共用等候区满 10 后第 11 辆(快充)应被拒").getMessage().contains("等候区已满"));
    }

    @Test
    @DisplayName("控制台动态重配生效(非硬编码)：改 桩数/等候区N/队列M 后即时按新参数运行")
    void reconfigureStationAppliesAtRuntime() {
        // 改成与默认 2/3/N10/M2 完全不同的拓扑：快1·慢1·等候区N=3·队列M=1
        monitoring.reconfigureStation(1, 1, 3, 1);
        java.util.Map<String, Object> cfg = monitoring.getStationConfig();
        assertEquals(1, ((Number) cfg.get("fastNum")).intValue(), "快充桩数应改为 1");
        assertEquals(1, ((Number) cfg.get("trickleNum")).intValue(), "慢充桩数应改为 1");
        assertEquals(3, ((Number) cfg.get("waitingSize")).intValue(), "等候区 N 应改为 3");
        assertEquals(1, ((Number) cfg.get("queueLen")).intValue(), "队列 M 应改为 1");
        assertEquals(2, pileMapper.getAllChargingPiles().size(), "应只剩 1 快+1 慢 = 2 个桩");

        // 新 N=3 即时生效：装满 3 辆后第 4 辆被拒——证明判满读的是动态配置而非写死的 10
        for (int i = 1; i <= 3; i++) submit("RC-" + i, 20, "FAST");
        assertEquals(3, waitingLen(RequestMode.FAST), "等候区应按新容量装满 3");
        assertTrue(assertThrows(IllegalStateException.class,
                () -> submit("RC-4", 20, "FAST"),
                "等候区按新 N=3 判满，第 4 辆应被拒").getMessage().contains("等候区已满"));
        // @Transactional：本用例结束自动回滚，拓扑恢复默认，不影响其它用例
    }

    @Test
    @DisplayName("桩前队列 M=2：5 辆快充仅 4 辆入桩，第 5 辆留等候区")
    void pileQueueCapacityLimitsDispatch() {
        for (int i = 1; i <= 5; i++) submit("TST-M-" + i, 50, "FAST");
        dispatch.dispatchWhenEmptySlot();
        int inPiles = pileQueueLen("P_F1") + pileQueueLen("P_F2");
        assertEquals(4, inPiles, "两快充桩 ×M2 = 4 个排队位");
        assertEquals(1, waitingLen(RequestMode.FAST), "超出的 1 辆应留在等候区");
    }

    // ============ 修改请求 ============

    @Test
    @DisplayName("等候区可改充电量，排队号不变")
    void modifyAmountInWaitingKeepsNumber() {
        String num = submit("TST-A-1", 50, "FAST").getQueueNum();
        ChargingRequest r = app.modifyAmount("TST-A-1", 70.0);
        assertEquals(70.0, r.getRequestAmount(), 1e-9);
        assertEquals(num, r.getQueueNum(), "改量不改排队号");
        assertEquals(CarState.WAITING, r.getRequestStatus());
    }

    @Test
    @DisplayName("充电区（已入桩排队）不允许改充电量")
    void modifyAmountAfterQueuedThrows() {
        submit("TST-A-2", 50, "FAST");
        dispatch.dispatchWhenEmptySlot();
        assertEquals(CarState.QUEUED_AT_PILE, requestMapper.getChargingRequest("TST-A-2").getRequestStatus());
        assertThrows(IllegalStateException.class, () -> app.modifyAmount("TST-A-2", 70.0));
    }

    @Test
    @DisplayName("等候区改模式：重新生成排队号(F→T)，提交时间刷新（排到新队尾）")
    void modifyModeInWaitingRegeneratesNumber() {
        ChargingRequest before = submit("TST-A-3", 50, "FAST");
        assertTrue(before.getQueueNum().startsWith("F"));
        ChargingRequest after = app.modifyMode("TST-A-3", "TRICKLE");
        assertEquals(RequestMode.TRICKLE, after.getRequestMode());
        assertTrue(after.getQueueNum().startsWith("T"), "改慢充后排队号应以 T 开头");
        assertNotEquals(before.getQueueNum(), after.getQueueNum(), "排队号应重新生成");
        assertFalse(after.getRequestTime().isBefore(before.getRequestTime()), "提交时间应被刷新（排到队尾）");
    }

    @Test
    @DisplayName("改模式后排到新模式队尾：原有同模式车在其前方")
    void modifyModeGoesToTail() throws InterruptedException {
        submit("TST-A-4", 20, "TRICKLE");   // 先有一辆慢充
        Thread.sleep(1100);                 // request_time 为秒级，拉开时间保证排序确定
        submit("TST-A-5", 20, "FAST");
        app.modifyMode("TST-A-5", "TRICKLE"); // 改为慢充，提交时间刷新到现在
        ChargingRequest q = app.queryCarState("TST-A-5");
        assertEquals(1, q.getCarsBeforeCount(), "改模式车应排在原有慢充车之后（前方 1 辆）");
    }

    @Test
    @DisplayName("充电区不允许改模式")
    void modifyModeAfterQueuedThrows() {
        submit("TST-A-6", 50, "FAST");
        dispatch.dispatchWhenEmptySlot();
        assertThrows(IllegalStateException.class, () -> app.modifyMode("TST-A-6", "TRICKLE"));
    }

    // ============ 取消 ============

    @Test
    @DisplayName("等候区可取消，等候区计数回落")
    void cancelInWaiting() {
        submit("TST-C-1", 30, "FAST");
        assertEquals(1, waitingLen(RequestMode.FAST));
        app.cancelChargingRequest("TST-C-1");
        assertEquals(CarState.CANCELLED, requestMapper.getChargingRequest("TST-C-1").getRequestStatus());
        assertEquals(0, waitingLen(RequestMode.FAST));
    }

    @Test
    @DisplayName("桩排队区可取消，腾出桩位")
    void cancelInPileQueue() {
        submit("TST-C-2", 30, "FAST");
        dispatch.dispatchWhenEmptySlot();
        String pile = requestMapper.getChargingRequest("TST-C-2").getPileId();
        assertEquals(1, pileQueueLen(pile));
        app.cancelChargingRequest("TST-C-2");
        assertEquals(CarState.CANCELLED, requestMapper.getChargingRequest("TST-C-2").getRequestStatus());
        assertEquals(0, pileQueueLen(pile), "取消后桩位应释放");
    }

    @Test
    @DisplayName("充电中调用取消接口被拒（须走结束充电出账）")
    void cancelWhileChargingThrows() {
        driveToCharging("TST-C-3", 50, "FAST");
        assertThrows(IllegalStateException.class, () -> app.cancelChargingRequest("TST-C-3"));
    }

    // ============ 前车数量 ============

    @Test
    @DisplayName("查看本模式前车等待数量：依次 0/1/2")
    void countCarsBeforeInWaiting() throws InterruptedException {
        submit("TST-B-1", 30, "FAST"); Thread.sleep(1100);
        submit("TST-B-2", 30, "FAST"); Thread.sleep(1100);
        submit("TST-B-3", 30, "FAST");
        assertEquals(0, app.queryCarState("TST-B-1").getCarsBeforeCount());
        assertEquals(1, app.queryCarState("TST-B-2").getCarsBeforeCount());
        assertEquals(2, app.queryCarState("TST-B-3").getCarsBeforeCount());
    }

    // ============ 结束充电 + 详单 + 报表 ============

    @Test
    @DisplayName("结束充电生成账单与详单（10 字段齐全、费用自洽），报表按日聚合含全部字段")
    void endChargingProducesBillDetailAndReport() throws InterruptedException {
        double saved = ChargingSession.TIME_ACCELERATION;
        try {
            ChargingSession.TIME_ACCELERATION = 600.0; // 加速，使会话累计到可计费电量
            String pile = driveToCharging("TST-E-1", 5.0, "FAST");
            Thread.sleep(400);
            Bill bill = app.endCharging("TST-E-1", pile);
            assertNotNull(bill, "应生成账单");

            List<DetailedList> details = billing.requestDetailsByCar("TST-E-1");
            assertFalse(details.isEmpty(), "应生成详单");
            DetailedList d = details.get(details.size() - 1);
            // 详单关键字段非空
            assertNotNull(d.getDetailId(), "详单编号");
            assertNotNull(d.getCreatedAt(), "详单生成时间");
            assertNotNull(d.getPileId(), "充电桩编号");
            assertNotNull(d.getStartTime(), "启动时间");
            assertNotNull(d.getEndTime(), "停止时间");
            assertNotNull(d.getChargeAmount(), "充电电量");
            assertNotNull(d.getChargeDuration(), "充电时长");
            // 费用自洽：总费 = 充电费 + 服务费；服务费 = 0.8×电量；时长 = 电量/功率×60
            assertEquals(d.getChargeFee() + d.getServiceFee(), d.getSubtotalFee(), 0.01, "总费=充电费+服务费");
            assertEquals(d.getChargeAmount() * 0.8, d.getServiceFee(), 0.01, "服务费=0.8×电量");
            assertEquals(d.getChargeAmount() / 30.0 * 60.0, d.getChargeDuration(), 0.1, "时长=电量/功率×60");
            // 时刻与时长自洽（修复“时长对、时刻错”）：起止时刻取自仿真时间轴，
            // 其差值（分钟）应与详单展示的充电时长一致，而非真实墙钟下的数秒之差。
            assertNotNull(d.getStartTime());
            assertNotNull(d.getEndTime());
            double spanMinutes = java.time.Duration.between(d.getStartTime(), d.getEndTime()).toMillis() / 60000.0;
            assertEquals(d.getChargeDuration(), spanMinutes, 0.5,
                    "详单起止时刻之差应等于充电时长（同一仿真时间轴，自洽）");

            List<Map<String, Object>> report = maintenanceMapper.selectReport("%Y-%m-%d");
            assertFalse(report.isEmpty(), "日报表应至少有一行");
            Map<String, Object> row = report.get(0);
            for (String key : new String[]{"time", "pileNo", "chargeCount", "totalDuration",
                    "totalAmount", "totalChargeFee", "totalServiceFee", "totalFee"}) {
                assertTrue(row.containsKey(key), "报表应包含字段 " + key);
            }
        } finally {
            ChargingSession.TIME_ACCELERATION = saved;
        }
    }
}
