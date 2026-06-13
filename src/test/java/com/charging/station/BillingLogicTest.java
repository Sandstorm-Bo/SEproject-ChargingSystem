package com.charging.station;

import com.charging.station.domain.TariffPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 计费逻辑测试（纯单元，不依赖 Spring / 数据库）。
 *
 * 校验 {@link TariffPolicy} 的分时电价与跨时段分段计费。
 * 需求口径：峰时 1.0 元/度 [10:00,15:00)∪[18:00,21:00)；平时 0.7 元/度
 * [07:00,10:00)∪[15:00,18:00)∪[21:00,23:00)；谷时 0.4 元/度 [23:00,次日07:00)；服务费 0.8 元/度。
 *
 * 参数化用例的预期 chargeFee/totalFee 由参考仿真器 charging_sim.py 生成（与验收说明同一口径），
 * 用作"标准答案"，因此本测试同时验证了 Java 计费与离散事件仿真一致。
 */
@DisplayName("计费逻辑：分时电价 + 跨时段分段计费")
public class BillingLogicTest {

    /** 与需求/ data.sql 一致的标准电价策略 */
    private TariffPolicy std() {
        TariffPolicy p = new TariffPolicy();
        p.setPeakPrice(1.0);
        p.setFlatPrice(0.7);
        p.setValleyPrice(0.4);
        p.setServiceFeePerKwh(0.8);
        return p;
    }

    /** {label, amount(度), start(HH:mm), power(度/h), 期望充电费, 期望总费用} —— 期望值出自 charging_sim.py */
    static Stream<Arguments> billingCases() {
        return Stream.of(
                // 单一时段
                arguments("谷_慢_2h", 20.0, "00:00", 10.0, 8.0, 24.0),
                arguments("谷_慢_3h", 30.0, "03:00", 10.0, 12.0, 36.0),
                arguments("平_慢_上午", 20.0, "07:00", 10.0, 14.0, 30.0),
                arguments("平_慢_下午", 15.0, "15:00", 10.0, 10.5, 22.5),
                arguments("平_慢_晚间", 10.0, "21:00", 10.0, 7.0, 15.0),
                arguments("峰_慢_上午", 20.0, "10:00", 10.0, 20.0, 36.0),
                arguments("峰_慢_晚间", 15.0, "18:00", 10.0, 15.0, 27.0),
                arguments("峰_快_2h", 60.0, "10:00", 30.0, 60.0, 108.0),
                arguments("谷_快_1h", 30.0, "00:00", 30.0, 12.0, 36.0),
                // 跨两段
                arguments("平转峰", 20.0, "09:00", 10.0, 17.0, 33.0),
                arguments("谷转平", 20.0, "06:00", 10.0, 11.0, 27.0),
                arguments("峰转平", 20.0, "14:00", 10.0, 17.0, 33.0),
                arguments("平转峰至21", 40.0, "17:00", 10.0, 37.0, 69.0),
                arguments("平转谷跨午夜", 50.0, "22:00", 10.0, 23.0, 63.0),
                arguments("平转峰_快", 100.0, "09:00", 30.0, 91.0, 171.0),
                arguments("平转峰_快2", 110.0, "07:00", 30.0, 83.0, 171.0),
                // 跨三段
                arguments("峰平峰", 70.0, "14:00", 10.0, 61.0, 117.0),
                // 跨午夜谷
                arguments("谷跨午夜", 60.0, "23:00", 10.0, 24.0, 72.0),
                // 边界精确起点
                arguments("边界10点", 50.0, "10:00", 10.0, 50.0, 90.0),
                arguments("边界23点", 40.0, "23:00", 10.0, 16.0, 48.0),
                arguments("边界07点", 30.0, "07:00", 10.0, 21.0, 45.0),
                // 非整点起点（仿真器 07:05 起跨段）
                arguments("非整点跨段", 55.0, "07:05", 30.0, 38.5, 82.5)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}: {1}度@{2} {3}kW")
    @MethodSource("billingCases")
    @DisplayName("跨时段分段电费 + 总费用（对照参考仿真器）")
    void crossPeriodBilling(String label, double amount, String start, double power,
                            double expCharge, double expTotal) {
        TariffPolicy p = std();
        LocalTime t = LocalTime.parse(start);

        double charge = p.calculateChargeFee(amount, t, power);
        double service = p.calculateServiceFee(amount);
        double total = charge + service;

        assertEquals(expCharge, charge, 0.02, label + " 充电费应等于参考值");
        assertEquals(expTotal, total, 0.02, label + " 总费用应等于参考值");
        assertEquals(amount * 0.8, service, 1e-9, label + " 服务费应为 0.8×电量");
        assertEquals(charge + service, total, 1e-9, label + " 总费=充电费+服务费");
    }

    @Test
    @DisplayName("分时电价边界判定（左闭右开，整点不漏判为谷时）")
    void priceBoundaries() {
        TariffPolicy p = std();
        // 谷时 0.4：[23:00,次日07:00)
        assertEquals(0.4, p.getPriceByTime(LocalTime.of(0, 0)), "00:00 谷");
        assertEquals(0.4, p.getPriceByTime(LocalTime.of(6, 59)), "06:59 谷");
        assertEquals(0.4, p.getPriceByTime(LocalTime.of(23, 0)), "23:00 谷");
        assertEquals(0.4, p.getPriceByTime(LocalTime.of(23, 59)), "23:59 谷");
        // 平时 0.7：[07,10)∪[15,18)∪[21,23)
        assertEquals(0.7, p.getPriceByTime(LocalTime.of(7, 0)), "07:00 平");
        assertEquals(0.7, p.getPriceByTime(LocalTime.of(9, 59)), "09:59 平");
        assertEquals(0.7, p.getPriceByTime(LocalTime.of(15, 0)), "15:00 平");
        assertEquals(0.7, p.getPriceByTime(LocalTime.of(17, 59)), "17:59 平");
        assertEquals(0.7, p.getPriceByTime(LocalTime.of(21, 0)), "21:00 平");
        assertEquals(0.7, p.getPriceByTime(LocalTime.of(22, 59)), "22:59 平");
        // 峰时 1.0：[10,15)∪[18,21)
        assertEquals(1.0, p.getPriceByTime(LocalTime.of(10, 0)), "10:00 峰");
        assertEquals(1.0, p.getPriceByTime(LocalTime.of(14, 59)), "14:59 峰");
        assertEquals(1.0, p.getPriceByTime(LocalTime.of(18, 0)), "18:00 峰");
        assertEquals(1.0, p.getPriceByTime(LocalTime.of(20, 59)), "20:59 峰");
    }

    @Test
    @DisplayName("峰时电价为 1.0（需求口径，非 1.2）")
    void peakPriceIsOnePointZero() {
        assertEquals(1.0, std().getPriceByTime(LocalTime.of(12, 0)), "峰时应为 1.0");
        assertEquals(1.0, std().getPriceByTime(LocalTime.of(19, 0)), "晚峰应为 1.0");
    }

    @Test
    @DisplayName("零电量计费为 0，不抛异常")
    void zeroAmount() {
        TariffPolicy p = std();
        assertEquals(0.0, p.calculateChargeFee(0.0, LocalTime.of(12, 0), 30.0), 1e-9);
        assertEquals(0.0, p.calculateServiceFee(0.0), 1e-9);
    }

    @Test
    @DisplayName("功率缺失时降级为起始时刻单一电价")
    void nullPowerFallback() {
        TariffPolicy p = std();
        // 12:00 峰时 1.0：20 度 → 20.0
        assertEquals(20.0, p.calculateChargeFee(20.0, LocalTime.of(12, 0), null), 1e-9);
    }
}
