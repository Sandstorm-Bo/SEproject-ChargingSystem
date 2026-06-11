# ⚡ 智能充电桩调度计费系统

Spring Boot 3.2 + MyBatis + MySQL 8 后端；三个零构建静态页面（原生 JS + WebSocket）前端。

## 快速开始

```bash
# 1. 首次建库 + 初始化（换机器/重置结构时重跑）
mysql -u root charging_station < src/main/resources/schema.sql
mysql -u root charging_station < src/main/resources/data.sql

# 2. 启动后端（充电生命周期由调度器自动驱动，无需手动开始/结束）
mvn spring-boot:run          # Ctrl+C 或 pkill -f spring-boot:run 关闭

# 3. 浏览器打开（双击或 open）
open client.html             # 用户端（可多开模拟多用户）
open admin.html              # 管理端监控大屏
open console.html            # 运维控制台（故障/调度/定时故障/倍速/重置）

# 4. 两次演示之间清场（保留桩配置）
./reset_db.sh
```

数据库连接在 `src/main/resources/application.yml`（默认 `root` / 空密码 / `localhost:3306`）。环境：Java 17+、Maven 3.6+、MySQL 8+。

## 连接服务器（本地 / 跨网）

页面顶部「🌐 服务器地址」栏运行时填写，**代码不硬编码 IP**（默认值 `localhost:8080` 仅兜底），地址存入 `localStorage` 持久保存，REST 与 WebSocket 都按此连接。

- **懒人启动**：首次打开或连接失败时地址栏自动弹出，填 `服务器IP:8080`（如 `192.168.1.10:8080`）点「连接」即可，下次免填。
- **用户端分发**：拷到其它机器时需**整包**——`client.html` + `app.css` + `app.js` + `lib/`（缺一不可）。用户端只需浏览器，不需 Java/MySQL。
- **服务器侧**：后端默认监听 `0.0.0.0:8080` 且已开放跨域（REST `@CrossOrigin("*")` + WebSocket `setAllowedOriginPatterns("*")`）。查 IP `ipconfig getifaddr en0`；放行防火墙 8080 入站；同一局域网即可，跨子网/公网需端口转发或 frp/tailscale。
- 依赖 `sockjs`/`stomp` 已本地化到 `lib/`，断外网可用。

## 运维控制台（console.html）

管理/演示/验收总控台，WebSocket 实时刷新，即点即生效。

| 面板 | 操作 | 作用 |
|------|------|------|
| 充电桩监控 | 点桩卡片 | 空闲/充电中 → 按当前策略注入故障；故障中 → 恢复 |
| 系统日志 | 自动滚动 / 清空 | 留痕每次故障·调度·重置，含批量调度逐车明细 |
| ① 故障调度策略 | 选策略 + 点 `F1/F2/T1/T2/T3 故障` | 优先级调度（故障车优先重排）或时间顺序调度（全体重排）；可一键恢复全部 |
| ② 定时故障 | 选桩+策略，填故障倒计时/持续秒数 → 安排 | 倒计时自动注入、到点自动恢复，验收按脚本定时触发故障时用 |
| ③ 扩展调度 2.8 | 选模式+空位 → 单次 / 全站批量 | 选做：SPT 排程最小化一批车总完成时长，结果打到日志 |
| ④ 仿真时间流速 | 点 `10×~240×` | 实时调倍率；**验收 1:10 设 `10×`**，演示用 `120×` |
| ⑤ 实时统计 | 只读 | 充电中 / 等候区·桩前 / 故障桩 / 累计电量·营收 |
| ⑥ 数据管理 | 重置（二次确认） | 清空请求·会话·账单并复位桩为空闲，保留桩与车辆配置（等价 `reset_db.sh`）|

**典型验收流程**：④ 设 `10×` → ⑥ 重置清场 → 在 `client.html` 按脚本提交申请 → 到点用 ①/② 注入故障看重排 → ③ 验证批量 SPT → ⑥ 重置进入下一轮。

## 调度与计费

- **充电桩**：快充 2 个（30 度/h）+ 慢充 3 个（10 度/h）；每桩队列 **M=3**（1 充电位 + 2 排队）；等候区 **N=10**。均可在 `data.sql` 调整。
- **最短完成时间调度**：`完成时间 = 桩队列已有车充电时间之和 + 自己充电时间（请求量/功率）`，选最小者分配。
- **分时计费**（元/度）：峰 1.0（10–15、18–21）/ 平 0.7（07–10、15–18、21–23）/ 谷 0.4（23–07）；服务费 0.8；跨时段自动分段累计。
- **生命周期**：调度器每秒推进 `等候区 → 桩排队 → 队首自动充电 → 充满出账单`。提交后约 1 秒等候窗口内可改电量/模式，之后锁定。

## 验收（1:10 比例尺）

每 30 真实秒 = 5 仿真分钟。先把加速设为 10：

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dcharging.time-acceleration=10"
# 或改 application.yml: charging.time-acceleration: 10
python3 accept_test.py     # 回放验收事件并校验早期范例（V1→慢充1、(V1,0.83,1.00) 等）
```

`test_cases.json` 为验收用例完整对照表（人工参考）。

## 测试

```bash
mvn test                   # JUnit 单元测试（关调度器保证确定性）
mvn spring-boot:run &      # 集成测试需后端已起 + mysql 在 PATH
python3 logic_test.py      # 验证自动充电/出账单/按桩型分配/拒改/超容/故障
```

## 核心 API

```
POST /api/charging/request           {carId,batteryCapacity,requestMode:F|T,requestAmount}
GET  /api/charging/car/{carId}        查车辆状态
GET  /api/monitor/piles               查桩状态
POST /api/dispatch/fault/{priority|timeorder}?pileId=P_F1   故障调度
POST /api/dispatch/recover?pileId=P_F1                       恢复
POST /api/dispatch/batch/{single?mode=FAST&emptySlots=2|full}  扩展调度 2.8
```

## 项目结构

```
src/main/java/com/charging/station/   controller / service（含 ChargingScheduler）/ domain / mapper / config / enums / dto
src/main/resources/                   schema.sql · data.sql · application.yml · mapper/*.xml
src/test/                             JUnit 测试
lib/                                  sockjs / stomp（本地化，离线可用）
app.css · app.js                      前端共享样式与逻辑（服务器配置/WebSocket/格式化）
client.html · admin.html · console.html   用户端 / 监控大屏 / 运维控制台
logic_test.py · accept_test.py · test_cases.json   集成测试 / 验收驱动 / 验收对照表
reset_db.sh                           清场脚本
```

## 排查

- **停在排队不充电**：确认 `data.sql` 已灌（桩默认 IDLE 可调度）；仍不动则 `./reset_db.sh` 重试。
- **连不上 / 大屏空白**：核对「🌐 服务器地址」；`curl http://<地址>/api/monitor/piles` 验后端；跨网确认 8080 放行可达。
- **端口占用**：`lsof -i :8080` 查，`pkill -f spring-boot:run` 关旧实例。
