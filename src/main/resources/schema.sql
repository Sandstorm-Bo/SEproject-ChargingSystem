-- 智能充电桩调度计费系统 - 数据库建表脚本
-- 根据设计文档数据库设计

-- 删除已存在的表
DROP TABLE IF EXISTS detailed_list;
DROP TABLE IF EXISTS bill;
DROP TABLE IF EXISTS charging_session;
DROP TABLE IF EXISTS charging_queue;
DROP TABLE IF EXISTS waiting_queue;
DROP TABLE IF EXISTS charging_request;
DROP TABLE IF EXISTS vehicle;
DROP TABLE IF EXISTS charging_pile;
DROP TABLE IF EXISTS tariff_policy;

-- 1. 车辆表
CREATE TABLE vehicle (
    vehicle_id VARCHAR(50) PRIMARY KEY COMMENT '车辆编号',
    plate_number VARCHAR(20) NOT NULL COMMENT '车牌号',
    vehicle_type VARCHAR(50) COMMENT '车辆类型',
    battery_capacity DOUBLE NOT NULL DEFAULT 100 COMMENT '电池总容量（度）',
    current_battery_level DOUBLE DEFAULT 0 COMMENT '当前电量（度）',
    car_state VARCHAR(20) DEFAULT 'IDLE' COMMENT '当前状态',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_plate (plate_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆信息表';

-- 2. 充电桩表
CREATE TABLE charging_pile (
    pile_id VARCHAR(50) PRIMARY KEY COMMENT '充电桩编号',
    pile_no VARCHAR(10) NOT NULL COMMENT '桩号',
    pile_type VARCHAR(20) NOT NULL COMMENT '快充/慢充',
    status VARCHAR(20) DEFAULT 'OFFLINE' COMMENT '状态',
    rated_power DOUBLE NOT NULL COMMENT '额定功率（度/小时）',
    queue_capacity_m INT DEFAULT 2 COMMENT '桩后队列长度M',
    is_working BOOLEAN DEFAULT TRUE COMMENT '是否正常工作',
    total_charge_count INT DEFAULT 0 COMMENT '累计充电次数',
    total_charge_duration DOUBLE DEFAULT 0 COMMENT '累计充电时长（分钟）',
    total_charge_amount DOUBLE DEFAULT 0 COMMENT '累计充电量（度）',
    is_schedulable BOOLEAN DEFAULT FALSE COMMENT '是否可参与调度',
    fault_reason VARCHAR(200) COMMENT '故障原因',
    last_run_time TIMESTAMP NULL COMMENT '最近启动时间',
    last_stop_time TIMESTAMP NULL COMMENT '最近停止时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_type (pile_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充电桩表';

-- 3. 充电请求表
CREATE TABLE charging_request (
    request_id VARCHAR(50) PRIMARY KEY COMMENT '请求编号',
    car_id VARCHAR(50) NOT NULL COMMENT '车辆编号',
    request_mode VARCHAR(20) NOT NULL COMMENT '充电模式',
    request_amount DOUBLE NOT NULL COMMENT '请求充电量（度）',
    queue_num VARCHAR(20) NOT NULL COMMENT '排队号',
    request_time TIMESTAMP NOT NULL COMMENT '提交时间',
    request_status VARCHAR(30) NOT NULL COMMENT '请求状态',
    pile_id VARCHAR(50) COMMENT '分配的充电桩编号',
    queue_position INT COMMENT '在充电桩队列中的位置',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_car_id (car_id),
    INDEX idx_status (request_status),
    INDEX idx_pile (pile_id),
    FOREIGN KEY (car_id) REFERENCES vehicle(vehicle_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充电请求表';

-- 4. 等候区队列表
CREATE TABLE waiting_queue (
    queue_id VARCHAR(50) PRIMARY KEY COMMENT '队列编号',
    queue_type VARCHAR(20) NOT NULL COMMENT '快充/慢充',
    queue_length INT DEFAULT 0 COMMENT '当前队列长度',
    max_capacity INT DEFAULT 12 COMMENT '最大容量',
    queue_status VARCHAR(20) DEFAULT 'NORMAL' COMMENT '队列状态',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_type (queue_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='等候区队列表';

-- 5. 充电桩前队列表
CREATE TABLE charging_queue (
    queue_id VARCHAR(50) PRIMARY KEY COMMENT '队列编号',
    pile_id VARCHAR(50) NOT NULL COMMENT '所属充电桩编号',
    queue_len_m INT DEFAULT 2 COMMENT '队列最大长度M',
    current_length INT DEFAULT 0 COMMENT '当前队列长度',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pile (pile_id),
    FOREIGN KEY (pile_id) REFERENCES charging_pile(pile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充电桩前队列表';

-- 6. 充电会话表
CREATE TABLE charging_session (
    session_id VARCHAR(50) PRIMARY KEY COMMENT '会话编号',
    car_id VARCHAR(50) NOT NULL COMMENT '车辆编号',
    pile_id VARCHAR(50) NOT NULL COMMENT '充电桩编号',
    request_amount DOUBLE NOT NULL COMMENT '请求充电量（度）',
    start_time TIMESTAMP NOT NULL COMMENT '开始时间',
    end_time TIMESTAMP NULL COMMENT '结束时间',
    session_status VARCHAR(20) DEFAULT '进行中' COMMENT '会话状态',
    charge_amount DOUBLE DEFAULT 0 COMMENT '实际充电量（度）',
    charge_duration DOUBLE DEFAULT 0 COMMENT '充电时长（分钟）',
    charge_fee DOUBLE DEFAULT 0 COMMENT '充电费用（元）',
    service_fee DOUBLE DEFAULT 0 COMMENT '服务费用（元）',
    total_fee DOUBLE DEFAULT 0 COMMENT '总费用（元）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_car (car_id),
    INDEX idx_pile (pile_id),
    INDEX idx_status (session_status),
    FOREIGN KEY (car_id) REFERENCES vehicle(vehicle_id),
    FOREIGN KEY (pile_id) REFERENCES charging_pile(pile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充电会话表';

-- 7. 账单表
CREATE TABLE bill (
    bill_id VARCHAR(50) PRIMARY KEY COMMENT '账单编号',
    car_id VARCHAR(50) NOT NULL COMMENT '车辆编号',
    date TIMESTAMP NOT NULL COMMENT '账单日期',
    total_charge_fee DOUBLE DEFAULT 0 COMMENT '总充电费用（元）',
    total_service_fee DOUBLE DEFAULT 0 COMMENT '总服务费用（元）',
    total_fee DOUBLE DEFAULT 0 COMMENT '总费用（元）',
    bill_status VARCHAR(20) DEFAULT '已生成' COMMENT '账单状态',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_car_date (car_id, date),
    FOREIGN KEY (car_id) REFERENCES vehicle(vehicle_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单表';

-- 8. 详单表
CREATE TABLE detailed_list (
    detail_id VARCHAR(50) PRIMARY KEY COMMENT '详单编号',
    bill_id VARCHAR(50) NOT NULL COMMENT '所属账单编号',
    pile_id VARCHAR(50) NOT NULL COMMENT '充电桩编号',
    charge_amount DOUBLE DEFAULT 0 COMMENT '充电电量（度）',
    charge_duration DOUBLE DEFAULT 0 COMMENT '充电时长（分钟）',
    start_time TIMESTAMP NOT NULL COMMENT '开始时间',
    end_time TIMESTAMP NULL COMMENT '结束时间',
    charge_fee DOUBLE DEFAULT 0 COMMENT '充电费用（元）',
    service_fee DOUBLE DEFAULT 0 COMMENT '服务费用（元）',
    subtotal_fee DOUBLE DEFAULT 0 COMMENT '小计费用（元）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_bill (bill_id),
    FOREIGN KEY (bill_id) REFERENCES bill(bill_id),
    FOREIGN KEY (pile_id) REFERENCES charging_pile(pile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='详单表';

-- 9. 计费策略表
CREATE TABLE tariff_policy (
    policy_id VARCHAR(50) PRIMARY KEY COMMENT '策略编号',
    status VARCHAR(20) DEFAULT '未配置' COMMENT '配置状态',
    peak_price DOUBLE NOT NULL COMMENT '峰时电价（元/度）',
    flat_price DOUBLE NOT NULL COMMENT '平时电价（元/度）',
    valley_price DOUBLE NOT NULL COMMENT '谷时电价（元/度）',
    service_fee_per_kwh DOUBLE NOT NULL COMMENT '服务费单价（元/度）',
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计费策略表';
