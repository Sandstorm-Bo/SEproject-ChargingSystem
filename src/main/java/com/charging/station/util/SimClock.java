package com.charging.station.util;

import com.charging.station.domain.ChargingSession;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * 仿真时钟（验收用，可选启用）。
 *
 * 验收用例的时间轴从 06:00（谷时）开始推进到 10:50，跨谷/平/峰三个电价时段；
 * 若计费直接使用真实墙钟时间，白天验收时起始时段会落在平/峰，费用与范例完全对不上。
 * 启用后：虚拟时间 = 虚拟起点 + (真实经过时间 × 时间加速因子)，
 * 所有「电价时段判断」都换算到虚拟时间轴上；充电量/时长计算不受影响。
 *
 * 不启用（默认）时 toVirtual 原样返回，行为与历史版本完全一致。
 * 通过 charging.sim-start-time 配置或 POST /api/monitor/sim-clock?start=06:00 启用，
 * 重置数据库时会重新锚定（场景从头开始）。
 */
public final class SimClock {

    private static volatile boolean enabled = false;
    private static volatile LocalTime configuredStart;    // 配置的虚拟起点（如 06:00）
    private static volatile LocalDateTime anchorReal;     // 锚定时的真实时刻
    private static volatile LocalDateTime anchorVirtual;  // 锚定时的虚拟时刻（如当日 06:00）

    private SimClock() {}

    /** 启用并锚定：从现在起，虚拟时间从今天的 startTime 开始按加速因子流逝 */
    public static synchronized void configure(LocalTime startTime) {
        configuredStart = startTime;
        anchorReal = LocalDateTime.now();
        anchorVirtual = LocalDateTime.of(LocalDate.now(), startTime);
        enabled = true;
    }

    /** 重新锚定回配置的起点（重置数据库、场景从头开始时调用）；未启用时无操作 */
    public static synchronized void reanchor() {
        if (enabled && configuredStart != null) {
            configure(configuredStart);
        }
    }

    public static synchronized void disable() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 把真实时刻映射到虚拟时间轴；未启用时原样返回 */
    public static LocalDateTime toVirtual(LocalDateTime real) {
        if (!enabled || real == null) {
            return real;
        }
        double realSeconds = Duration.between(anchorReal, real).toMillis() / 1000.0;
        long virtualMillis = (long) (realSeconds * ChargingSession.TIME_ACCELERATION * 1000.0);
        return anchorVirtual.plus(virtualMillis, ChronoUnit.MILLIS);
    }

    /** 当前虚拟时刻；未启用时即真实当前时刻 */
    public static LocalDateTime nowVirtual() {
        return toVirtual(LocalDateTime.now());
    }
}
