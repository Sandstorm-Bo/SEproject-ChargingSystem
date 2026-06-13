package com.charging.station;

import com.charging.station.enums.RequestMode;
import com.charging.station.util.QueueNumberGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 排队号生成逻辑测试（纯单元）。
 * 需求：快充号码首字母 F、从 1 递增（F1,F2…）；慢充首字母 T、从 1 递增（T1,T2…）；两类计数独立。
 */
@DisplayName("排队号生成：F/T 前缀 + 各自递增")
public class QueueNumberLogicTest {

    @Test
    @DisplayName("F/T 各自从 1 顺序递增且互相独立")
    void sequentialAndIndependent() {
        QueueNumberGenerator.reset();
        assertEquals("F1", QueueNumberGenerator.generate(RequestMode.FAST));
        assertEquals("F2", QueueNumberGenerator.generate(RequestMode.FAST));
        assertEquals("T1", QueueNumberGenerator.generate(RequestMode.TRICKLE));
        assertEquals("F3", QueueNumberGenerator.generate(RequestMode.FAST));
        assertEquals("T2", QueueNumberGenerator.generate(RequestMode.TRICKLE));
        assertEquals("T3", QueueNumberGenerator.generate(RequestMode.TRICKLE));
        assertEquals("F4", QueueNumberGenerator.generate(RequestMode.FAST));
    }

    @Test
    @DisplayName("reset 后重新从 1 开始")
    void resetRestartsFromOne() {
        QueueNumberGenerator.generate(RequestMode.FAST);
        QueueNumberGenerator.generate(RequestMode.TRICKLE);
        QueueNumberGenerator.reset();
        assertEquals("F1", QueueNumberGenerator.generate(RequestMode.FAST));
        assertEquals("T1", QueueNumberGenerator.generate(RequestMode.TRICKLE));
    }

    @Test
    @DisplayName("前缀符合需求：快充 F、慢充 T")
    void prefixes() {
        assertEquals("F", RequestMode.FAST.getPrefix());
        assertEquals("T", RequestMode.TRICKLE.getPrefix());
    }

    @Test
    @DisplayName("连续生成不重号（同模式严格递增）")
    void noDuplicateWithinMode() {
        QueueNumberGenerator.reset();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            assertTrue(seen.add(QueueNumberGenerator.generate(RequestMode.FAST)), "快充号码不应重复");
        }
        assertEquals(50, seen.size());
        assertTrue(seen.contains("F1") && seen.contains("F50"));
    }
}
