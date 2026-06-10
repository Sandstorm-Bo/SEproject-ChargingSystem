#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
充电桩系统 - 集成测试（真实断言版）

验证「调度器开启下」的实时自动行为（这些是 JUnit 因关闭调度器而测不到的）：
  1. 提交后自动调度 -> 自动开始充电 -> 充满自动结束并生成账单，且服务费=电量×单价
  2. 慢充车被分配到慢充桩、快充车被分配到快充桩
  3. 正在充电的车修改充电量被拒绝（WAITING-only 规则仍生效）
  4. 充电量超过电池容量被拒绝
  5. 等候区（WAITING）内可修改充电模式，排队号前缀随之改变
  6. 充电桩前队列饱和时，新车停留在等候区且可修改充电量（WAITING 窗口）
  7. 充电中触发故障调度，故障桩状态置为 FAULT

前置：后端已启动（mvn spring-boot:run）。脚本会重置并初始化数据库。
"""
import requests
import time
import subprocess
import shutil
import sys

API = "http://localhost:8080/api"
MYSQL_BIN = shutil.which("mysql") or "/opt/homebrew/opt/mysql/bin/mysql"

passed = 0
failed = 0


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  ✅ {name}")
    else:
        failed += 1
        print(f"  ❌ {name}   {detail}")


def clean_db():
    """清空业务数据并把充电桩复位为 空闲+可调度（含 FAULT 复位），保证各测试相互隔离"""
    sql = (
        "DELETE FROM detailed_list; DELETE FROM bill; DELETE FROM charging_session; "
        "DELETE FROM charging_request; "
        "UPDATE waiting_queue SET queue_length=0; "
        "UPDATE charging_queue SET current_length=0; "
        "UPDATE charging_pile SET status='IDLE', is_schedulable=1, fault_reason=NULL;"
    )
    subprocess.run([MYSQL_BIN, "-u", "root", "charging_station", "-e", sql],
                   check=True, capture_output=True)


def car(plate):
    return requests.get(f"{API}/charging/car/{plate}", timeout=5).json().get("data") or {}


def submit(plate, mode, amount, battery=60):
    return requests.post(f"{API}/charging/request", json={
        "carId": plate, "requestMode": mode,
        "requestAmount": amount, "batteryCapacity": battery,
    }, timeout=5).json()


def wait_status(plate, target, timeout=25):
    end = time.time() + timeout
    while time.time() < end:
        if car(plate).get("requestStatus") == target:
            return True
        time.sleep(0.8)
    return False


def setup():
    print("初始化：重置库 + 配置计费策略 + 启动全部充电桩")
    clean_db()
    requests.post(f"{API}/pile/parameters", json={
        "policyId": "TEST", "peakPrice": 1.0, "flatPrice": 0.7,
        "valleyPrice": 0.4, "serviceFeePerKwh": 0.8,
    }, timeout=5)
    for pid in ["P_F1", "P_F2", "P_T1", "P_T2", "P_T3"]:
        requests.post(f"{API}/pile/poweron?pileId={pid}", timeout=5)
        requests.post(f"{API}/pile/run?pileId={pid}", timeout=5)


def test_auto_lifecycle_and_bill():
    print("\n[1] 自动调度 -> 自动充电 -> 自动结算出账单")
    clean_db()
    submit("T_LIFE", "FAST", 3)
    check("自动进入充电（无需手动 start）", wait_status("T_LIFE", "CHARGING"))
    check("充满后自动结束", wait_status("T_LIFE", "FINISHED", timeout=20))
    time.sleep(1)
    bills = requests.get(f"{API}/billing/bill?carId=T_LIFE", timeout=5).json().get("data") or []
    check("自动生成账单", len(bills) >= 1)
    if bills:
        b = bills[0]
        check("服务费 = 3度 × 0.8 = 2.4", abs((b.get("totalServiceFee") or 0) - 2.4) < 0.01, str(b))
        check("总费用 = 电费 + 服务费",
              abs((b.get("totalFee") or 0) - ((b.get("totalChargeFee") or 0) + (b.get("totalServiceFee") or 0))) < 0.01)


def test_dispatch_by_type():
    print("\n[2] 按充电模式分配到对应类型的桩")
    clean_db()
    submit("T_TRICKLE", "TRICKLE", 3)
    submit("T_FAST", "FAST", 3)
    wait_status("T_TRICKLE", "CHARGING") or wait_status("T_TRICKLE", "QUEUED_AT_PILE")
    wait_status("T_FAST", "CHARGING") or wait_status("T_FAST", "QUEUED_AT_PILE")
    pt = car("T_TRICKLE").get("pileId") or ""
    pf = car("T_FAST").get("pileId") or ""
    check("慢充车分配到慢充桩 P_T*", pt.startswith("P_T"), f"pile={pt}")
    check("快充车分配到快充桩 P_F*", pf.startswith("P_F"), f"pile={pf}")


def test_reject_modify_when_charging():
    print("\n[3] 充电中修改充电量被拒绝")
    clean_db()
    submit("T_RMOD", "FAST", 30)  # 大电量，确保测试期间仍在充电
    assert wait_status("T_RMOD", "CHARGING"), "未进入充电"
    r = requests.post(f"{API}/charging/amount", json={"carId": "T_RMOD", "amount": 5}, timeout=5).json()
    check("充电中改充电量被拒绝", r.get("success") is False, r.get("message", ""))


def test_reject_over_capacity():
    print("\n[4] 充电量超过电池容量被拒绝")
    clean_db()
    r = submit("T_CAP", "FAST", 200, battery=60)
    check("充电量(200) > 电池容量(60) 被拒绝", r.get("success") is False, r.get("message", ""))


def test_modify_mode_in_waiting():
    print("\n[5] 等候区内修改充电模式，排队号前缀改变")
    clean_db()
    # 先把两个快充桩塞满（每桩 1充 + 2排队 = 6 辆），使第 7 辆快充车停在等候区
    for i in range(6):
        submit(f"FILL{i}", "FAST", 30)
    time.sleep(3)  # 等调度器把 6 辆填充车放入快充桩
    submit("T_MODE", "FAST", 3)
    time.sleep(1)
    st = car("T_MODE").get("requestStatus")
    if st != "WAITING":
        check("(前置)目标车停留在等候区", False, f"实际={st}（快充桩可能未饱和）")
        return
    r = requests.post(f"{API}/charging/mode", json={"carId": "T_MODE", "mode": "TRICKLE"}, timeout=5).json()
    check("WAITING 时改模式成功", r.get("success") is True, r.get("message", ""))
    qn = (r.get("data") or {}).get("queueNum", "")
    check("排队号变为 T 前缀", qn.startswith("T"), f"queueNum={qn}")


def test_modify_amount_in_waiting():
    print("\n[6] 等候区饱和时新车可修改充电量（WAITING 窗口）")
    clean_db()
    for i in range(6):
        submit(f"SAT{i}", "FAST", 30)
    time.sleep(3)
    submit("T_WMOD", "FAST", 4)
    time.sleep(1)
    st = car("T_WMOD").get("requestStatus")
    if st != "WAITING":
        check("(前置)目标车停留在等候区", False, f"实际={st}")
        return
    r = requests.post(f"{API}/charging/amount", json={"carId": "T_WMOD", "amount": 7}, timeout=5).json()
    check("WAITING 时改充电量成功", r.get("success") is True, r.get("message", ""))
    check("改后充电量=7", (r.get("data") or {}).get("requestAmount") == 7.0)


def test_fault_dispatch():
    print("\n[7] 充电中触发优先级故障调度，桩置为 FAULT")
    clean_db()
    submit("T_FAULT", "FAST", 30)
    assert wait_status("T_FAULT", "CHARGING"), "未进入充电"
    pid = car("T_FAULT").get("pileId")
    requests.post(f"{API}/dispatch/fault/priority?pileId={pid}", timeout=5)
    time.sleep(1)
    p = requests.get(f"{API}/monitor/pile/{pid}", timeout=5).json().get("data") or {}
    check("故障桩状态 = FAULT", p.get("status") == "FAULT", f"status={p.get('status')}")


def main():
    try:
        requests.get(f"{API}/monitor/piles", timeout=5)
    except Exception:
        print("后端未启动，请先运行 mvn spring-boot:run")
        sys.exit(1)

    setup()
    test_auto_lifecycle_and_bill()
    test_dispatch_by_type()
    test_reject_modify_when_charging()
    test_reject_over_capacity()
    test_modify_mode_in_waiting()
    test_modify_amount_in_waiting()
    test_fault_dispatch()
    clean_db()  # 收尾复位

    print("\n" + "=" * 50)
    print(f"通过 {passed} / {passed + failed}")
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
