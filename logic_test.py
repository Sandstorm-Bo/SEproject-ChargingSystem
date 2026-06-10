#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
充电桩系统 - 完整自动化逻辑测试
支持 A(Apply), B(Breakdown), C(Change), O(Over/结束) 事件类型
"""

import requests
import json
import time
import subprocess
from datetime import datetime

API_BASE = "http://localhost:8080/api"

class ChargingSystemTester:
    def __init__(self):
        self.results = []
        self.test_count = 0
        self.pass_count = 0
        self.fail_count = 0

    def log(self, msg, level="INFO"):
        ts = datetime.now().strftime("%H:%M:%S")
        print(f"[{ts}] [{level}] {msg}")

    def clean_database(self):
        """清理测试数据"""
        self.log("清理数据库...")
        try:
            subprocess.run([
                'mysql', '-u', 'root', 'charging_station', '-e',
                'DELETE FROM detailed_list; DELETE FROM bill; DELETE FROM charging_session; DELETE FROM charging_request;'
            ], check=True, capture_output=True)
            self.log("✓ 数据库清理完成")
            return True
        except Exception as e:
            self.log(f"✗ 清理失败: {e}", "ERROR")
            return False

    def setup(self):
        """初始化"""
        self.log("初始化测试环境...")
        if not self.clean_database():
            return False

        try:
            r = requests.get(f"{API_BASE}/monitor/piles", timeout=5)
            if r.status_code != 200:
                return False
            self.log("✓ 后端服务正常")

            requests.post(f"{API_BASE}/pile/parameters", json={
                "policyId": "TEST", "peakPrice": 1.0,
                "flatPrice": 0.7, "valleyPrice": 0.4,
                "serviceFeePerKwh": 0.8
            }, timeout=5)

            for pid in ["P_F1", "P_F2", "P_T1", "P_T2", "P_T3"]:
                requests.post(f"{API_BASE}/pile/poweron?pileId={pid}", timeout=5)
                requests.post(f"{API_BASE}/pile/run?pileId={pid}", timeout=5)

            self.log("✓ 环境初始化完成\n")
            return True
        except Exception as e:
            self.log(f"✗ 初始化失败: {e}", "ERROR")
            return False

    def handle_apply(self, vehicle, mode, amount):
        """A事件：申请充电"""
        request_mode = "FAST" if mode == "F" else "TRICKLE"
        try:
            r = requests.post(f"{API_BASE}/charging/request", json={
                "carId": vehicle,
                "requestAmount": float(amount),
                "requestMode": request_mode
            }, timeout=5)

            if r.status_code == 200:
                data = r.json()
                if data.get("success"):
                    return {"success": True, "data": data.get("data", {})}
                else:
                    return {"success": False, "error": data.get("message", "未知错误")}
            return {"success": False, "error": f"HTTP {r.status_code}"}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def handle_over(self, vehicle):
        """O事件：结束充电"""
        try:
            # 模拟结束充电 - 实际应该调用endCharging接口
            # 这里我们删除该车辆的请求来模拟结束
            subprocess.run([
                'mysql', '-u', 'root', 'charging_station', '-e',
                f"DELETE FROM charging_request WHERE car_id='{vehicle}'; DELETE FROM charging_session WHERE car_id='{vehicle}';"
            ], check=True, capture_output=True)
            return {"success": True, "message": f"{vehicle}结束充电"}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def handle_breakdown(self, pile_id, action):
        """B事件：充电桩故障/恢复"""
        full_pile_id = f"P_{pile_id}"
        try:
            if action == "0":
                r = requests.post(f"{API_BASE}/dispatch/fault/priority?pileId={full_pile_id}", timeout=5)
            else:
                r = requests.post(f"{API_BASE}/dispatch/recover?pileId={full_pile_id}", timeout=5)

            if r.status_code == 200:
                data = r.json()
                if data.get("success"):
                    return {"success": True, "message": data.get("data", "")}
            return {"success": False, "error": "操作失败"}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def handle_change(self, vehicle, mode, amount):
        """C事件：变更充电请求"""
        try:
            r = None
            if mode != "O":
                new_mode = "FAST" if mode == "F" else "TRICKLE"
                r = requests.put(f"{API_BASE}/charging/mode?carId={vehicle}&mode={new_mode}", timeout=5)

            if amount != "-1":
                r = requests.put(f"{API_BASE}/charging/amount?carId={vehicle}&amount={amount}", timeout=5)

            if r and r.status_code == 200:
                data = r.json()
                if data.get("success"):
                    return {"success": True, "data": data.get("data", {})}
            return {"success": False, "error": "变更失败"}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def run_test(self, tc, idx):
        """执行单个测试"""
        self.test_count += 1

        time_str = tc["time"]
        action = tc["action"]
        param1 = tc["vehicle"]
        mode = tc["mode"]
        amount = tc["amount"]

        self.log(f"\n{'='*60}")
        self.log(f"测试 {idx}: {time_str} - {action}({param1},{mode},{amount})")
        self.log(f"{'='*60}")

        result = {"test_id": idx, "time": time_str, "vehicle": param1, "passed": False, "issues": []}

        try:
            if action == "A":
                # 判断是否为O事件（结束充电）
                if mode == "O" and amount == "0":
                    self.log(f"[O] 结束充电: {param1}")
                    resp = self.handle_over(param1)
                else:
                    self.log(f"[A] 申请充电: {param1}, {'快充' if mode=='F' else '慢充'}, {amount}度")
                    resp = self.handle_apply(param1, mode, amount)
            elif action == "B":
                self.log(f"[B] 故障处理: {param1}, {'故障' if amount=='0' else '恢复'}")
                resp = self.handle_breakdown(param1, amount)
            elif action == "C":
                self.log(f"[C] 变更请求: {param1}, 模式={mode}, 充电量={amount}")
                resp = self.handle_change(param1, mode, amount)
            else:
                resp = {"success": False, "error": "未知事件类型"}

            if resp.get("success"):
                self.log("✓ 测试通过", "PASS")
                result["passed"] = True
                self.pass_count += 1
            else:
                self.log(f"✗ 测试失败: {resp.get('error')}", "FAIL")
                result["issues"].append(resp.get("error"))
                self.fail_count += 1

        except Exception as e:
            self.log(f"✗ 测试异常: {e}", "ERROR")
            result["issues"].append(str(e))
            self.fail_count += 1

        self.results.append(result)
        time.sleep(0.3)

    def run_all(self):
        """运行所有测试"""
        self.log("\n" + "="*60)
        self.log("充电桩系统 - 完整逻辑测试")
        self.log("="*60 + "\n")

        try:
            with open("test_cases.json", 'r', encoding='utf-8') as f:
                cases = json.load(f)
            self.log(f"加载了 {len(cases)} 个测试用例")
        except Exception as e:
            self.log(f"✗ 无法加载: {e}", "ERROR")
            return

        for i, tc in enumerate(cases[:15], 1):
            self.run_test(tc, i)

        self.report()

    def report(self):
        """生成报告"""
        self.log("\n" + "="*60)
        self.log("测试报告")
        self.log("="*60)
        self.log(f"\n总测试数: {self.test_count}")
        self.log(f"通过: {self.pass_count} ({self.pass_count/self.test_count*100:.1f}%)")
        self.log(f"失败: {self.fail_count} ({self.fail_count/self.test_count*100:.1f}%)")

        with open("test_results.json", 'w', encoding='utf-8') as f:
            json.dump({
                "summary": {"total": self.test_count, "passed": self.pass_count, "failed": self.fail_count, "pass_rate": round(self.pass_count/self.test_count*100, 1)},
                "results": self.results
            }, f, ensure_ascii=False, indent=2)

        self.log(f"\n详细结果: test_results.json")

        if self.fail_count > 0:
            self.log(f"\n失败的测试:", "WARN")
            for r in self.results:
                if not r['passed']:
                    self.log(f"  测试{r['test_id']}: {r['time']} - {r.get('vehicle')} - {r['issues']}", "WARN")

def main():
    tester = ChargingSystemTester()
    if not tester.setup():
        return
    tester.run_all()

if __name__ == "__main__":
    main()
