# ⚡ 智能充电桩调度计费系统

Spring Boot 3.2 + MyBatis + MySQL 8 后端；三个零构建静态页面（原生 JS + WebSocket）前端。

**角色与页面**（对应需求文档 b/c 两类客户端）：

| 页面 | 角色 | 登录 | 职责 |
|------|------|------|------|
| `client.html` 用户端 | **用户客户端** | 用户注册/登录 | 提交/修改充电请求、排队号与前车数、结束充电、历史详单 |
| `admin.html` 调度盘 | **管理员客户端 · 监控** | 管理员登录 | 全站实景监控、桩状态累计、等候车辆信息表、日/周/月报表 |
| `console.html` 运维控制台 | **管理员客户端 · 操控** | 管理员登录 | ⭐验收用例一键演示、启停桩、故障注入/恢复、手动指派、计费策略、倍速/时钟、参数重建、重置 |

调度盘与控制台是**同一个管理员客户端的两块屏**（监控看盘 + 操作下发），共用一套管理员账号（`sys_user.role=ADMIN`），未登录会弹出登录/注册浮层；用户账号无法进入。首次使用在浮层点「注册管理员」创建账号；登录态存 `localStorage`，**在调度盘 ↔ 控制台之间切换、乃至新开标签页都保持登录，无需重复输入账号密码**，点页面右下角「退出管理」才登出。两个管理页面顶部导航只在彼此间跳转，不含通往用户端的入口（管理员与用户客户端相互独立）。

## 快速开始

```bash
# 1. 首次建库 + 初始化（换机器/重置结构时重跑）
mysql -u root charging_station < src/main/resources/schema.sql
mysql -u root charging_station < src/main/resources/data.sql

# 2. 启动后端（充电生命周期由调度器自动驱动，无需手动开始/结束）
mvn spring-boot:run          # Ctrl+C 或 pkill -f spring-boot:run 关闭

# 3. 浏览器打开（双击或 open）
open client.html             # 用户客户端：注册/登录后使用
./open-clients.sh 3          # ⭐ 一键多开 3 个独立用户客户端（模拟多用户，见下方「多开」）
open admin.html              # 管理员·调度盘：监控/等候车辆/报表（需管理员登录）
open console.html            # 管理员·运维控制台：启停/故障/调度/策略/倍速/重置（需管理员登录）

# 4. 两次演示之间清场（保留桩配置与账号）
./reset_db.sh
```

数据库连接在 `src/main/resources/application.yml`（默认 `root` / 空密码 / `localhost:3306`）。环境：Java 17+、Maven 3.6+、MySQL 8+。

## 连接服务器（本地 / 跨网）

页面顶部「🌐 服务器地址」栏运行时填写，**代码不硬编码 IP**（默认值 `localhost:8080` 仅兜底），地址存入 `localStorage` 持久保存，REST 与 WebSocket 都按此连接。

- **懒人启动**：首次打开或连接失败时地址栏自动弹出，填 `服务器IP:8080`（如 `192.168.1.10:8080`）点「连接」即可，下次免填。
- **用户端分发**：拷到其它机器时需**整包**——`client.html` + `app.css` + `app.js` + `lib/`（缺一不可）。用户端只需浏览器，不需 Java/MySQL。
- **服务器侧**：后端默认监听 `0.0.0.0:8080` 且已开放跨域（REST `@CrossOrigin("*")` + WebSocket `setAllowedOriginPatterns("*")`）。查 IP `ipconfig getifaddr en0`；放行防火墙 8080 入站；同一局域网即可，跨子网/公网需端口转发或 frp/tailscale。
- 依赖 `sockjs`/`stomp` 已本地化到 `lib/`，断外网可用。

## 用户客户端（client.html）

- **注册/登录**：打开页面先注册或登录（存 `sessionStorage`，**每个浏览器标签页独立**——同一台电脑开多个标签即可模拟多个用户）。
- **充电**：提交/修改充电请求（模式+电量）、查看排队号与本模式前车数量、结束充电出账单。
- **历史详单**：登录后页面底部展示本人全部详单（详单编号、生成时间、桩号、电量、时长、启停时间、充电费/服务费/总费用），充电完成自动刷新。
- 已有旧库升级需补两条 DDL（新库跑 `schema.sql` 则不用）：`CREATE TABLE sys_user ...`（见 schema.sql，含 role 列）和 `ALTER TABLE vehicle ADD COLUMN user_id VARCHAR(50) NULL`。

### 多开：模拟多个用户同时充电

登录态存在 `sessionStorage` 里，**它本来就是每个标签页独立的**，所以多用户隔离没有问题。坑在于直接 `open client.html` 传的是同一个 `file://` 地址，浏览器发现已经开着就只激活原标签页，不会新建会话——所以点几次都是同一个用户。正确做法（任选其一）：

- **① 一键多开（推荐）**：
  ```bash
  ./open-clients.sh 3      # 默认 3 个；./open-clients.sh 5 开 5 个
  ```
  脚本给每个窗口分配独立的 Chrome `--user-data-dir`，会话完全隔离，分别登录不同账号即可。用完清理临时数据：`rm -rf /tmp/ion-client-*`。
- **② 手动新建标签页**：开一次后按 `Cmd+T` 新建空白标签，再把同一文件地址粘进去回车。⚠️ 必须是「新建空白标签再导航」，**不能用「复制标签页」**——复制会连 `sessionStorage` 一起拷过去，仍是同一个用户。
- **③ 普通窗口 + 无痕窗口**：`Cmd+Shift+N` 开无痕窗口，与普通窗口存储天然隔离，立刻得到 2 个独立会话。
- **④ 不同浏览器各开一个**：Chrome / Safari / Firefox 各一个，互不影响，最不易搞错。

## 管理员客户端 · 调度盘（admin.html）

实景监控大屏（WebSocket 每秒刷新），承担需求 c) 的"看"半边：

- **充电桩状态**：每桩实时状态（空闲/充电/故障/关闭）+ 系统启动后累计充电次数、充电总时长、充电总电量；充电位/排队位/等候区全景动画。
- **等候服务车辆表**：用户 ID、车牌、模式、电池总容量、请求充电量、排队时长（仿真时间轴）、所在位置。
- **运营报表**：日 / 周 / 月 × 充电桩，累计充电次数、时长、电量、充电费、服务费、总费用，10s 自动刷新。
- **实时仪表**：在线桩数、充电中、等候/桩前、累计充电量·营收（含进行中会话实时增长）、当前电价时段。

## 管理员客户端 · 运维控制台（console.html）

操控台，承担需求 c) 的"管"半边，即点即生效。

| 面板 | 操作 | 作用 |
|------|------|------|
| ⭐ 验收用例·一键演示 | 选倍速 → 「导入并开始演示」 | **一键完整流程**：自动重建官方拓扑(快2·慢3·N10·M2)+清空→锚定 06:00→开启自动调度→按**虚拟时刻**逐条注入全部 42 条验收事件（申请/取消/改量/故障/恢复），左侧监控与日志即完整流程；面板显示注入进度与下一条事件。**对账答案表用 `10×`，演示给人看用 `60×`** |
| 调度方式总控 | ▶ 开启 / ⏸ 暂停 自动叫号调度 | 暂停后车辆停留等候区，便于用 ②③④ 手动接管、演示并切换各调度方式 |
| 充电桩监控 | 桩卡片右侧「⏻ 关闭 / ▶ 启动」 | **启动/关闭充电桩**：关闭时在充车辆按已充电量出账单、排队车辆自动重排；启动后重新参与调度 |
| 充电桩监控 | 点桩卡片本体 | 空闲/充电中 → 按当前策略注入故障；故障中 → 恢复 |
| 系统日志 | 自动滚动 / 清空 | 留痕每次故障·调度·策略·重置，含批量调度逐车明细 |
| ① 故障调度策略 | 选策略 + 点 `F1/F2/T1/T2/T3 故障` | 优先级调度（故障车优先重排）或时间顺序调度（全体重排）；可一键恢复全部 |
| ② 定时故障 | 选桩+策略，填故障倒计时/持续秒数 → 安排 | 倒计时自动注入、到点自动恢复，验收按脚本定时触发故障时用 |
| ③ 扩展调度 2.8 | 选模式+空位 → 单次 / 全站批量 | 选做：SPT 排程最小化一批车总完成时长，结果打到日志 |
| ④ 指定充电桩 | 选等候区车辆 + 目标桩 → 指派 | 手动把等候区车辆指派到指定桩（YL-024；要求模式匹配、桩可用、队列有空位）|
| ⑤ 计费策略 | 改峰/平/谷电价、服务费 → 保存；选桩改功率 | 修改电费策略与桩功率（YL-028），新策略即时生效 |
| ⑥ 仿真时间流速 | 点 `10×~240×`；下方锚定**仿真起始时间**（如 06:00） | 实时调倍率；**验收 1:10 设 `10×` + 锚定 06:00**，演示用 `120×`；页面时钟/电价时段随仿真时间走 |
| 系统参数·验收可调 | 改快/慢桩数、等候区容量 N、队列长度 M → 应用重建 | 按官方"验收时可自由设置参数"运行时重建充电站拓扑并清空数据；⭐一键演示也调用它固定官方 2 快 3 慢 / N10 / M2 |
| ⑦ 实时统计 | 只读 | 充电中 / 等候区·桩前 / 故障桩 / 累计电量·营收（含进行中会话，实时增长） |
| ⑧ 数据管理 | 重置（二次确认） | 清空请求·会话·账单并复位桩为空闲，保留桩、车辆配置与账号（等价 `reset_db.sh`）|

**典型验收流程**（任选其一）：

- **一键演示（推荐）**：控制台「⭐ 验收用例·一键演示」选 `10×` → 点「导入并开始演示」。它自动重建官方拓扑、锚定 06:00、开启自动调度，并按虚拟时刻注入全部 42 条事件，左侧充电桩监控 + 系统日志即完整流程，无需手动逐条操作。
- **手动**：⑥ 设 `10×` + 锚定 06:00 → ⑧ 重置清场 → 在 `client.html` 按脚本提交申请 → 到点用 ①/② 注入故障看重排 → ③ 验证批量 SPT → ⑧ 重置进入下一轮。

> ⚠️ 倍速越高，调度 tick（每 1 真实秒）对应的仿真时间越粗，实时系统会偏离理想答案表（如某些时刻车辆已离开等候区、申请不再被拒）。**要与参考答案表逐格对账请用 `10×`**；这是真实系统与理想答案表的固有差异，非缺陷。

## 调度与计费

- **充电桩**：快充 2 个（30 度/h）+ 慢充 3 个（10 度/h）；每桩队列 **M=3**（1 充电位 + 2 排队）；等候区 **N=10**。可在 `data.sql` 改默认值，或在控制台「系统参数·验收可调」运行时重建。
- **最短完成时间调度**：`完成时间 = 桩队列各车完成充电时间之和 + 自己充电时间（请求量/功率）`，选最小者分配；其中**正在充电的车按剩余电量计时**，排队车按满请求量计时。
- **分时计费**（元/度）：峰 1.0（10–15、18–21）/ 平 0.7（07–10、15–18、21–23）/ 谷 0.4（23–07）；服务费 0.8；跨时段自动分段累计。
- **生命周期（事件驱动 · 零轮询）**：调度在 申请/结束/取消/故障/恢复/启停桩 等"状态变更"事件的**事务提交后**即时对账——有空位即叫号、队首即刻开充（实测 提交→开充 ≈ 50ms）。**"充满"不再轮询**：开始充电时按确定的充满时刻（剩余电量/功率÷倍速）安排一个**一次性定时器**精确触发结算并接力下一辆；改倍速/功率时按新值重排定时器，进程重启时扫描在充会话重建。各桩占满时车辆留在等候区，其间可改电量/模式。（注：仪表盘的 WebSocket 每秒推送是 UI 刷新，与调度无关。）

## 验收（1:10 比例尺）

每 30 真实秒 = 5 仿真分钟。先把加速设为 10：

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dcharging.time-acceleration=10"
# 或改 application.yml: charging.time-acceleration: 10
python3 accept_test.py     # 命令行回放并校验早期范例（V1→慢充1、(V1,0.83,1.00) 等）
```

- **浏览器完整回放**：开 `console.html` → 「⭐ 验收用例·一键演示」选 `10×` → 「导入并开始」，自动注入全部 42 条事件并在大屏可视化（见上「运维控制台」）。
- `accept_test.py` 为命令行早期范例校验；`test_cases.json` 为验收用例完整对照表（人工参考）。

## 测试

```bash
pkill -f spring-boot:run   # ⚠️ 先停后端：单测与运行中的服务共用 MySQL，
mvn test                   #    调度器会动测试数据导致假失败（JUnit 关调度器保证确定性）
mvn spring-boot:run &      # 集成测试需后端已起 + mysql 在 PATH
python3 logic_test.py      # 验证自动充电/出账单/按桩型分配/拒改/超容/故障
```

## 核心 API

```
POST /api/auth/register               {username,password[,role:ADMIN]}  注册（管理员带 role）
POST /api/auth/login                  {username,password}  登录（返回 userId+role）
POST /api/charging/request           {carId,batteryCapacity,requestMode:F|T,requestAmount[,userId]}
GET  /api/charging/car/{carId}        查车辆状态
POST /api/charging/amount             {carId,newAmount}  修改充电量（仅等候区）
POST /api/charging/cancel             {carId}            取消请求（等候/排队区）
POST /api/charging/end?carId=&pileId= 结束/提前结束充电并出账单（"充电中离开"走此端点，按已充量跨时段计费）
GET  /api/billing/details?userId=U-X  按用户查历史详单（或 ?carId=车牌）
GET  /api/report?period=day|week|month   日/周/月 × 桩 运营报表
GET  /api/monitor/piles               查桩状态
POST /api/pile/run?pileId=P_F1        启动充电桩（重新参与调度）
POST /api/pile/poweroff?pileId=P_F1   关闭充电桩（在充车辆结算出账单+剩余量重排）
POST /api/pile/parameters             {policyId,peakPrice,flatPrice,valleyPrice,serviceFeePerKwh} 计费策略
PUT  /api/pile/power?pileId=&powerKw= 修改桩功率
POST /api/dispatch/assign?carId=&pileId=   手动指派等候区车辆到指定桩（YL-024）
POST /api/monitor/sim-clock?start=06:00   锚定仿真时钟起点（控制台也有 UI）
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
app.css · app.js                      前端共享样式与逻辑（服务器配置/WebSocket/格式化/管理员登录门）
client.html · admin.html · console.html   用户客户端 / 管理员客户端·调度盘 / 管理员客户端·运维控制台
logic_test.py · accept_test.py · test_cases.json   集成测试 / 验收驱动 / 验收对照表
reset_db.sh                           清场脚本
open-clients.sh                       一键多开 N 个独立用户客户端窗口（模拟多用户）
```

## 排查

- **停在排队不充电**：确认 `data.sql` 已灌（桩默认 IDLE 可调度）；仍不动则 `./reset_db.sh` 重试。
- **调度盘/控制台一直弹登录**：需要 `role=ADMIN` 的账号——在浮层切到「注册管理员」新建，普通用户账号会被拒。
- **连不上 / 大屏空白**：核对「🌐 服务器地址」；`curl http://<地址>/api/monitor/piles` 验后端；跨网确认 8080 放行可达。
- **端口占用**：`lsof -i :8080` 查，`pkill -f spring-boot:run` 关旧实例。
