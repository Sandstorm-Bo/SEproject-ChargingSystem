package com.charging.station.controller;

import com.charging.station.domain.Bill;
import com.charging.station.domain.DetailedList;
import com.charging.station.dto.Result;
import com.charging.station.service.BillingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账单控制器
 * 根据设计文档 2.2 节实现
 * Controller 只负责接收和转发请求
 */
@RestController
@RequestMapping("/billing")
@CrossOrigin(origins = "*")
public class BillingController {

    @Autowired
    private BillingService billingService;

    /**
     * 查看账单
     * GET /api/billing/bill?carId=xxx&date=2024-01-01T00:00:00
     */
    @GetMapping("/bill")
    public Result<List<Bill>> requestBill(
            @RequestParam String carId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime date) {
        try {
            List<Bill> bills = billingService.requestBill(carId, date);
            return Result.success(bills);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 查看详单
     * GET /api/billing/detail/{billId}
     */
    @GetMapping("/detail/{billId}")
    public Result<List<DetailedList>> requestDetailedList(@PathVariable String billId) {
        try {
            List<DetailedList> details = billingService.requestDetailedList(billId);
            return Result.success(details);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
