# ⚡ 智能充电桩调度计费系统

> 基于Spring Boot + MyBatis的充电桩智能调度与计费管理系统

## 快速开始

```bash
# 1. 首次：建库 + 初始化数据（结构与种子数据可复现）
mysql -u root charging_station < src/main/resources/schema.sql
mysql -u root charging_station < src/main/resources/data.sql

# 2. 启动后端（充电桩与调度由系统自动驱动，无需手动启动）
mvn spring-boot:run

# 3. 浏览器打开界面（直接双击文件或 open）
open admin.html     # 管理端：实时监控大屏
open client.html    # 用户端：可开多个窗口模拟多用户
open console.html   # 测试控制台：故障模拟 / 调度策略

# 4. 两次演示之间清空充电数据（保留充电桩配置）
./reset_db.sh

# 5. 关闭后端：运行 mvn 的终端按 Ctrl+C，或
pkill -f spring-boot:run
```

> **连本机**：前端默认连 `localhost:8080`，打开即用。
> **连远程/跨网**：在页面顶部「🌐 服务器地址」栏填服务器 `IP:端口`（如 `192.168.1.10:8080`）后点「连接」即可。

---

## 系统参数

| 参数 | 值 | 说明 |
|-----|-----|------|
| 快充功率 | 30度/小时 | 快充桩充电速度 |
| 慢充功率 | 10度/小时 | 慢充桩充电速度 |
| 充电桩配置 | 快充2个 + 慢充3个 | 共5个充电桩（可在 data.sql 调整）|
| 充电桩队列长度(M) | 3 | 每桩 1 充电位 + 2 后排队位（验收 M=3）|
| 等候区容量(N) | 10 | 等待分配的最大车辆数 |

---

## 自动调度与仿真时间

系统由后端调度器 `ChargingScheduler`（每秒一次）自动驱动整个生命周期，无需手动调接口：
**等候区 → 充电桩排队区 → 到队首自动开始充电 → 按仿真时间推进充电量 → 充满自动结束并生成账单**。

为便于演示，充电时间被加速：`ChargingSession.TIME_ACCELERATION = 120`（1 真实秒 ≈ 2 仿真分钟），
即充满一辆快充 30 度（30kW，需 1 仿真小时）约 30 真实秒完成；修改该常量即可调整快慢。

> 提交申请后车辆先在等候区停留约 1 秒（一个调度周期），其间可修改充电量/模式；一旦被调度进入桩排队区或开始充电即不可再改。

---

## 前端连接服务器（本地 / 远程）

三个页面（`client.html` / `admin.html` / `console.html`）顶部都有「🌐 服务器地址」配置栏：

- **默认**连本机 `http://localhost:8080`，本地演示直接打开即可。
- **跨网/远程**：在配置栏填服务器 `IP:端口`（或完整 `http://host:port`）后点「连接」。地址会存入浏览器 `localStorage` 并持久保存，REST 接口与 WebSocket(SockJS) 都按此地址连接。
- 依赖的 `sockjs` / `stomp` 已本地化到 `lib/` 目录（不走外网 CDN），**断外网也能用**，只要能连到后端服务器即可。

> 后端已开放跨域（REST `@CrossOrigin("*")` + WebSocket `setAllowedOriginPatterns("*")`），跨网无需改后端；只需保证服务器对外可达（防火墙/安全组放行 `8080`）。

---

## 技术栈

- **后端**: Java 17 + Spring Boot 3.2 + MyBatis
- **前端**: HTML5 + Tailwind CSS + JavaScript + WebSocket
- **数据库**: MySQL 8.0
- **测试**: JUnit 5 + Python

---

## 核心功能

### 用户端功能
- ✅ 提交充电申请（快充/慢充）
- ✅ 修改充电量
- ✅ 修改充电模式
- ✅ 查看排队状态（自动刷新）
- ✅ 取消充电

### 管理端功能
- ✅ 实时监控所有充电桩状态
- ✅ 查看充电中车辆和进度
- ✅ 模拟充电桩故障
- ✅ 选择故障调度策略（优先级/时间顺序）
- ✅ 重置数据库

### 核心算法
- ✅ **最短完成时间调度**：计算等待时间+充电时间，选择最优充电桩
- ✅ **分时计费**：峰时（10:00-15:00, 18:00-21:00）、平时、谷时
- ✅ **跨时段计费**：自动分段累计不同时段费用
- ✅ **故障调度**：优先级调度、时间顺序调度
- ✅ **扩展调度（选做）**：单次/全站批量分配，最小化一批车的总充电完成时长（SPT）

---

## 项目结构

```
charging-system-java/
├── src/
│   ├── main/
│   │   ├── java/com/charging/station/
│   │   │   ├── controller/         # 接口层
│   │   │   ├── service/            # 业务层（含 ChargingScheduler 自动调度）
│   │   │   ├── domain/             # 领域对象
│   │   │   ├── mapper/             # 数据访问层
│   │   │   ├── config/             # WebSocket / 仿真参数配置
│   │   │   ├── enums/              # 枚举（车辆状态/桩状态/模式）
│   │   │   └── dto/                # 数据传输对象
│   │   └── resources/
│   │       ├── mapper/             # MyBatis映射
│   │       ├── schema.sql          # 数据库结构
│   │       ├── data.sql            # 初始数据
│   │       └── application.yml     # 配置（含 charging.time-acceleration）
│   └── test/                       # JUnit 测试
├── lib/                            # 前端本地依赖 sockjs/stomp（离线可用）
├── admin.html                      # 管理端（实时监控大屏）
├── client.html                     # 用户端界面
├── console.html                    # 测试控制台（故障模拟/调度策略）
├── logic_test.py                   # 集成测试（真实断言）
├── accept_test.py                  # 验收用例驱动
├── reset_db.sh                     # 清空充电数据脚本
├── pom.xml                         # Maven配置
└── README.md                       # 本文档
```

---

## 使用说明

### 1. 环境要求

- Java 17+
- Maven 3.6+
- MySQL 8.0+
- 现代浏览器（Chrome/Firefox/Safari）

### 2. 数据库配置

确保MySQL已创建数据库：
```sql
CREATE DATABASE charging_station CHARACTER SET utf8mb4;
```

配置文件：`src/main/resources/application.yml`
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/charging_station
    username: root
    password: (your-password)
```

### 3. 启动步骤

```bash
# 首次：建库 + 初始化（之后无需每次重建）
mysql -u root charging_station < src/main/resources/schema.sql
mysql -u root charging_station < src/main/resources/data.sql

# 启动后端
mvn spring-boot:run

# 打开界面（默认连 localhost:8080；远程在页面顶部配置服务器地址）
open admin.html      # 管理端
open client.html     # 用户端（可多开）
open console.html    # 测试控制台

# 两次演示之间清空充电数据
./reset_db.sh
```

### 4. 测试流程

**用户端操作**（client.html）：
1. （远程时）在顶部「🌐 服务器地址」填服务器 `IP:端口` 并连接
2. 输入车牌号（例如：京A12345）
3. 选择充电模式（快充/慢充），输入电池容量与充电量
4. 点击"提交充电申请" → 进入等候区（约 1 秒窗口内可改充电量/模式）
5. 系统自动调度：等候区 → 桩排队 → 自动开始充电 → 充满自动结束并出账单（状态横幅每 2 秒刷新）

**管理端监控**（admin.html / console.html）：
- 实时显示各充电桩状态、充电中车辆与进度/费用
- 统计充电中 / 等待数量
- 模拟充电桩故障并选择调度策略（优先级 / 时间顺序）

---

## 核心API接口

### 充电申请
```bash
POST /api/charging/request
Content-Type: application/json

{
  "carId": "京A12345",
  "batteryCapacity": 60,
  "requestMode": "F",  // F=快充, T=慢充
  "requestAmount": 30
}
```

### 查询车辆状态
```bash
GET /api/charging/car/{carId}
```

### 查看充电桩状态
```bash
GET /api/monitor/piles
```

### 故障调度
```bash
# 优先级调度
POST /api/dispatch/fault/priority?pileId=P_F1

# 时间顺序调度
POST /api/dispatch/fault/timeorder?pileId=P_F1

# 恢复充电桩
POST /api/dispatch/recover?pileId=P_F1

# 扩展调度（2.8 选做）：最小化一批车的总充电完成时长
POST /api/dispatch/batch/single?mode=FAST&emptySlots=2
POST /api/dispatch/batch/full
```

---

## 故障排查

### 提交后停在"排队中 / 已分配充电桩"不开始充电
- 充电桩需为可调度（重灌 `data.sql` 后默认 IDLE + 可调度）；
- 调度器每秒推进，正常会自动「排队 → 叫号 → 充电 → 出账单」；
- 若仍不动，`./reset_db.sh` 清数据后重试。

### 前端连不上后端 / 大屏空白
- 顶部「🌐 服务器地址」是否填对（本机 `localhost:8080`，远程填对方 `IP:端口`）；
- 后端是否在运行：`curl http://<服务器地址>/api/monitor/piles`；
- 跨网时确认服务器 `8080` 端口已放行、对外可达。

### 端口被占用
- `lsof -i :8080` 查占用；`pkill -f spring-boot:run` 关闭旧实例。

---

## 测试

### 单元测试（JUnit，13 项，全绿）
```bash
mvn test   # 覆盖提交/修改/查询/启停桩/计费/故障调度等核心操作契约
```
> 单元测试通过 `charging.scheduler.enabled=false` 关闭自动调度器，以保证事务隔离与确定性。

### 集成测试（真实断言，需后端已启动）
```bash
mvn spring-boot:run        # 终端 A：启动后端
python3 logic_test.py      # 终端 B：需 mysql 在 PATH。验证自动充电/出账单/按桩型分配/拒改/超容量/故障
```

### 数据库可复现重建（换机器或重置结构时）
```bash
mysql -u root charging_station < src/main/resources/schema.sql
mysql -u root charging_station < src/main/resources/data.sql
```

---

## 验收（作业验收用例）

验收用例为 **1:10 比例尺**：每 30 真实秒输入一条事件 = 5 仿真分钟。运行前把仿真加速设为 10：

```bash
# 方式一：改 application.yml 的 charging.time-acceleration: 10
# 方式二：启动时覆盖
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dcharging.time-acceleration=10"
```

- **参数**：快充 30 / 慢充 10 度·h⁻¹；峰 1.0 / 平 0.7 / 谷 0.4；服务费 0.8；快充 2 + 慢充 3；每桩队列 **M=3**（1 充电位 + 2 排队位）；等候区 **N=10**。
- **事件**：A 申请、C 变更（模式/电量）、O 结束、B 故障(0)/恢复(1)（默认优先级调度）。
- **调度**：对应模式下"完成时间（等待时长 + 自身充电时长）最短"。
- `python3 accept_test.py` 回放验收事件序列并校验早期范例（V1→慢充1、V2→慢充2、6 辆快充占满后第 7 辆进等候区、(V1,0.83,1.00) 实时电量/费用）。

---

## 调度算法说明

### 最短完成时间调度

系统会为每个等待中的车辆计算所有可用充电桩的完成时间：

```
完成时间 = 等待时间 + 自己充电时间

等待时间 = 该充电桩队列中所有车辆的充电时间之和
自己充电时间 = 请求充电量 / 充电桩功率
```

选择完成时间最短的充电桩进行分配。

### 分时计费

| 时段 | 时间 | 电价（元/度）|
|------|------|-------------|
| 峰时 | 10:00-15:00, 18:00-21:00 | 1.0 |
| 平时 | 07:00-10:00, 15:00-18:00, 21:00-23:00 | 0.7 |
| 谷时 | 23:00-次日07:00 | 0.4 |

服务费：0.8元/度

---

## 注意事项

1. 两次演示之间用 `./reset_db.sh` 清空充电数据（保留桩配置）；换机器/重置结构用 `schema.sql + data.sql` 重建。
2. 充电生命周期由调度器自动驱动，**无需手动开始/结束充电**。
3. 用户端每 2 秒刷新；管理端 / 控制台通过 WebSocket 实时更新。
4. 提交后约 1 秒的等候区窗口内可改充电量/模式，之后不可改。
5. 车牌号自动转大写；充电量不能超过电池容量。
6. 仿真加速因子 `charging.time-acceleration`：本地演示默认 120；验收按 1:10 设为 10。

---

## License

MIT © 2026

---

## 常见问题

**Q: reset_db.sh 和 schema.sql/data.sql 有什么区别？**  
A: `reset_db.sh` 只清空充电业务数据（请求/会话/账单）并复位充电桩，保留表结构与桩配置，适合两次演示之间；`schema.sql + data.sql` 从零重建表结构与种子数据，适合换机器或结构变更。

**Q: 如何连接另一台机器上的后端？**  
A: 在任意前端页面顶部「🌐 服务器地址」栏填该机 `IP:端口` 后点「连接」。依赖库已本地化（`lib/`）断外网也能用；后端已开放跨域，只需保证服务器 8080 对外可达。

**Q: 如何模拟多个用户？**  
A: 打开多个client.html窗口，输入不同车牌号即可。

**Q: 如何查看详细日志？**  
A: 后端日志会输出到控制台，或查看 `target/logs/` 目录。

**Q: 如何修改充电桩数量？**  
A: 修改 `src/main/resources/data.sql` 中的充电桩初始化数据。
