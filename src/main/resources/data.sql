-- 智能充电桩调度计费系统 - 初始化数据脚本

-- 插入充电桩数据（2个快充桩 + 3个慢充桩）
INSERT INTO charging_pile (pile_id, pile_no, pile_type, status, rated_power, queue_capacity_m, is_schedulable) VALUES
('P_F1', 'F1', 'FAST', 'IDLE', 30.0, 2, TRUE),
('P_F2', 'F2', 'FAST', 'IDLE', 30.0, 2, TRUE),
('P_T1', 'T1', 'TRICKLE', 'IDLE', 7.0, 2, TRUE),
('P_T2', 'T2', 'TRICKLE', 'IDLE', 7.0, 2, TRUE),
('P_T3', 'T3', 'TRICKLE', 'IDLE', 7.0, 2, TRUE);

-- 插入等候区队列
INSERT INTO waiting_queue (queue_id, queue_type, queue_length, max_capacity) VALUES
('WQ_FAST', 'FAST', 0, 12),
('WQ_TRICKLE', 'TRICKLE', 0, 12);

-- 插入充电桩前队列
INSERT INTO charging_queue (queue_id, pile_id, queue_len_m, current_length) VALUES
('CQ_F1', 'P_F1', 2, 0),
('CQ_F2', 'P_F2', 2, 0),
('CQ_T1', 'P_T1', 2, 0),
('CQ_T2', 'P_T2', 2, 0),
('CQ_T3', 'P_T3', 2, 0);

-- 插入默认计费策略
INSERT INTO tariff_policy (policy_id, status, peak_price, flat_price, valley_price, service_fee_per_kwh) VALUES
('POLICY_DEFAULT', '已配置', 1.2, 0.7, 0.4, 0.8);

-- 插入测试车辆数据
INSERT INTO vehicle (vehicle_id, plate_number, vehicle_type, battery_capacity) VALUES
('京A10001', '京A10001', '电动汽车', 100.0),
('京A10002', '京A10002', '电动汽车', 80.0),
('京A10003', '京A10003', '电动汽车', 90.0),
('京A10004', '京A10004', '电动汽车', 85.0),
('京A10005', '京A10005', '电动汽车', 100.0);
