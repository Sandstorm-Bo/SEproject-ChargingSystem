package com.charging.station.config;

import com.charging.station.domain.ChargingSession;
import com.charging.station.util.SimClock;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.LocalTime;

/**
 * 仿真参数配置：启动时把 charging.time-acceleration 注入 ChargingSession 的时间加速因子。
 * 验收（1:10 比例尺，30 真实秒=5 仿真分钟）时设为 10；本地演示可用更大值（默认 120）。
 *
 * charging.sim-start-time（可选，如 "06:00"）：启用仿真时钟，电价时段按虚拟时间轴判定，
 * 用于验收场景从 06:00 谷时开始的计费范例；不配置则按真实墙钟时段计费（历史行为）。
 */
@Configuration
public class SimulationConfig {

    @Value("${charging.time-acceleration:120}")
    private double timeAcceleration;

    @Value("${charging.sim-start-time:}")
    private String simStartTime;

    @PostConstruct
    public void init() {
        ChargingSession.TIME_ACCELERATION = timeAcceleration;
        if (simStartTime != null && !simStartTime.isBlank()) {
            SimClock.configure(LocalTime.parse(simStartTime.trim()));
        }
    }
}
