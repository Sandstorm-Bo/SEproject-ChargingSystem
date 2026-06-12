#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""作业验收用例 - 早期范例校验驱动（对照 作业验收用例.xlsx 给出的范例行）。

需后端已启动；建议以 1:10 比例尺运行（charging.time-acceleration=10）：
  mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dcharging.time-acceleration=10"

校验点（均来自验收用例已给范例）：
  - 06:00 V1(慢充,40) -> 慢充1(P_T1)
  - V1 充约 5 仿真分钟 ≈ (0.83 度, 1.00 元)  [谷时 0.4 电价 + 0.8 服务费]
  - 06:05 V2(慢充,30) -> 慢充2(P_T2)
  - 2 个快充桩(每桩 M=3：1 充 + 2 排队)被 6 辆快充占满后，第 7、8 辆快充进等候区(WAITING)
"""
import requests
import time
import subprocess
import shutil
import sys

API = "http://localhost:8080/api"
MYSQL = shutil.which("mysql") or "/opt/homebrew/opt/mysql/bin/mysql"
ok = bad = 0


def check(name, cond, detail=""):
    global ok, bad
    if cond:
        ok += 1
        print(f"  ✅ {name}")
    else:
        bad += 1
        print(f"  ❌ {name}   {detail}")


def clean():
    subprocess.run([MYSQL, "-u", "root", "charging_station", "-e",
        "DELETE FROM detailed_list;DELETE FROM bill;DELETE FROM charging_session;DELETE FROM charging_request;"
        "UPDATE waiting_queue SET queue_length=0;UPDATE charging_queue SET current_length=0;"
        "UPDATE charging_pile SET status='IDLE',is_schedulable=1,fault_reason=NULL;"],
        check=True, capture_output=True)


def car(p):
    return requests.get(f"{API}/charging/car/{p}", timeout=5).json().get("data") or {}


def submit(p, m, a, b=200):
    return requests.post(f"{API}/charging/request",
        json={"carId": p, "requestMode": m, "requestAmount": a, "batteryCapacity": b}, timeout=5).json()


def wait_status(p, t, to=40):
    e = time.time() + to
    while time.time() < e:
        if car(p).get("requestStatus") == t:
            return True
        time.sleep(0.6)
    return False


def setup():
    clean()
    # 验收范例从 06:00（谷时）开始计费，锚定仿真时钟，避免依赖验收当天的真实时段
    requests.post(f"{API}/monitor/sim-clock?start=06:00", timeout=5)
    requests.post(f"{API}/pile/parameters", json={"policyId": "ACC", "peakPrice": 1.0,
        "flatPrice": 0.7, "valleyPrice": 0.4, "serviceFeePerKwh": 0.8}, timeout=5)
    for pid in ["P_F1", "P_F2", "P_T1", "P_T2", "P_T3"]:
        requests.post(f"{API}/pile/poweron?pileId={pid}", timeout=5)
        requests.post(f"{API}/pile/run?pileId={pid}", timeout=5)


def main():
    try:
        requests.get(f"{API}/monitor/piles", timeout=5)
    except Exception:
        print("后端未启动，请先 mvn spring-boot:run")
        sys.exit(1)

    print("初始化（重置库 + 启动 5 桩 + 配置计费）…")
    setup()

    print("\n[范例1] 06:00 V1(慢充,40) → 慢充1")
    submit("V1", "TRICKLE", 40)
    check("V1 进入充电", wait_status("V1", "CHARGING"))
    check("V1 分配到慢充桩 P_T1", car("V1").get("pileId") == "P_T1", f"pile={car('V1').get('pileId')}")

    print("\n[范例2] V1 充约 5 仿真分钟应 ≈ (0.83度, 1.00元)")
    snap = None
    e = time.time() + 40
    while time.time() < e:
        s = requests.get(f"{API}/charging/state/V1", timeout=5).json().get("data") or {}
        if (s.get("chargeAmount") or 0) >= 0.80:
            snap = s
            break
        time.sleep(0.8)
    if snap:
        amt = snap["chargeAmount"]
        fee = snap["totalFee"]
        check(f"已充电量 ≈ 0.83 度（实测 {round(amt,3)}）", 0.78 <= amt <= 0.92, f"amt={amt}")
        check(f"费用 = 电量×(0.4+0.8)（实测 {round(fee,3)} vs 期望 {round(amt*1.2,3)}）",
              abs(fee - amt * 1.2) < 0.06, f"fee={fee}")
    else:
        check("能取到 V1 实时充电快照", False)

    print("\n[范例3] 06:05 V2(慢充,30) → 慢充2")
    submit("V2", "TRICKLE", 30)
    wait_status("V2", "CHARGING")
    check("V2 分配到慢充桩 P_T2", car("V2").get("pileId") == "P_T2", f"pile={car('V2').get('pileId')}")

    print("\n[范例4] 6 辆快充占满 2 个快充桩(每桩 M=3) 后，第 7/8 辆进等候区")
    for c, a in [("V3", 100), ("V4", 120), ("V7", 110), ("V9", 105), ("V11", 110), ("V12", 90)]:
        submit(c, "FAST", a)
        time.sleep(2.5)
    submit("V13", "FAST", 110)
    time.sleep(2.0)
    check("V13（第7辆快充）在等候区 WAITING", car("V13").get("requestStatus") == "WAITING",
          f"status={car('V13').get('requestStatus')}")
    submit("V14", "FAST", 95)
    time.sleep(1.5)
    check("V14（第8辆快充）在等候区 WAITING", car("V14").get("requestStatus") == "WAITING",
          f"status={car('V14').get('requestStatus')}")
    fast_in = [c for c in ["V3", "V4", "V7", "V9", "V11", "V12"]
               if (car(c).get("pileId") or "").startswith("P_F")]
    check("6 辆快充全部进入快充桩", len(fast_in) == 6, f"in={fast_in}")

    print("\n" + "=" * 50)
    print(f"通过 {ok} / {ok + bad}")
    sys.exit(0 if bad == 0 else 1)


if __name__ == "__main__":
    main()
