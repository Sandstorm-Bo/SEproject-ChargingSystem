package com.charging.station.controller;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.ChargingQueue;
import com.charging.station.domain.ChargingSession;
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
     * 重置数据库（清空充电业务数据并复位充电桩，等价 reset_db.sh）
     * POST /api/monitor/reset
     */
    @PostMapping("/reset")
    public Result<String> resetDatabase() {
        try {
            monitoringService.resetAll();
            return Result.success("已重置：充电请求/会话/账单已清空，充电桩复位为空闲", null);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 查询仿真倍速（1 真实秒 = N 仿真秒）
     * GET /api/monitor/sim-speed
     */
    @GetMapping("/sim-speed")
    public Result<Double> getSimSpeed() {
        return Result.success(ChargingSession.TIME_ACCELERATION);
    }

    /**
     * 设置仿真倍速（演示用：1~600）
     * POST /api/monitor/sim-speed?value=120
     */
    @PostMapping("/sim-speed")
    public Result<Double> setSimSpeed(@RequestParam double value) {
        try {
            if (value < 1 || value > 600) {
                return Result.error("仿真倍速需在 1 ~ 600 之间");
            }
            ChargingSession.TIME_ACCELERATION = value;
            return Result.success("仿真倍速已更新", value);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
