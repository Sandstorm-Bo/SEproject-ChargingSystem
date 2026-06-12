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
     * 2.8 扩展调度：全站最短总完成时长批量调度
     * POST /api/dispatch/batch/full
     */
    @PostMapping("/batch/full")
    public Result<DispatchPlan> batchFull() {
        try {
            DispatchPlan plan = stationLock.call(() -> dispatchService.dispatchFullStationBatchMinTotalDuration());
            return Result.success("全站最短总时长调度完成", plan);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
