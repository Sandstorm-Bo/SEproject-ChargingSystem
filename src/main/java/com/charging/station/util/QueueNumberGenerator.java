package com.charging.station.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.charging.station.enums.RequestMode;

/**
 * 排队号生成器
 * 根据设计文档 3.1 节定义
 */
public class QueueNumberGenerator {

    private static final Map<RequestMode, AtomicInteger> counters = new ConcurrentHashMap<>();

    static {
        counters.put(RequestMode.FAST, new AtomicInteger(1));
        counters.put(RequestMode.TRICKLE, new AtomicInteger(1));
    }

    /**
     * 生成排队号
     * 快充: F1, F2, F3...
     * 慢充: T1, T2, T3...
     */
    public static String generate(RequestMode mode) {
        String prefix = mode.getPrefix();
        int number = counters.get(mode).getAndIncrement();
        return prefix + number;
    }

    /**
     * 重置计数器（测试用）
     */
    public static void reset() {
        counters.get(RequestMode.FAST).set(1);
        counters.get(RequestMode.TRICKLE).set(1);
    }
}
