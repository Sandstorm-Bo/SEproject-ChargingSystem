#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""故障调度策略 a/b 对比实验。

把 console.html「验收用例·一键演示」的 42 条官方事件各回放一次：
  - 第一次：所有故障(F)走 7a 优先级调度  /dispatch/fault/priority
  - 第二次：所有故障(F)走 7b 时间顺序调度 /dispatch/fault/timeorder
恢复(R)两次都走 /dispatch/recover（即 7c，固定行为，非可选项）。

除故障端点外，其余调用与前端完全一致（同一套 REST）。两次用相同倍速，
故差异只来自故障调度策略本身。跑完直接查 MySQL 出对比。

前置：后端已启动；mysql 在 PATH；charging_station 库存在。
用法：python3 strategy_compare.py            # 默认 120x
     python3 strategy_compare.py 60         # 指定倍速
"""
import requests, time, sys, subprocess, shutil

API = "http://localhost:8080/api"
MYSQL = shutil.which("mysql") or "/opt/homebrew/opt/mysql/bin/mysql"
SPEED = float(sys.argv[1]) if len(sys.argv) > 1 else 120.0   # 1 真实秒 = SPEED 仿真秒，两次一致
SETTLE_CAP_SEC = 240          # 注入完后等待剩余车辆充满的真实秒数上限（all_terminal 提前退出）

# 官方 42 条事件，逐字取自 console.html ACCEPT_EVENTS
EVENTS = [
    {"t":"06:00","op":"A","car":"V1","mode":"TRICKLE","amt":40},
    {"t":"06:05","op":"A","car":"V2","mode":"TRICKLE","amt":30},
    {"t":"06:10","op":"A","car":"V3","mode":"FAST","amt":100},
    {"t":"06:15","op":"A","car":"V4","mode":"FAST","amt":120},
    {"t":"06:20","op":"O","car":"V2"},
    {"t":"06:25","op":"A","car":"V5","mode":"TRICKLE","amt":20},
    {"t":"06:30","op":"A","car":"V6","mode":"TRICKLE","amt":20},
    {"t":"06:35","op":"A","car":"V7","mode":"FAST","amt":110},
    {"t":"06:40","op":"A","car":"V8","mode":"TRICKLE","amt":20},
    {"t":"06:45","op":"A","car":"V9","mode":"FAST","amt":105},
    {"t":"06:50","op":"A","car":"V10","mode":"TRICKLE","amt":10},
    {"t":"06:55","op":"A","car":"V11","mode":"FAST","amt":110},
    {"t":"07:00","op":"A","car":"V12","mode":"FAST","amt":90},
    {"t":"07:05","op":"A","car":"V13","mode":"FAST","amt":110},
    {"t":"07:10","op":"A","car":"V14","mode":"FAST","amt":95},
    {"t":"07:15","op":"A","car":"V15","mode":"TRICKLE","amt":10},
    {"t":"07:20","op":"A","car":"V16","mode":"FAST","amt":60},
    {"t":"07:25","op":"A","car":"V17","mode":"TRICKLE","amt":10},
    {"t":"07:30","op":"A","car":"V18","mode":"TRICKLE","amt":7.5},
    {"t":"07:35","op":"A","car":"V19","mode":"FAST","amt":75},
    {"t":"07:40","op":"A","car":"V20","mode":"FAST","amt":95},
    {"t":"07:45","op":"A","car":"V21","mode":"FAST","amt":95},
    {"t":"07:50","op":"A","car":"V22","mode":"FAST","amt":70},
    {"t":"07:55","op":"A","car":"V23","mode":"FAST","amt":80},
    {"t":"08:00","op":"A","car":"V24","mode":"TRICKLE","amt":5},
    {"t":"08:20","op":"A","car":"V25","mode":"TRICKLE","amt":15},
    {"t":"08:25","op":"F","pile":"P_T1"},
    {"t":"08:30","op":"A","car":"V26","mode":"TRICKLE","amt":20},
    {"t":"08:35","op":"A","car":"V27","mode":"TRICKLE","amt":25},
    {"t":"08:50","op":"F","pile":"P_F1"},
    {"t":"09:00","op":"A","car":"V28","mode":"FAST","amt":30},
    {"t":"09:10","op":"O","car":"V1"},
    {"t":"09:15","op":"R","pile":"P_T1"},
    {"t":"09:20","op":"O","car":"V27"},
    {"t":"09:25","op":"C","car":"V21","amt":35},
    {"t":"09:30","op":"O","car":"V19"},
    {"t":"09:35","op":"O","car":"V28"},
    {"t":"09:40","op":"C","car":"V23","amt":40},
    {"t":"09:50","op":"A","car":"V29","mode":"TRICKLE","amt":30},
    {"t":"09:55","op":"C","car":"V14","amt":30},
    {"t":"10:00","op":"A","car":"V30","mode":"TRICKLE","amt":10},
    {"t":"10:50","op":"R","pile":"P_F1"},
    {"t":"11:35","op":"END"},
]

# ---------- HTTP（精确复刻前端 ION.post / ION.postJson）----------
def post(path, **params):       # @RequestParam -> query string，同 ION.post
    return requests.post(f"{API}{path}", params=params, timeout=10).json()
def postj(path, body):          # @RequestBody  -> JSON，同 ION.postJson
    return requests.post(f"{API}{path}", json=body, timeout=10).json()
def getj(path):
    return requests.get(f"{API}{path}", timeout=10).json()
def car(cid):
    return getj(f"/charging/car/{cid}").get("data") or {}

def hhmm(s):
    a, b = s.split(":"); return int(a) * 60 + int(b)
def sim_now_min():
    try:
        v = (getj("/monitor/sim-clock").get("data") or "")   # "SIM 06:12:30"
        import re; m = re.search(r"(\d{1,2}):(\d{2})", v)
        return int(m.group(1)) * 60 + int(m.group(2)) if m else None
    except Exception:
        return None

# ---------- MySQL ----------
def q(sql):
    r = subprocess.run([MYSQL, "-u", "root", "charging_station", "-B", "-N", "-e", sql],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr)
    return [line.split("\t") for line in r.stdout.splitlines() if line]

# ---------- 事件 -> 后端调用（除 F 外与前端一致）----------
def fire(ev, strategy, log):
    op = ev["op"]
    if op == "A":
        r = postj("/charging/request", {"carId": ev["car"], "requestMode": ev["mode"],
                  "requestAmount": ev["amt"], "batteryCapacity": max(ev["amt"], 200)})
        log(f'  {ev["t"]} 申请 {ev["car"]} {ev["mode"]} {ev["amt"]}度  -> {"ok" if r.get("success") else r.get("message")}')
    elif op == "O":
        c = car(ev["car"]); st = c.get("requestStatus"); pile = c.get("pileId")
        if st == "CHARGING":
            post("/charging/end", carId=ev["car"], pileId=pile)
            log(f'  {ev["t"]} {ev["car"]} 充电中离开->结束出账({pile})')
        elif st in ("WAITING", "QUEUED_AT_PILE", "CALLED"):
            postj("/charging/cancel", {"carId": ev["car"]})
            log(f'  {ev["t"]} {ev["car"]} 取消({st})')
        else:
            log(f'  {ev["t"]} {ev["car"]} 取消空操作(当前 {st})')
    elif op == "C":
        r = postj("/charging/amount", {"carId": ev["car"], "newAmount": ev["amt"]})
        log(f'  {ev["t"]} 改量 {ev["car"]}->{ev["amt"]}度  -> {"ok" if r.get("success") else r.get("message")}')
    elif op == "F":
        r = post(f"/dispatch/fault/{strategy}", pileId=ev["pile"])           # ★ 策略开关
        log(f'  {ev["t"]} ★故障 {ev["pile"]} · {strategy}  -> {r.get("message") or r.get("data")}')
    elif op == "R":
        r = post("/dispatch/recover", pileId=ev["pile"])
        log(f'  {ev["t"]} 恢复 {ev["pile"]}  -> {r.get("message") or r.get("data")}')
    elif op == "END":
        log(f'  {ev["t"]} END（输入注入完毕，剩余车辆继续充电）')

def all_terminal():
    rows = q("SELECT COUNT(*) FROM charging_request WHERE request_status IN "
             "('WAITING','QUEUED_AT_PILE','CALLED','CHARGING');")
    return int(rows[0][0]) == 0

def snapshot():
    rev = q("SELECT COUNT(*),IFNULL(SUM(total_charge_fee),0),IFNULL(SUM(total_service_fee),0),"
            "IFNULL(SUM(total_fee),0) FROM bill;")[0]
    per_car = q("SELECT car_id,ROUND(SUM(total_fee),2) FROM bill GROUP BY car_id ORDER BY car_id;")
    details = q("SELECT b.car_id,d.pile_id,ROUND(d.charge_amount,2),ROUND(d.subtotal_fee,2) "
                "FROM detailed_list d JOIN bill b ON d.bill_id=b.bill_id ORDER BY b.car_id,d.start_time;")
    disp = q("SELECT dispatch_type,detail,car_count FROM dispatch_record "
             "WHERE dispatch_type LIKE 'FAULT%' OR dispatch_type='RECOVER' ORDER BY created_at;")
    finals = q("SELECT car_id,request_status,IFNULL(pile_id,'-') FROM charging_request ORDER BY car_id;")
    return {
        "n_bills": int(rev[0]), "charge": float(rev[1]), "service": float(rev[2]), "total": float(rev[3]),
        "per_car": {c: float(f) for c, f in per_car},
        "details": details, "disp": disp,
        "finals": {c: (s, p) for c, s, p in finals},
    }

def run_once(strategy, verbose=True):
    def log(m):
        if verbose: print(m, flush=True)
    print(f"\n{'='*64}\n▶ 回放 42 事件 · 故障策略 = {strategy}  · 倍速 {SPEED:g}x\n{'='*64}", flush=True)
    # 重建官方拓扑(快2慢3/N10/M2)+清空数据 -> 自动调度 -> 倍速 -> 锚定06:00（顺序同前端）
    post("/monitor/station-config", fastNum=2, trickleNum=3, waitingSize=10, queueLen=2)
    # station-config 只清 充电业务数据(详单/账单/会话/请求)，不清审计表；
    # 手动清 dispatch_record / fault_record，保证本轮调度指纹不被历史跑次污染
    q("DELETE FROM dispatch_record; DELETE FROM fault_record;")
    post("/dispatch/auto", enabled="true")
    post("/monitor/sim-speed", value=SPEED)
    post("/monitor/sim-clock", start="06:00")
    piles = {p["pileId"] for p in (getj("/monitor/piles").get("data") or [])}
    assert {"P_F1", "P_T1"} <= piles, f"拓扑缺少故障目标桩: {piles}"

    i = 0
    while i < len(EVENTS):
        now = sim_now_min()
        if now is None:
            time.sleep(0.25); continue
        while i < len(EVENTS) and hhmm(EVENTS[i]["t"]) <= now:
            fire(EVENTS[i], strategy, log); i += 1
        time.sleep(0.25)

    # ① 输入结束(~11:35)快照：只统计此刻已完成出账的车 —— 对应"验收答案表"的口径
    snap_inputs = snapshot()
    print(f"  [快照·输入结束] 账单 {snap_inputs['n_bills']} 张, 营收 {snap_inputs['total']:.2f}", flush=True)

    # 注入完毕，等剩余车辆充满（封顶，避免个别大电量车拖太久）
    print("  …等待剩余车辆充满…", flush=True)
    t_end = time.time() + SETTLE_CAP_SEC
    while time.time() < t_end:
        if all_terminal():
            print("  剩余车辆全部充满/离开", flush=True); break
        time.sleep(2)
    else:
        print(f"  ⚠ 等待封顶 {SETTLE_CAP_SEC}s 到，仍有车在充（两策略同条件，仍可比）", flush=True)
    # ② 全部充满快照：所有车把请求量充满后的总营收（两策略应≈相等，因总电量相同）
    snap_settled = snapshot()
    return {"inputs": snap_inputs, "settled": snap_settled}

# ---------- 对比输出 ----------
def fmt(x): return f"{x:8.2f}"
def compare(a, b):
    print(f"\n\n{'#'*64}\n# 实验结果对比：a 优先级调度  vs  b 时间顺序调度\n"
          f"# 倍速 {SPEED:g}x · 官方拓扑(快2慢3/N10/M2) · 06:00 锚定 · 恢复均走 7c\n{'#'*64}")

    print("\n【1】总营收")
    print(f"  {'':10}{'a 优先级':>12}{'b 时间顺序':>14}{'差异(b-a)':>12}")
    for k, lab in [("n_bills","账单数"),("charge","充电费"),("service","服务费"),("total","总营收")]:
        d = b[k] - a[k]
        av = f"{a[k]:>12}" if k=="n_bills" else f"{a[k]:>12.2f}"
        bv = f"{b[k]:>14}" if k=="n_bills" else f"{b[k]:>14.2f}"
        dv = f"{d:>+12}" if k=="n_bills" else f"{d:>+12.2f}"
        print(f"  {lab:10}{av}{bv}{dv}")

    print("\n【2】故障/恢复时刻的调度决策 (dispatch_record，策略指纹)")
    for tag, snap in [("a 优先级", a), ("b 时间顺序", b)]:
        print(f"  ── {tag} ──")
        for dt, detail, cnt in snap["disp"]:
            print(f"     {dt:18} {detail}  (车数={cnt})")

    print("\n【3】逐车最终账单对比（★=两策略不同）")
    cars = sorted(set(a["per_car"]) | set(b["per_car"]),
                  key=lambda c: (len(c), c))
    print(f"  {'车':5}{'a 费用':>10}{'b 费用':>10}   a:充电桩明细 | b:充电桩明细")
    n_diff = 0
    for c in cars:
        fa = a["per_car"].get(c); fb = b["per_car"].get(c)
        da = " ".join(f"{p}:{amt}" for cc,p,amt,_ in a["details"] if cc==c)
        db = " ".join(f"{p}:{amt}" for cc,p,amt,_ in b["details"] if cc==c)
        diff = (fa != fb) or (da != db)
        if diff: n_diff += 1
        mark = "★" if diff else " "
        sa = f"{fa:>10.2f}" if fa is not None else f"{'-':>10}"
        sb = f"{fb:>10.2f}" if fb is not None else f"{'-':>10}"
        print(f" {mark}{c:5}{sa}{sb}   {da or '-'} | {db or '-'}")
    print(f"\n  共 {len(cars)} 辆出账，其中 {n_diff} 辆在两策略下结果不同。")

    print("\n【4】最终车辆状态差异（充电桩归属/状态不同的车）")
    allc = sorted(set(a["finals"]) | set(b["finals"]), key=lambda c:(len(c),c))
    any_diff = False
    for c in allc:
        sa = a["finals"].get(c, ("无","-")); sb = b["finals"].get(c, ("无","-"))
        if sa != sb:
            any_diff = True
            print(f"   {c:5} a={sa[0]}@{sa[1]:6}  b={sb[0]}@{sb[1]}")
    if not any_diff:
        print("   （最终状态一致）")

def main():
    try:
        getj("/monitor/piles")
    except Exception:
        print("后端未启动，请先 mvn spring-boot:run"); sys.exit(1)
    a = run_once("priority")
    b = run_once("timeorder")
    compare(a, b)
    # 存原始快照便于复查
    import json
    open("/tmp/strat_a.json","w").write(json.dumps(a, ensure_ascii=False, indent=2))
    open("/tmp/strat_b.json","w").write(json.dumps(b, ensure_ascii=False, indent=2))
    print("\n原始快照: /tmp/strat_a.json  /tmp/strat_b.json")

if __name__ == "__main__":
    main()
