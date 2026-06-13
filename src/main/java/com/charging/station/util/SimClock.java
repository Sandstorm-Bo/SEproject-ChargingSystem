package com.charging.station.util;

import com.charging.station.domain.ChargingSession;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * 仿真时钟（系统唯一权威时间源）。
 *
 * <p>设计：整个系统不再使用真实墙钟时间记录业务时刻。一切“发生时刻”（提交请求、开始/结束充电、
 * 故障/恢复、详单起止）都取 {@link #nowVirtual()}；充电量/时长由“虚拟时间差”直接推导
 * （见 {@link ChargingSession#calculateCurrentAmount}），不再二次乘以加速因子。
 *
 * <p>这样做的根本目的：让“显示的时刻”与“显示的时长”始终自洽。历史实现把真实墙钟时刻存库、
 * 仅在展示时换算，导致倍速一改、锚点一变，过去时刻就被重算错位——出现“时长对、时刻错”。
 * 现在虚拟时刻一旦产生即被冻结存库，倍速变化只影响“此后”的流速，过去记录不受影响。
 *
 * <p>虚拟时间 = 锚定虚拟时刻 + (真实经过秒数 × 时间加速因子)。
 * <ul>
 *   <li>{@link #configure(LocalTime)}：把虚拟起点跳转到今天的某时刻（控制台“仿真起始时间”、重置场景）。</li>
 *   <li>{@link #prepareSpeedChange()}：变更倍速前调用，把当前虚拟时刻设为新锚点，保证时钟连续不跳变。</li>
 *   <li>{@link #restart()}：重置数据库时回到配置的起点，场景从头开始。</li>
 * </ul>
 * 加速因子沿用 {@link ChargingSession#TIME_ACCELERATION}（1 真实秒 = N 仿真秒）。
 */
public final class SimClock {

    private static volatile LocalTime configuredStart;    // 配置的虚拟起点（如 06:00）；null 表示用真实当日时刻
    private static volatile LocalDateTime anchorReal;     // 锚定时的真实时刻
    private static volatile LocalDateTime anchorVirtual;  // 锚定时的虚拟时刻

    private SimClock() {}

    /** 懒锚定：首次取用且尚未配置时，默认虚拟起点 = 当前真实日期时刻（之后按倍速流逝） */
    private static synchronized void ensureAnchored() {
        if (anchorReal == null || anchorVirtual == null) {
            anchorReal = LocalDateTime.now();
            anchorVirtual = anchorReal;
        }
    }

    /** 把虚拟起点跳转到今天的 startTime，并从现在起按加速因子流逝（控制台设定起始时间 / 配置项） */
    public static synchronized void configure(LocalTime startTime) {
        configuredStart = startTime;
        anchorReal = LocalDateTime.now();
        anchorVirtual = LocalDateTime.of(LocalDate.now(), startTime);
    }

    /**
     * 变更倍速前调用：把“当前虚拟时刻”设为新锚点、真实锚点设为现在。
     * 这样改变 {@link ChargingSession#TIME_ACCELERATION} 后，虚拟时钟从当前值继续、不发生跳变，
     * 之前已冻结存库的虚拟时刻也不受影响。
     */
    public static synchronized void prepareSpeedChange() {
        LocalDateTime current = nowVirtual();
        anchorReal = LocalDateTime.now();
        anchorVirtual = current;
    }

    /** 重置数据库 / 场景从头开始：回到配置的起点（未配置过则回到当前真实时刻） */
    public static synchronized void restart() {
        if (configuredStart != null) {
            configure(configuredStart);
        } else {
            anchorReal = LocalDateTime.now();
            anchorVirtual = anchorReal;
        }
    }

    /** 兼容旧调用名（等价 {@link #restart()}） */
    public static synchronized void reanchor() {
        restart();
    }

    /** 仿真时钟始终启用（虚拟时间是系统唯一权威时间源） */
    public static boolean isEnabled() {
        return true;
    }

    /** 把真实时刻映射到虚拟时间轴（仅供少数仍持有真实时刻的场景使用；业务时刻一律直接用 {@link #nowVirtual()}） */
    public static LocalDateTime toVirtual(LocalDateTime real) {
        if (real == null) {
            return null;
        }
        ensureAnchored();
        double realSeconds = Duration.between(anchorReal, real).toMillis() / 1000.0;
        long virtualMillis = (long) (realSeconds * ChargingSession.TIME_ACCELERATION * 1000.0);
        return anchorVirtual.plus(virtualMillis, ChronoUnit.MILLIS);
    }

    /** 当前虚拟时刻（系统取用的“现在”） */
    public static LocalDateTime nowVirtual() {
        ensureAnchored();
        double realSeconds = Duration.between(anchorReal, LocalDateTime.now()).toMillis() / 1000.0;
        long virtualMillis = (long) (realSeconds * ChargingSession.TIME_ACCELERATION * 1000.0);
        return anchorVirtual.plus(virtualMillis, ChronoUnit.MILLIS);
    }
}
