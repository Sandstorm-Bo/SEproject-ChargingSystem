package com.charging.station.config;

import com.charging.station.domain.ChargingSession;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 仿真参数配置：启动时把 charging.time-acceleration 注入 ChargingSession 的时间加速因子。
 * 验收（1:10 比例尺，30 真实秒=5 仿真分钟）时设为 10；本地演示可用更大值（默认 120）。
 */
@Configuration
public class SimulationConfig {

    @Value("${charging.time-acceleration:120}")
    private double timeAcceleration;

    @PostConstruct
    public void init() {
        ChargingSession.TIME_ACCELERATION = timeAcceleration;
    }
}
