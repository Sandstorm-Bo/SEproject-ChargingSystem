#!/bin/bash
# 数据库清理脚本 - 用于重置测试环境

echo "=== 重置数据库 ==="

mysql -u root charging_station << 'SQL'
-- 清空充电相关数据
DELETE FROM detailed_list;
DELETE FROM bill;
DELETE FROM charging_session;
DELETE FROM charging_request;

-- 重置队列
UPDATE waiting_queue SET queue_length = 0;

-- 验证
SELECT '充电请求' as item, COUNT(*) as count FROM charging_request
UNION ALL
SELECT '等候区(快充)', queue_length FROM waiting_queue WHERE queue_type='FAST'
UNION ALL
SELECT '等候区(慢充)', queue_length FROM waiting_queue WHERE queue_type='TRICKLE';
SQL

echo ""
echo "✓ 数据库已重置"
