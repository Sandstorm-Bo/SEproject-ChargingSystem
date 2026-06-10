#!/bin/bash
# 数据库清理脚本 - 用于重置测试环境

echo "=== 重置数据库 ==="

mysql -u root charging_station << 'SQL'
-- 清空充电相关数据
DELETE FROM detailed_list;
DELETE FROM bill;
DELETE FROM charging_session;
DELETE FROM charging_request;

-- 重置队列长度
UPDATE waiting_queue SET queue_length = 0;
UPDATE charging_queue SET current_length = 0;

-- 验证
SELECT '充电请求' as item, COUNT(*) as count FROM charging_request
UNION ALL
SELECT '等候区(快充)', queue_length FROM waiting_queue WHERE queue_type='FAST'
UNION ALL
SELECT '等候区(慢充)', queue_length FROM waiting_queue WHERE queue_type='TRICKLE'
UNION ALL
SELECT '充电桩队列', SUM(current_length) FROM charging_queue;
SQL

echo ""
echo "✓ 数据库已重置"
