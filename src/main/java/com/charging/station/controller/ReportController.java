package com.charging.station.controller;

import com.charging.station.dto.Result;
import com.charging.station.mapper.MaintenanceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 报表控制器
 * 管理员需求 c)：报表展示，字段含 时间(日/周/月)、充电桩编号、累计充电次数、
 * 累计充电时长、累计充电电量、累计充电费用、累计服务费用、累计总费用。
 */
@RestController
@RequestMapping("/report")
@CrossOrigin(origins = "*")
public class ReportController {

    @Autowired
    private MaintenanceMapper maintenanceMapper;

    /**
     * GET /api/report?period=day|week|month
     */
    @GetMapping
    public Result<List<Map<String, Object>>> report(@RequestParam(defaultValue = "day") String period) {
        try {
            String fmt;
            switch (period.toLowerCase()) {
                case "day":   fmt = "%Y-%m-%d"; break;
                case "week":  fmt = "%x-W%v";   break;
                case "month": fmt = "%Y-%m";    break;
                default:
                    return Result.error("period 应为 day / week / month");
            }
            return Result.success(maintenanceMapper.selectReport(fmt));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
