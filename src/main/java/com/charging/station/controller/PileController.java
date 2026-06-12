package com.charging.station.controller;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.TariffPolicy;
import com.charging.station.dto.Result;
import com.charging.station.service.PileManagementService;
import com.charging.station.service.StationLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 充电桩管理控制器
 * 根据设计文档 2.3 节实现
 * Controller 只负责接收和转发请求
 */
@RestController
@RequestMapping("/pile")
@CrossOrigin(origins = "*")
public class PileController {

    @Autowired
    private PileManagementService pileManagementService;

    // 桩启停/参数变更影响调度结果，统一持全站锁
    @Autowired
    private StationLock stationLock;

    /**
     * 启动充电桩
     * POST /api/pile/poweron
     */
    @PostMapping("/poweron")
    public Result<ChargingPile> powerOn(@RequestParam String pileId) {
        try {
            ChargingPile pile = stationLock.call(() -> pileManagementService.powerOn(pileId));
            return Result.success("充电桩启动成功", pile);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 设置计费参数
     * POST /api/pile/parameters
     */
    @PostMapping("/parameters")
    public Result<TariffPolicy> setParameters(@RequestBody TariffPolicy policy) {
        try {
            TariffPolicy savedPolicy = stationLock.call(() -> pileManagementService.setParameters(policy));
            return Result.success("计费参数设置成功", savedPolicy);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 运行充电桩
     * POST /api/pile/run
     */
    @PostMapping("/run")
    public Result<ChargingPile> startChargingPile(@RequestParam String pileId) {
        try {
            ChargingPile pile = stationLock.call(() -> pileManagementService.startChargingPile(pileId));
            return Result.success("充电桩运行成功", pile);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 关闭充电桩
     * POST /api/pile/poweroff
     */
    @PostMapping("/poweroff")
    public Result<ChargingPile> powerOff(@RequestParam String pileId) {
        try {
            ChargingPile pile = stationLock.call(() -> pileManagementService.powerOff(pileId));
            return Result.success("充电桩关闭成功", pile);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 修改充电桩功率
     * PUT /api/pile/power
     */
    @PutMapping("/power")
    public Result<ChargingPile> setPilePower(@RequestParam String pileId, @RequestParam Double powerKw) {
        try {
            ChargingPile pile = stationLock.call(() -> pileManagementService.setPileParameters(pileId, powerKw));
            return Result.success("充电桩功率修改成功", pile);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
