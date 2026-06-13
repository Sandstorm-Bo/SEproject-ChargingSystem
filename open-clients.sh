#!/bin/bash
# 多开用户客户端 - 用独立 Chrome profile 拉起 N 个互不干扰的用户端窗口
#
# 每个客户端用独立的 --user-data-dir，sessionStorage/localStorage 完全隔离，
# 可同时登录不同用户模拟多客户端。直接 open client.html 只会激活同一个标签页，
# 无法多开，故用本脚本。
#
# 用法:
#   ./open-clients.sh        # 默认开 3 个
#   ./open-clients.sh 5      # 开 5 个

set -e

N="${1:-3}"
DIR="$(cd "$(dirname "$0")" && pwd)"
URL="file://$DIR/client.html"

# 优先 Google Chrome，没有则退回系统默认浏览器（默认浏览器无法隔离 profile，仅兜底）
if [ -d "/Applications/Google Chrome.app" ]; then
  for i in $(seq 1 "$N"); do
    open -na "Google Chrome" --args \
      --user-data-dir="/tmp/ion-client-$i" \
      --new-window "$URL"
  done
  echo "✓ 已用 Chrome 独立 profile 开启 $N 个用户客户端窗口（互不干扰，可分别登录不同用户）"
  echo "  清理临时 profile: rm -rf /tmp/ion-client-*"
else
  echo "⚠ 未检测到 Google Chrome，回退到默认浏览器（无法隔离会话，建议手动新建标签页）"
  open "$URL"
fi
