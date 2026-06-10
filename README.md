# 智能充电桩调度计费系统

基于 Spring Boot + MyBatis 的充电桩管理系统，支持快充/慢充调度、故障处理、费用计算。

## 项目结构

```
charging-system-java/
├── src/main/java/com/charging/station/
│   ├── ChargingStationApplication.java    # 启动类
│   ├── controller/                        # REST API
│   │   ├── ChargingController.java        # 充电请求
│   │   ├── DispatchController.java        # 队列调度
│   │   ├── PileController.java            # 充电桩管理
│   │   ├── BillingController.java         # 费用计算
│   │   └── MonitorController.java         # 状态监控
│   ├── service/                           # 业务逻辑
│   │   ├── ChargingApplicationService.java # 充电申请
│   │   ├── DispatchService.java           # 调度策略
│   │   ├── PileManagementService.java     # 充电桩管理
│   │   ├── BillingService.java            # 费用计算
│   │   └── MonitoringService.java         # 状态监控
│   ├── domain/                            # 实体类 (8个)
│   ├── mapper/                            # MyBatis Mapper (5个)
│   ├── dto/                               # 数据传输对象
│   ├── enums/                             # 枚举类型
│   └── util/                              # 工具类
├── src/main/resources/
│   ├── application.yml                    # 配置文件
│   ├── schema.sql                         # 数据库表结构
│   ├── data.sql                           # 初始化数据
│   └── mapper/*.xml                       # MyBatis映射文件
├── console.html                           # 管理控制台
├── logic_test.py                          # 自动化测试
├── test_cases.json                        # 测试用例
├── reset_db.sh                            # 数据库重置
└── pom.xml                                # Maven依赖
```

## 快速开始

### 1. 启动后端

```bash
# 启动 Spring Boot (端口 8080)
mvn spring-boot:run
```

### 2. 打开控制台

```bash
# 在浏览器中打开
open console.html
```

### 3. 运行测试

```bash
# 执行 42 个测试用例
python3 logic_test.py
```

## 功能说明

### 控制台功能
- **时间流速调整**：支持 1:1 / 1:10 / 1:60 / 即时模式
- **故障模拟**：一键设置/恢复充电桩故障
- **数据库重置**：清空充电请求和队列数据
- **自动化测试**：运行全部测试用例并查看日志

### 核心业务
- **充电模式**：快充（30kW）/ 慢充（10kW）
- **调度策略**：优先级队列（故障车辆优先）
- **费用计算**：峰谷平时段计费
- **故障处理**：自动切换可用充电桩

## 数据库配置

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/charging_station
    username: root
    password: Lty20041011
```

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/charging/request` | POST | 提交充电请求 |
| `/api/dispatch/assign` | POST | 分配充电桩 |
| `/api/pile/fault` | POST | 设置故障状态 |
| `/api/billing/calculate` | GET | 计算费用 |
| `/api/monitor/status` | GET | 查询系统状态 |

## 技术栈

- **后端**: Spring Boot 2.7.18, MyBatis 2.3.2
- **数据库**: MySQL 8.0
- **前端**: 原生 HTML/CSS/JavaScript
- **测试**: Python 3 + requests

## 时间流速说明

系统支持时间加速模拟：
- **1:1**：实时运行
- **1:10**：30分钟模拟5小时（验收要求）
- **1:60**：快速测试
- **即时**：跳过等待（极速验证）

## 许可证

MIT
