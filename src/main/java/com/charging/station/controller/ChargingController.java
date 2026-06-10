package com.charging.station.controller;

import com.charging.station.domain.ChargingRequest;
import com.charging.station.domain.ChargingSession;
import com.charging.station.domain.Bill;
import com.charging.station.dto.ChargingRequestDTO;
import com.charging.station.dto.Result;
import com.charging.station.service.ChargingApplicationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;

/**
 * 充电申请控制器
 * 根据设计文档 2.1 节实现
 * Controller 只负责接收和转发请求，不包含业务逻辑
 */
@RestController
@RequestMapping("/charging")
@CrossOrigin(origins = "*")
public class ChargingController {

    @Autowired
    private ChargingApplicationService chargingApplicationService;

    /**
     * 提交充电申请
     * POST /api/charging/request
     */
    @PostMapping("/request")
    public Result<ChargingRequest> submitChargingRequest(@Valid @RequestBody ChargingRequestDTO dto) {
        try {
            ChargingRequest request = chargingApplicationService.submitChargingRequest(dto);
            return Result.success("充电申请提交成功", request);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 修改充电量
     * POST /api/charging/amount
     */
    @PostMapping("/amount")
    public Result<ChargingRequest> modifyAmount(@RequestBody Map<String, Object> params) {
        try {
            String carId = (String) params.get("carId");
            Object amountObj = params.get("newAmount");
            if (amountObj == null) {
                amountObj = params.get("amount");
            }
            if (amountObj == null) {
                return Result.error("缺少充电量参数");
            }
            Double amount = Double.parseDouble(amountObj.toString());
            ChargingRequest request = chargingApplicationService.modifyAmount(carId, amount);
            return Result.success("充电量修改成功", request);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 修改充电模式
     * POST /api/charging/mode
     */
    @PostMapping("/mode")
    public Result<ChargingRequest> modifyMode(@RequestBody Map<String, Object> params) {
        try {
            String carId = (String) params.get("carId");
            String mode = (String) params.get("mode");
            ChargingRequest request = chargingApplicationService.modifyMode(carId, mode);
            return Result.success("充电模式修改成功", request);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 查看车辆排队状态
     * GET /api/charging/car/{carId}
     */
    @GetMapping("/car/{carId}")
    public Result<ChargingRequest> queryCarState(@PathVariable String carId) {
        try {
            ChargingRequest request = chargingApplicationService.queryCarState(carId);
            return Result.success(request);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 开始充电
     * POST /api/charging/start
     */
    @PostMapping("/start")
    public Result<ChargingSession> startCharging(@RequestParam String carId, @RequestParam String pileId) {
        try {
            ChargingSession session = chargingApplicationService.startCharging(carId, pileId);
            return Result.success("开始充电", session);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 查看充电状态
     * GET /api/charging/state/{carId}
     */
    @GetMapping("/state/{carId}")
    public Result<ChargingSession> queryChargingState(@PathVariable String carId) {
        try {
            ChargingSession session = chargingApplicationService.queryChargingState(carId);
            return Result.success(session);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 取消充电请求
     * POST /api/charging/cancel
     */
    @PostMapping("/cancel")
    public Result<String> cancelRequest(@RequestBody Map<String, Object> params) {
        try {
            String carId = (String) params.get("carId");
            chargingApplicationService.cancelChargingRequest(carId);
            return Result.success("已取消充电", null);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 结束充电（正在充电的车辆）
     * POST /api/charging/end
     */
    @PostMapping("/end")
    public Result<Bill> endCharging(@RequestParam String carId, @RequestParam String pileId) {
        try {
            Bill bill = chargingApplicationService.endCharging(carId, pileId);
            return Result.success("充电已结束，账单已生成", bill);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
