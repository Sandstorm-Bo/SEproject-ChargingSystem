package com.charging.station.controller;

import com.charging.station.domain.DispatchPlan;
import com.charging.station.dto.Result;
import com.charging.station.enums.RequestMode;
import com.charging.station.service.DispatchService;
import com.charging.station.service.FaultSchedulerService;
import com.charging.station.service.StationLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 调度控制器
 * 根据设计文档 2.6/2.7 节实现
 * Controller 只负责接收和转发请求
 */
@RestController
@RequestMapping("/dispatch")
@CrossOrigin(origins = "*")
public class DispatchController {

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private FaultSchedulerService faultSchedulerService;

    // 状态变更类调度操作统一持全站锁，与秒级调度循环、定时故障注入互斥
    @Autowired
    private StationLock stationLock;

    /**
     * 手动指派：把等候区车辆指派到指定充电桩（YL-024 指定充电桩）
     * POST /api/dispatch/assign?carId=xxx&pileId=P_F1
     */
    @PostMapping("/assign")
    public Result<String> assignCarToPile(@RequestParam String carId, @RequestParam String pileId) {
        try {
            String result = stationLock.call(() -> dispatchService.assignCarToPile(carId, pileId));
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 优先级故障调度
     * POST /api/dispatch/fault/priority
     */
    @PostMapping("/fault/priority")
    public Result<String> handlePileFaultByPriority(@RequestParam String pileId) {
        try {
            String result = stationLock.call(() -> dispatchService.handlePileFaultByPriority(pileId));
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 时间顺序故障调度
     * POST /api/dispatch/fault/timeorder
     */
    @PostMapping("/fault/timeorder")
    public Result<String> handlePileFaultByTimeOrder(@RequestParam String pileId) {
        try {
            String result = stationLock.call(() -> dispatchService.handlePileFaultByTimeOrder(pileId));
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 故障恢复
     * POST /api/dispatch/recover
     */
    @PostMapping("/recover")
    public Result<String> recoverPileAndRedispatch(@RequestParam String pileId) {
        try {
            String result = stationLock.call(() -> dispatchService.recoverPileAndRedispatch(pileId));
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 定时故障编排（验收可视化）：faultDelaySec 秒后注入故障，recoverDelaySec 秒后自动恢复（<=0 不恢复）。
     * POST /api/dispatch/fault/schedule?pileId=P_F1&strategy=priority&faultDelaySec=10&recoverDelaySec=20
     */
    @PostMapping("/fault/schedule")
    public Result<String> scheduleFault(@RequestParam String pileId,
                                        @RequestParam(defaultValue = "priority") String strategy,
                                        @RequestParam(defaultValue = "0") long faultDelaySec,
                                        @RequestParam(defaultValue = "0") long recoverDelaySec) {
        try {
            faultSchedulerService.schedule(pileId, strategy, faultDelaySec, recoverDelaySec);
            String msg = String.format("已安排 %s：%ds 后注入%s故障", pileId, faultDelaySec,
                    "timeorder".equalsIgnoreCase(strategy) ? "时间顺序" : "优先级");
            msg += recoverDelaySec > 0 ? String.format("，再 %ds 后自动恢复", recoverDelaySec) : "（不自动恢复）";
            return Result.success(msg);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 取消定时故障：不带 pileId 取消全部。
     * POST /api/dispatch/fault/schedule/cancel[?pileId=P_F1]
     */
    @PostMapping("/fault/schedule/cancel")
    public Result<String> cancelScheduledFault(@RequestParam(required = false) String pileId) {
        try {
            if (pileId == null || pileId.isBlank()) {
                faultSchedulerService.cancelAll();
                return Result.success("已取消全部定时故障安排");
            }
            faultSchedulerService.cancel(pileId);
            return Result.success("已取消 " + pileId + " 的定时故障安排");
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 2.8 扩展调度：单次最短总完成时长调度
     * POST /api/dispatch/batch/single?mode=FAST&emptySlots=2
     */
    @PostMapping("/batch/single")
    public Result<DispatchPlan> batchSingle(@RequestParam String mode,
                                            @RequestParam(defaultValue = "2") int emptySlots) {
        try {
            DispatchPlan plan = stationLock.call(() -> dispatchService.dispatchSingleBatchMinTotalDuration(
                    RequestMode.fromString(mode), emptySlots));
            return Result.success("单次最短总时长调度完成", plan);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 切换"自动叫号调度"运行期开关：关闭后车辆停留等候区，便于用批量/指定/故障等调度方式手动接管。
     * POST /api/dispatch/auto?enabled=false
     */
    @PostMapping("/auto")
    public Result<Boolean> setAutoDispatch(@RequestParam boolean enabled) {
        com.charging.station.service.ChargingScheduler.setAutoDispatch(enabled);
        return Result.success(enabled ? "自动叫号调度已开启"
                : "自动叫号调度已暂停：车辆停留等候区，可用批量/指定/故障调度方式手动调度", enabled);
    }

    /**
     * §27 切换"故障在充车"处理口径（验收随机/按老师认可口径）：
     *   terminal（默认）= 按已充量出账并置中断终态、剩余电量不重排（§27/§28/§29 字面口径）；
     *   requeue        = 按已充量出账后，剩余电量随该车最高优先重新调度续充。
     * POST /api/dispatch/interrupt-policy?policy=terminal|requeue
     */
    @PostMapping("/interrupt-policy")
    public Result<String> setInterruptPolicy(@RequestParam String policy) {
        boolean requeue = "requeue".equalsIgnoreCase(policy);
        DispatchService.setInterruptPolicy(requeue
                ? DispatchService.InterruptPolicy.REQUEUE
                : DispatchService.InterruptPolicy.TERMINAL);
        return Result.success(requeue
                ? "故障在充车口径：剩余电量续充（requeue）"
                : "故障在充车口径：终态离开（terminal，默认）",
                DispatchService.getInterruptPolicy().name());
    }

    /**
     * 查询当前 §27 故障在充车口径。
     * GET /api/dispatch/interrupt-policy
     */
    @GetMapping("/interrupt-policy")
    public Result<String> getInterruptPolicy() {
        return Result.success(DispatchService.getInterruptPolicy().name());
    }

    /**
     * §7 显式设置"当前生效的故障调度策略"（服务器端持久持有；验收随机选择启用哪种即调用此项）。
     * POST /api/dispatch/fault-strategy?strategy=priority|timeorder
     */
    @PostMapping("/fault-strategy")
    public Result<String> setFaultStrategy(@RequestParam String strategy) {
        boolean timeorder = "timeorder".equalsIgnoreCase(strategy) || "time_order".equalsIgnoreCase(strategy);
        DispatchService.setActiveFaultStrategy(timeorder
                ? DispatchService.FaultStrategy.TIME_ORDER
                : DispatchService.FaultStrategy.PRIORITY);
        return Result.success(timeorder ? "当前生效故障策略：时间顺序调度" : "当前生效故障策略：优先级调度",
                DispatchService.getActiveFaultStrategy().name());
    }

    /**
     * 查询当前生效的故障调度策略。
     * GET /api/dispatch/fault-strategy
     */
    @GetMapping("/fault-strategy")
    public Result<String> getFaultStrategy() {
        return Result.success(DispatchService.getActiveFaultStrategy().name());
    }

    /**
     * 按【当前生效策略】触发桩故障调度（不带策略参数；服务器用 activeFaultStrategy 决定优先级/时间顺序）。
     * POST /api/dispatch/fault?pileId=P_F1
     */
    @PostMapping("/fault")
    public Result<String> handlePileFault(@RequestParam String pileId) {
        try {
            String result = stationLock.call(() -> dispatchService.handlePileFault(pileId));
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 一览当前服务器端调度配置（供演示/核验"正在使用的调度策略"）：故障策略、在充车口径、自动叫号开关。
     * GET /api/dispatch/strategy
     */
    @GetMapping("/strategy")
    public Result<java.util.Map<String, Object>> currentStrategy() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("faultStrategy", DispatchService.getActiveFaultStrategy().name());
        m.put("interruptPolicy", DispatchService.getInterruptPolicy().name());
        m.put("autoDispatch", com.charging.station.service.ChargingScheduler.isAutoDispatch());
        return Result.success(m);
    }

    /**
     * 2.8 扩展调度：全站最短总完成时长批量调度（不区分模式、任意车任意桩）。
     * 仅当到站车辆达到全部车位数时触发；force=true 跳过门槛（演示用）。
     * POST /api/dispatch/batch/full[?force=true]
     */
    @PostMapping("/batch/full")
    public Result<DispatchPlan> batchFull(@RequestParam(defaultValue = "false") boolean force) {
        try {
            DispatchPlan plan = stationLock.call(() -> dispatchService.dispatchFullStationBatchMinTotalDuration(force));
            return Result.success("全站最短总时长调度完成", plan);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量调度触发门槛状态：到站车辆数 / 全部车位数。
     * GET /api/dispatch/batch/full/status
     */
    @GetMapping("/batch/full/status")
    public Result<java.util.Map<String, Object>> batchFullStatus() {
        try {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            int arrived = dispatchService.countArrivedCars();
            int capacity = dispatchService.totalStationCapacity();
            m.put("arrived", arrived);
            m.put("capacity", capacity);
            m.put("ready", arrived >= capacity);
            return Result.success(m);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
