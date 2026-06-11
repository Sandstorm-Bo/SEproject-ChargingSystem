package com.charging.station.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 定时故障编排（验收可视化用）：
 * 在指定的「故障倒计时」后对某桩注入故障（优先级/时间顺序调度），并可在其后的「持续时长」自动恢复。
 * 这样验收时可一键安排一个完整的「故障→重新调度→恢复→再调度」过程，在大屏上直观观看。
 */
@Service
public class FaultSchedulerService {

    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);
    private final Map<String, List<ScheduledFuture<?>>> jobs = new ConcurrentHashMap<>();

    @Autowired
    private DispatchService dispatchService;

    /**
     * 安排定时故障。
     * @param pileId          目标充电桩
     * @param strategy        priority / timeorder
     * @param faultDelaySec   多少秒后注入故障（0 = 立即）
     * @param recoverDelaySec 故障后多少秒自动恢复（<=0 = 不自动恢复）
     */
    public synchronized void schedule(String pileId, String strategy, long faultDelaySec, long recoverDelaySec) {
        cancel(pileId); // 同一桩的旧安排先取消，避免叠加
        long fd = Math.max(0, faultDelaySec);
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        futures.add(exec.schedule(() -> runFault(pileId, strategy), fd, TimeUnit.SECONDS));
        if (recoverDelaySec > 0) {
            futures.add(exec.schedule(() -> runRecover(pileId), fd + recoverDelaySec, TimeUnit.SECONDS));
        }
        jobs.put(pileId, futures);
    }

    private void runFault(String pileId, String strategy) {
        try {
            if ("timeorder".equalsIgnoreCase(strategy)) {
                dispatchService.handlePileFaultByTimeOrder(pileId);
            } else {
                dispatchService.handlePileFaultByPriority(pileId);
            }
        } catch (Exception ignored) {
            // 桩已故障/不存在等，忽略；下次安排可重试
        }
    }

    private void runRecover(String pileId) {
        try {
            dispatchService.recoverPileAndRedispatch(pileId);
        } catch (Exception ignored) {
        }
    }

    /** 取消某桩的待执行安排 */
    public synchronized void cancel(String pileId) {
        List<ScheduledFuture<?>> futures = jobs.remove(pileId);
        if (futures != null) {
            futures.forEach(f -> f.cancel(false));
        }
    }

    /** 取消全部待执行安排 */
    public synchronized void cancelAll() {
        jobs.values().forEach(list -> list.forEach(f -> f.cancel(false)));
        jobs.clear();
    }
}
