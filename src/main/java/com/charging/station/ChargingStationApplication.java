package com.charging.station;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智能充电桩调度计费系统 - 主启动类
 *
 * @author 软件工程大作业 G3 组
 */
@SpringBootApplication
@EnableScheduling
public class ChargingStationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChargingStationApplication.class, args);
        System.out.println("====================================");
        System.out.println("充电桩调度计费系统启动成功");
        System.out.println("接口文档: http://localhost:8080/api");
        System.out.println("WebSocket: ws://localhost:8080/api/ws");
        System.out.println("====================================");
    }
}
