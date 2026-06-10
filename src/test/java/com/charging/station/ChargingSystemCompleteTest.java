package com.charging.station;

import com.charging.station.domain.*;
import com.charging.station.dto.ChargingRequestDTO;
import com.charging.station.enums.CarState;
import com.charging.station.enums.PileStatus;
import com.charging.station.enums.RequestMode;
import com.charging.station.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 完整自动化测试
 * 验证设计文档中的所有功能
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
@org.springframework.test.context.TestPropertySource(properties = "charging.scheduler.enabled=false")
public class ChargingSystemCompleteTest {

    @Autowired
    private ChargingApplicationService chargingApplicationService;

    @Autowired
    private BillingService billingService;

    @Autowired
    private PileManagementService pileManagementService;

    @Autowired
    private MonitoringService monitoringService;

    @Autowired
    private DispatchService dispatchService;

    private static final String TEST_CAR_ID = "京A10001";
    private static final String TEST_PILE_ID = "P_F1";

    @BeforeEach
    public void setUp() {
        System.out.println("\n========================================");
        System.out.println("开始新的测试用例");
        System.out.println("========================================");
    }

    /**
     * 测试用例1：提交充电申请
     * 对应设计文档 2.1.1 E_chargingRequest
     */
    @Test
    @Order(1)
    @DisplayName("测试1：提交充电申请")
    public void test01_SubmitChargingRequest() {
        System.out.println("测试1：提交充电申请（E_chargingRequest）");

        // 准备测试数据
        ChargingRequestDTO dto = new ChargingRequestDTO();
        dto.setCarId(TEST_CAR_ID);
        dto.setRequestAmount(50.0);
        dto.setRequestMode("FAST");

        // 执行测试
        ChargingRequest request = chargingApplicationService.submitChargingRequest(dto);

        // 验证结果
        assertNotNull(request, "充电请求不应为空");
        assertEquals(TEST_CAR_ID, request.getCarId(), "车辆ID应该匹配");
        assertEquals(50.0, request.getRequestAmount(), "充电量应该为50");
        assertEquals(RequestMode.FAST, request.getRequestMode(), "充电模式应该为快充");
        assertEquals(CarState.WAITING, request.getRequestStatus(), "状态应该为等候区排队");
        assertNotNull(request.getQueueNum(), "排队号不应为空");
        assertTrue(request.getQueueNum().startsWith("F"), "快充排队号应以F开头");

        System.out.println("✓ 充电申请提交成功");
        System.out.println("  - 车辆ID: " + request.getCarId());
        System.out.println("  - 排队号: " + request.getQueueNum());
        System.out.println("  - 充电量: " + request.getRequestAmount() + "度");
        System.out.println("  - 状态: " + request.getRequestStatus());
    }

    /**
     * 测试用例2：修改充电量
     * 对应设计文档 2.1.2 Modify_Amount
     */
    @Test
    @Order(2)
    @DisplayName("测试2：修改充电量")
    public void test02_ModifyAmount() {
        System.out.println("测试2：修改充电量（Modify_Amount）");

        // 先提交申请
        ChargingRequestDTO dto = new ChargingRequestDTO();
        dto.setCarId(TEST_CAR_ID);
        dto.setRequestAmount(50.0);
        dto.setRequestMode("FAST");
        chargingApplicationService.submitChargingRequest(dto);

        // 修改充电量
        ChargingRequest updated = chargingApplicationService.modifyAmount(TEST_CAR_ID, 60.0);

        // 验证结果
        assertNotNull(updated, "更新后的请求不应为空");
        assertEquals(60.0, updated.getRequestAmount(), "充电量应该更新为60");
        assertEquals(CarState.WAITING, updated.getRequestStatus(), "状态应该仍为等候区");

        System.out.println("✓ 充电量修改成功");
        System.out.println("  - 原充电量: 50度");
        System.out.println("  - 新充电量: " + updated.getRequestAmount() + "度");
    }

    /**
     * 测试用例3：修改充电模式
     * 对应设计文档 2.1.3 Modify_Mode
     */
    @Test
    @Order(3)
    @DisplayName("测试3：修改充电模式")
    public void test03_ModifyMode() {
        System.out.println("测试3：修改充电模式（Modify_Mode）");

        // 先提交快充申请
        ChargingRequestDTO dto = new ChargingRequestDTO();
        dto.setCarId(TEST_CAR_ID);
        dto.setRequestAmount(50.0);
        dto.setRequestMode("FAST");
        ChargingRequest original = chargingApplicationService.submitChargingRequest(dto);
        String originalQueueNum = original.getQueueNum();

        // 修改为慢充
        ChargingRequest updated = chargingApplicationService.modifyMode(TEST_CAR_ID, "TRICKLE");

        // 验证结果
        assertNotNull(updated, "更新后的请求不应为空");
        assertEquals(RequestMode.TRICKLE, updated.getRequestMode(), "充电模式应该更新为慢充");
        assertNotEquals(originalQueueNum, updated.getQueueNum(), "排队号应该改变");
        assertTrue(updated.getQueueNum().startsWith("T"), "慢充排队号应以T开头");

        System.out.println("✓ 充电模式修改成功");
        System.out.println("  - 原模式: FAST, 排队号: " + originalQueueNum);
        System.out.println("  - 新模式: TRICKLE, 排队号: " + updated.getQueueNum());
    }

    /**
     * 测试用例4：查看车辆状态
     * 对应设计文档 2.1.4 Query_Car_State
     */
    @Test
    @Order(4)
    @DisplayName("测试4：查看车辆状态")
    public void test04_QueryCarState() {
        System.out.println("测试4：查看车辆状态（Query_Car_State）");

        // 先提交申请
        ChargingRequestDTO dto = new ChargingRequestDTO();
        dto.setCarId(TEST_CAR_ID);
        dto.setRequestAmount(50.0);
        dto.setRequestMode("FAST");
        chargingApplicationService.submitChargingRequest(dto);

        // 查询状态
        ChargingRequest request = chargingApplicationService.queryCarState(TEST_CAR_ID);

        // 验证结果
        assertNotNull(request, "查询结果不应为空");
        assertEquals(TEST_CAR_ID, request.getCarId(), "车辆ID应该匹配");
        assertNotNull(request.getQueueNum(), "应该有排队号");
        assertNotNull(request.getRequestStatus(), "应该有状态");

        System.out.println("✓ 车辆状态查询成功");
        System.out.println("  - 车辆ID: " + request.getCarId());
        System.out.println("  - 排队号: " + request.getQueueNum());
        System.out.println("  - 状态: " + request.getRequestStatus());
    }

    /**
     * 测试用例5：启动充电桩
     * 对应设计文档 2.3.1 powerOn
     */
    @Test
    @Order(5)
    @DisplayName("测试5：启动充电桩")
    public void test05_PowerOnPile() {
        System.out.println("测试5：启动充电桩（powerOn）");

        // 启动充电桩
        ChargingPile pile = pileManagementService.powerOn(TEST_PILE_ID);

        // 验证结果
        assertNotNull(pile, "充电桩不应为空");
        assertEquals(PileStatus.POWERED, pile.getStatus(), "状态应该为已上电");

        System.out.println("✓ 充电桩启动成功");
        System.out.println("  - 充电桩ID: " + pile.getPileId());
        System.out.println("  - 状态: " + pile.getStatus());
    }

    /**
     * 测试用例6：设置计费参数
     * 对应设计文档 2.3.2 setParameters
     */
    @Test
    @Order(6)
    @DisplayName("测试6：设置计费参数")
    public void test06_SetParameters() {
        System.out.println("测试6：设置计费参数（setParameters）");

        // 创建计费策略
        TariffPolicy policy = new TariffPolicy();
        policy.setPolicyId("TEST_POLICY");
        policy.setPeakPrice(1.2);
        policy.setFlatPrice(0.7);
        policy.setValleyPrice(0.4);
        policy.setServiceFeePerKwh(0.8);

        // 设置参数
        TariffPolicy saved = pileManagementService.setParameters(policy);

        // 验证结果
        assertNotNull(saved, "保存的策略不应为空");
        assertEquals("已配置", saved.getStatus(), "状态应该为已配置");
        assertEquals(1.2, saved.getPeakPrice(), "峰时电价应该正确");
        assertEquals(0.7, saved.getFlatPrice(), "平时电价应该正确");
        assertEquals(0.4, saved.getValleyPrice(), "谷时电价应该正确");

        System.out.println("✓ 计费参数设置成功");
        System.out.println("  - 峰时电价: " + saved.getPeakPrice() + "元/度");
        System.out.println("  - 平时电价: " + saved.getFlatPrice() + "元/度");
        System.out.println("  - 谷时电价: " + saved.getValleyPrice() + "元/度");
        System.out.println("  - 服务费: " + saved.getServiceFeePerKwh() + "元/度");
    }

    /**
     * 测试用例7：运行充电桩
     * 对应设计文档 2.3.3 Start_ChargingPile
     */
    @Test
    @Order(7)
    @DisplayName("测试7：运行充电桩")
    public void test07_StartChargingPile() {
        System.out.println("测试7：运行充电桩（Start_ChargingPile）");

        // 先设置计费策略
        TariffPolicy policy = new TariffPolicy();
        policy.setPolicyId("TEST_POLICY");
        policy.setPeakPrice(1.2);
        policy.setFlatPrice(0.7);
        policy.setValleyPrice(0.4);
        policy.setServiceFeePerKwh(0.8);
        pileManagementService.setParameters(policy);

        // 启动并运行充电桩
        pileManagementService.powerOn(TEST_PILE_ID);
        ChargingPile pile = pileManagementService.startChargingPile(TEST_PILE_ID);

        // 验证结果
        assertNotNull(pile, "充电桩不应为空");
        assertEquals(PileStatus.IDLE, pile.getStatus(), "状态应该为空闲");
        assertTrue(pile.getIsSchedulable(), "应该可参与调度");

        System.out.println("✓ 充电桩运行成功");
        System.out.println("  - 充电桩ID: " + pile.getPileId());
        System.out.println("  - 状态: " + pile.getStatus());
        System.out.println("  - 可调度: " + pile.getIsSchedulable());
    }

    /**
     * 测试用例8：查看充电桩状态
     * 对应设计文档 2.4 Query_PileState
     */
    @Test
    @Order(8)
    @DisplayName("测试8：查看充电桩状态")
    public void test08_QueryPileState() {
        System.out.println("测试8：查看充电桩状态（Query_PileState）");

        // 查询充电桩状态
        ChargingPile pile = monitoringService.queryPileState(TEST_PILE_ID);

        // 验证结果
        assertNotNull(pile, "充电桩不应为空");
        assertEquals(TEST_PILE_ID, pile.getPileId(), "充电桩ID应该匹配");
        assertNotNull(pile.getStatus(), "应该有状态");
        assertNotNull(pile.getPileType(), "应该有类型");

        System.out.println("✓ 充电桩状态查询成功");
        System.out.println("  - 充电桩ID: " + pile.getPileId());
        System.out.println("  - 桩号: " + pile.getPileNo());
        System.out.println("  - 类型: " + pile.getPileType());
        System.out.println("  - 状态: " + pile.getStatus());
        System.out.println("  - 累计充电次数: " + pile.getTotalChargeCount());
    }

    /**
     * 测试用例9：查看队列状态
     * 对应设计文档 2.5 Query_QueueState
     */
    @Test
    @Order(9)
    @DisplayName("测试9：查看队列状态")
    public void test09_QueryQueueState() {
        System.out.println("测试9：查看队列状态（Query_QueueState）");

        // 查询队列状态
        var queues = monitoringService.queryQueueState();

        // 验证结果
        assertNotNull(queues, "队列列表不应为空");
        assertTrue(queues.size() > 0, "应该有至少一个队列");

        System.out.println("✓ 队列状态查询成功");
        System.out.println("  - 队列数量: " + queues.size());
        for (ChargingQueue queue : queues) {
            System.out.println("    * 充电桩 " + queue.getPileId() +
                             ", 当前长度: " + queue.getCurrentLength() +
                             "/" + queue.getQueueLenM());
        }
    }

    /**
     * 测试用例10：优先级故障调度
     * 对应设计文档 2.7 handlePileFaultByPriority
     */
    @Test
    @Order(10)
    @DisplayName("测试10：优先级故障调度")
    public void test10_HandlePileFaultByPriority() {
        System.out.println("测试10：优先级故障调度（handlePileFaultByPriority）");

        // 执行故障调度
        String result = dispatchService.handlePileFaultByPriority(TEST_PILE_ID);

        // 验证结果
        assertNotNull(result, "调度结果不应为空");
        assertTrue(result.contains("完成"), "应该包含完成信息");

        // 验证充电桩状态
        ChargingPile pile = monitoringService.queryPileState(TEST_PILE_ID);
        assertEquals(PileStatus.FAULT, pile.getStatus(), "充电桩应该标记为故障");
        assertFalse(pile.getIsSchedulable(), "充电桩不应可调度");

        System.out.println("✓ 优先级故障调度成功");
        System.out.println("  - 调度结果: " + result);
        System.out.println("  - 充电桩状态: " + pile.getStatus());
    }

    /**
     * 测试用例11：故障恢复
     * 对应设计文档 2.7 recoverPileAndRedispatch
     */
    @Test
    @Order(11)
    @DisplayName("测试11：故障恢复")
    public void test11_RecoverPile() {
        System.out.println("测试11：故障恢复（recoverPileAndRedispatch）");

        // 先触发故障
        dispatchService.handlePileFaultByPriority(TEST_PILE_ID);

        // 恢复充电桩
        String result = dispatchService.recoverPileAndRedispatch(TEST_PILE_ID);

        // 验证结果
        assertNotNull(result, "恢复结果不应为空");
        assertTrue(result.contains("恢复"), "应该包含恢复信息");

        // 验证充电桩状态
        ChargingPile pile = monitoringService.queryPileState(TEST_PILE_ID);
        assertNotEquals(PileStatus.FAULT, pile.getStatus(), "充电桩不应再是故障状态");

        System.out.println("✓ 充电桩恢复成功");
        System.out.println("  - 恢复结果: " + result);
        System.out.println("  - 充电桩状态: " + pile.getStatus());
    }

    /**
     * 测试用例12：完整充电流程
     * 综合测试
     */
    @Test
    @Order(12)
    @DisplayName("测试12：完整充电流程")
    public void test12_CompleteChargingFlow() {
        System.out.println("测试12：完整充电流程（综合测试）");

        // 1. 设置计费策略
        TariffPolicy policy = new TariffPolicy();
        policy.setPolicyId("TEST_POLICY");
        policy.setPeakPrice(1.2);
        policy.setFlatPrice(0.7);
        policy.setValleyPrice(0.4);
        policy.setServiceFeePerKwh(0.8);
        pileManagementService.setParameters(policy);
        System.out.println("  1. ✓ 计费策略已设置");

        // 2. 启动充电桩
        pileManagementService.powerOn(TEST_PILE_ID);
        pileManagementService.startChargingPile(TEST_PILE_ID);
        System.out.println("  2. ✓ 充电桩已启动");

        // 3. 提交充电申请
        ChargingRequestDTO dto = new ChargingRequestDTO();
        dto.setCarId(TEST_CAR_ID);
        dto.setRequestAmount(50.0);
        dto.setRequestMode("FAST");
        ChargingRequest request = chargingApplicationService.submitChargingRequest(dto);
        assertNotNull(request);
        System.out.println("  3. ✓ 充电申请已提交, 排队号: " + request.getQueueNum());

        // 4. 查看车辆状态
        ChargingRequest status = chargingApplicationService.queryCarState(TEST_CAR_ID);
        assertNotNull(status);
        System.out.println("  4. ✓ 车辆状态: " + status.getRequestStatus());

        // 5. 查看充电桩状态
        ChargingPile pile = monitoringService.queryPileState(TEST_PILE_ID);
        assertNotNull(pile);
        System.out.println("  5. ✓ 充电桩状态: " + pile.getStatus());

        System.out.println("\n✓ 完整充电流程测试通过！");
    }

    @AfterEach
    public void tearDown() {
        System.out.println("========================================\n");
    }
}
