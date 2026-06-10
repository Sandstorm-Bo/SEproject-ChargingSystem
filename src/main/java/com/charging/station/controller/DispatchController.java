package com.charging.station.controller;

import com.charging.station.dto.Result;
import com.charging.station.service.DispatchService;
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

    /**
     * 优先级故障调度
     * POST /api/dispatch/fault/priority
     */
    @PostMapping("/fault/priority")
    public Result<String> handlePileFaultByPriority(@RequestParam String pileId) {
        try {
            String result = dispatchService.handlePileFaultByPriority(pileId);
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
            String result = dispatchService.handlePileFaultByTimeOrder(pileId);
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
            String result = dispatchService.recoverPileAndRedispatch(pileId);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
