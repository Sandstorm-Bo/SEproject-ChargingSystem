package com.charging.station.controller;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.ChargingQueue;
import com.charging.station.dto.Result;
import com.charging.station.service.MonitoringService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 监控控制器
 * 根据设计文档 2.4/2.5 节实现
 * Controller 只负责接收和转发请求
 */
@RestController
@RequestMapping("/monitor")
@CrossOrigin(origins = "*")
public class MonitorController {

    @Autowired
    private MonitoringService monitoringService;

    /**
     * 查看单个充电桩状态
     * GET /api/monitor/pile/{pileId}
     */
    @GetMapping("/pile/{pileId}")
    public Result<ChargingPile> queryPileState(@PathVariable String pileId) {
        try {
            ChargingPile pile = monitoringService.queryPileState(pileId);
            return Result.success(pile);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 查看所有充电桩状态
     * GET /api/monitor/piles
     */
    @GetMapping("/piles")
    public Result<List<ChargingPile>> queryAllPileStates() {
        try {
            List<ChargingPile> piles = monitoringService.queryAllPileStates();
            return Result.success(piles);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 查看队列状态
     * GET /api/monitor/queue
     */
    @GetMapping("/queue")
    public Result<List<ChargingQueue>> queryQueueState() {
        try {
            List<ChargingQueue> queues = monitoringService.queryQueueState();
            return Result.success(queues);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 重置数据库
     * POST /api/monitor/reset
     */
    @PostMapping("/reset")
    public Result<String> resetDatabase() {
        try {
            // 这里简单返回成功，实际重置由前端调用reset_db.sh
            return Result.success("请使用 reset_db.sh 脚本重置数据库");
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
