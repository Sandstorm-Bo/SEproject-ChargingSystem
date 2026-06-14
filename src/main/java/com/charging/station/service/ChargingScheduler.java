package com.charging.station.service;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.ChargingRequest;
import com.charging.station.domain.ChargingSession;
import com.charging.station.enums.CarState;
import com.charging.station.enums.PileStatus;
import com.charging.station.mapper.PileMapper;
import com.charging.station.mapper.RequestMapper;
import com.charging.station.mapper.SessionMapper;
import com.charging.station.util.SimClock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 充电生命周期编排器 —— <b>完全事件驱动、零轮询</b>。
 *
 * <p>不再用周期性 tick 反复查询，而是：
 * <ul>
 *   <li><b>"有空位"类事件</b>（申请 / 取消 / 结束 / 故障 / 恢复 / 指派 / 启停桩）发生后，由对应服务在
 *       <b>事务提交后</b>经 {@link SchedulerTrigger} 回调 {@link #onSchedulingEvent()}，触发一次"对账"
 *       {@code reconcile}：等候区→桩排队区→空闲桩队首立即开充。</li>
 *   <li><b>"充满"用一次性定时器精确触发，而非轮询</b>：每辆车开始充电时，其充满时刻是确定可算的
 *       （剩余电量/功率=剩余虚拟小时，再 ÷ 倍速换算成真实毫秒），据此安排一个 <b>one-shot</b> 定时任务，
 *       到点结算并接力下一辆。空闲时零 CPU、结算零延迟。</li>
 *   <li><b>倍速 / 桩功率变化</b>：充满真实时刻随之改变，故 {@link #onSpeedChange()} 取消并按新值重排全部在充定时器。</li>
 *   <li><b>启动恢复</b>：{@link #onStartup()} 扫描已有在充会话重建定时器，支持进程重启后继续。</li>
 * </ul>
 *
 * <p>开关 {@code charging.scheduler.enabled=false}（测试）时本编排器整体不创建，业务方通过
 * {@link SchedulerTrigger} 取不到它 → 自动退化为"纯手工驱动"，保证单测确定性。
 *
 * <p>并发：所有对账、定时器映射、重入标志的读写都在 {@link StationLock}（可重入公平锁）内串行化，
 * 故内部用普通 {@link HashMap} 即可。定时器线程触发时同样先取全站锁再结算。
 */
@Service
@ConditionalOnProperty(name = "charging.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ChargingScheduler {

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private ChargingApplicationService chargingApplicationService;

    @Autowired
    private PileMapper pileMapper;

    @Autowired
    private RequestMapper requestMapper;

    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private StationLock stationLock;

    /** 一次性“充满结算”定时器线程池（非轮询：每个在充会话对应一个精确的 one-shot 任务）。 */
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);
    /** pileId -> 该桩当前在充会话的“充满”定时器 */
    private final Map<String, ScheduledFuture<?>> timers = new HashMap<>();
    /** pileId -> 该定时器对应的 sessionId（判定定时器是否已过期、需为新会话重排） */
    private final Map<String, String> timerSession = new HashMap<>();

    /** 对账重入保护：reconcile 内部调用 startCharging 等会在其提交后再次回调本类，置脏后单层循环收敛，避免无限递归。 */
    private boolean reconciling = false;
    private boolean dirty = false;

    /**
     * 运行期开关：自动「等候区→桩排队区」叫号调度是否开启（默认开）。
     * 关闭后车辆停留等候区，便于用 2.8 批量调度 / 指定充电桩 / 故障调度等“调度方式”手动接管。
     * 桩内的「开始充电」「充满结算」始终运行，已入桩的车照常充电。
     */
    private static volatile boolean autoDispatch = true;

    public static void setAutoDispatch(boolean enabled) {
        autoDispatch = enabled;
    }

    public static boolean isAutoDispatch() {
        return autoDispatch;
    }

    /** 启动后做一次对账：重建已有在充会话的定时器、并启动待充车（支持进程重启恢复）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        onSchedulingEvent();
    }

    /** 业务事件触发的对账入口（由 {@link SchedulerTrigger} 在事务提交后调用）。 */
    public void onSchedulingEvent() {
        stationLock.run(this::pump);
    }

    /** 倍速 / 功率变化：取消全部在充定时器，按新值重排（在充会话保留，只改“多久后充满”）。 */
    public void onSpeedChange() {
        stationLock.run(() -> {
            cancelAllTimers();
            pump();
        });
    }

    // ============ 对账核心（全程持全站锁；reconcile 内部调用会经事务同步重入，靠脏标志收敛） ============

    private void pump() {
        if (reconciling) {
            dirty = true;
            return;
        }
        reconciling = true;
        try {
            do {
                dirty = false;
                reconcileOnce();
            } while (dirty);
        } finally {
            reconciling = false;
        }
    }

    private void reconcileOnce() {
        // 0) §7a/§7b 故障队列续排：其它同类型桩一旦腾出空位，先把仍滞留在故障桩排队队列里的车按号优先移入，
        //    严格优先于等候区叫号；故障队列清空前 dispatchWhenEmptySlot 会跳过该模式（暂停叫号）。
        safely(dispatchService::drainFaultQueues);
        // 1) 等候区 -> 桩排队区（自动叫号；可被运行期开关暂停以演示批量/指定调度方式）
        if (autoDispatch) {
            safely(dispatchService::dispatchWhenEmptySlot);
        }
        // 2) 空闲桩队首 -> 叫号并【立即开始充电】（同一次对账内完成，不拖延）
        for (ChargingPile pile : pileMapper.getAllChargingPiles()) {
            if (pile.getStatus() != PileStatus.IDLE || !Boolean.TRUE.equals(pile.getIsSchedulable())) {
                continue;
            }
            ChargingRequest head = firstAtPileHead(pile.getPileId());
            if (head == null) {
                continue;
            }
            if (head.getRequestStatus() == CarState.QUEUED_AT_PILE) {
                safely(() -> chargingApplicationService.callVehicle(head.getCarId()));
            }
            safely(() -> chargingApplicationService.startCharging(head.getCarId(), pile.getPileId()));
        }
        // 3) 同步每个桩的“充满定时器”：在充会话若尚无对应定时器（或换了新会话）则按精确充满时刻安排；
        //    桩上已无在充会话则清理其过期定时器。
        for (ChargingPile pile : pileMapper.getAllChargingPiles()) {
            String pid = pile.getPileId();
            ChargingSession session = sessionMapper.getActiveSessionByPileId(pid);
            if (session != null) {
                if (!session.getSessionId().equals(timerSession.get(pid))) {
                    scheduleCompletion(pile, session);
                }
            } else if (timers.containsKey(pid)) {
                cancelTimer(pid);
            }
        }
    }

    /** 按“充满真实时刻”安排一次性结算定时器：剩余电量/功率=剩余虚拟小时，再 ÷ 倍速换算成真实毫秒。 */
    private void scheduleCompletion(ChargingPile pile, ChargingSession session) {
        cancelTimer(pile.getPileId());
        double charged = session.calculateCurrentAmount(pile.getRatedPower(), SimClock.nowVirtual());
        double remainingKwh = Math.max(0.0, session.getRequestAmount() - charged);
        double remainingVirtualHours = remainingKwh / pile.getRatedPower();
        // 用 ceil 略保守，避免取整误差导致定时器“早触发一瞬”而尚未充满（早触发也有自愈兜底，见 onCompletionTimer）
        long realMillis = (long) Math.ceil(remainingVirtualHours * 3600_000.0 / ChargingSession.TIME_ACCELERATION);
        final String pid = pile.getPileId();
        final String sid = session.getSessionId();
        ScheduledFuture<?> f = exec.schedule(() -> onCompletionTimer(pid, sid),
                Math.max(0L, realMillis), TimeUnit.MILLISECONDS);
        timers.put(pid, f);
        timerSession.put(pid, sid);
    }

    /** “充满”定时器触发：结算该会话并接力下一辆。带防御校验——会话已变/被中断/因取整未满时自愈（重排或跳过）。 */
    private void onCompletionTimer(String pileId, String sessionId) {
        stationLock.run(() -> {
            timers.remove(pileId);
            timerSession.remove(pileId);
            ChargingSession s = sessionMapper.getActiveSessionByPileId(pileId);
            if (s != null && sessionId.equals(s.getSessionId())) {
                ChargingPile pile = pileMapper.getPile(pileId);
                double charged = s.calculateCurrentAmount(pile.getRatedPower(), SimClock.nowVirtual());
                if (charged >= s.getRequestAmount() - 1e-6) {
                    // 结算：endCharging 事务提交后会经 SchedulerTrigger 回调 onSchedulingEvent，接力下一辆
                    safely(() -> chargingApplicationService.endCharging(s.getCarId(), pileId));
                    return;
                }
            }
            // 会话已结束 / 被故障中断 / 取整误差尚未充满 → 对账（必要时为剩余量重排该桩定时器）
            pump();
        });
    }

    private void cancelAllTimers() {
        timers.values().forEach(f -> f.cancel(false));
        timers.clear();
        timerSession.clear();
    }

    private void cancelTimer(String pileId) {
        ScheduledFuture<?> f = timers.remove(pileId);
        if (f != null) {
            f.cancel(false);
        }
        timerSession.remove(pileId);
    }

    /** 取某桩排队区队首（QUEUED_AT_PILE 或 CALLED，按 queue_position 升序）的请求 */
    private ChargingRequest firstAtPileHead(String pileId) {
        for (ChargingRequest r : requestMapper.getRequestsByPileId(pileId)) {
            if (r.getRequestStatus() == CarState.QUEUED_AT_PILE || r.getRequestStatus() == CarState.CALLED) {
                return r;
            }
        }
        return null;
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (Exception ignored) {
            // 某一步失败不应中断整个对账；下一次事件/定时器会再次对账自愈
        }
    }
}
