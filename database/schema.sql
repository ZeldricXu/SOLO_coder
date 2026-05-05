-- ============================================
-- EventHub 活动管理平台数据库表结构
-- 创建时间: 2026-05-05
-- 数据库: MySQL
-- ============================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS eventhub DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE eventhub;

-- ============================================
-- 1. 用户表 (用户信息)
-- ============================================
CREATE TABLE IF NOT EXISTS users (
    user_id VARCHAR(36) PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    email VARCHAR(100) NOT NULL UNIQUE COMMENT '邮箱',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希',
    phone VARCHAR(20) COMMENT '手机号',
    role ENUM('organizer', 'admin') DEFAULT 'organizer' COMMENT '角色',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ============================================
-- 2. 活动表 (活动基本信息)
-- ============================================
CREATE TABLE IF NOT EXISTS events (
    event_id VARCHAR(36) PRIMARY KEY COMMENT '活动ID',
    organizer_id VARCHAR(36) NOT NULL COMMENT '组织者ID',
    title VARCHAR(200) NOT NULL COMMENT '活动标题',
    description TEXT COMMENT '活动描述',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME NOT NULL COMMENT '结束时间',
    location VARCHAR(200) COMMENT '活动地点',
    max_attendees INT DEFAULT 0 COMMENT '最大参会人数',
    status ENUM('draft', 'published', 'closed', 'cancelled') DEFAULT 'draft' COMMENT '活动状态',
    need_approval BOOLEAN DEFAULT FALSE COMMENT '是否需要审核',
    cover_image VARCHAR(500) COMMENT '封面图片URL',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (organizer_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动表';

-- ============================================
-- 3. 表单字段配置表 (报名表单字段配置)
-- ============================================
CREATE TABLE IF NOT EXISTS form_fields (
    field_id VARCHAR(36) PRIMARY KEY COMMENT '字段ID',
    event_id VARCHAR(36) NOT NULL COMMENT '活动ID',
    field_name VARCHAR(100) NOT NULL COMMENT '字段名称',
    field_label VARCHAR(100) NOT NULL COMMENT '字段标签',
    field_type ENUM('text', 'textarea', 'select', 'radio', 'checkbox', 'number', 'email', 'phone', 'date') NOT NULL COMMENT '字段类型',
    placeholder VARCHAR(200) COMMENT '占位符',
    required BOOLEAN DEFAULT FALSE COMMENT '是否必填',
    options JSON COMMENT '选项配置 (JSON数组)',
    validation_rules JSON COMMENT '验证规则 (JSON对象)',
    sort_order INT DEFAULT 0 COMMENT '排序顺序',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (event_id) REFERENCES events(event_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表单字段配置表';

-- ============================================
-- 4. 票务类型表 (票务配置)
-- ============================================
CREATE TABLE IF NOT EXISTS tickets (
    ticket_id VARCHAR(36) PRIMARY KEY COMMENT '票务ID',
    event_id VARCHAR(36) NOT NULL COMMENT '活动ID',
    ticket_name VARCHAR(100) NOT NULL COMMENT '票务名称',
    price DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '价格',
    quota INT NOT NULL DEFAULT 0 COMMENT '配额',
    sold_count INT DEFAULT 0 COMMENT '已售数量',
    description TEXT COMMENT '票务描述',
    status ENUM('available', 'unavailable', 'sold_out') DEFAULT 'available' COMMENT '状态',
    version INT DEFAULT 1 COMMENT '乐观锁版本号',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (event_id) REFERENCES events(event_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='票务类型表';

-- ============================================
-- 5. 报名表 (报名记录)
-- ============================================
CREATE TABLE IF NOT EXISTS registrations (
    registration_id VARCHAR(36) PRIMARY KEY COMMENT '报名ID',
    event_id VARCHAR(36) NOT NULL COMMENT '活动ID',
    ticket_id VARCHAR(36) COMMENT '票务ID',
    ticket_name VARCHAR(100) COMMENT '票务名称',
    user_id VARCHAR(36) COMMENT '用户ID (可选，支持未登录用户)',
    form_data JSON NOT NULL COMMENT '表单数据 (JSON对象)',
    status ENUM('pending_review', 'approved', 'rejected', 'cancelled') DEFAULT 'pending_review' COMMENT '报名状态',
    check_in_status BOOLEAN DEFAULT FALSE COMMENT '签到状态',
    check_in_time DATETIME COMMENT '签到时间',
    total_amount DECIMAL(10, 2) DEFAULT 0.00 COMMENT '总金额',
    paid_amount DECIMAL(10, 2) DEFAULT 0.00 COMMENT '已支付金额',
    payment_status ENUM('unpaid', 'pending', 'paid', 'refunded') DEFAULT 'unpaid' COMMENT '支付状态',
    notes TEXT COMMENT '备注',
    registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '报名时间',
    approved_at DATETIME COMMENT '审核时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (event_id) REFERENCES events(event_id) ON DELETE CASCADE,
    FOREIGN KEY (ticket_id) REFERENCES tickets(ticket_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报名表';

-- ============================================
-- 6. 通知记录表 (通知发送记录)
-- ============================================
CREATE TABLE IF NOT EXISTS notifications (
    notification_id VARCHAR(36) PRIMARY KEY COMMENT '通知ID',
    registration_id VARCHAR(36) COMMENT '报名ID',
    event_id VARCHAR(36) COMMENT '活动ID',
    recipient_type ENUM('email', 'sms') NOT NULL COMMENT '接收类型',
    recipient VARCHAR(200) NOT NULL COMMENT '接收者 (邮箱或手机号)',
    notification_type ENUM('registration_submitted', 'approval_pending', 'approved', 'rejected', 'check_in_reminder', 'event_reminder') NOT NULL COMMENT '通知类型',
    subject VARCHAR(200) COMMENT '主题 (邮件用)',
    content TEXT NOT NULL COMMENT '内容',
    status ENUM('pending', 'sent', 'failed') DEFAULT 'pending' COMMENT '发送状态',
    error_message TEXT COMMENT '错误信息',
    sent_at DATETIME COMMENT '发送时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (registration_id) REFERENCES registrations(registration_id) ON DELETE SET NULL,
    FOREIGN KEY (event_id) REFERENCES events(event_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知记录表';

-- ============================================
-- 7. 签到记录表 (签到详细记录)
-- ============================================
CREATE TABLE IF NOT EXISTS check_ins (
    check_in_id VARCHAR(36) PRIMARY KEY COMMENT '签到ID',
    registration_id VARCHAR(36) NOT NULL COMMENT '报名ID',
    event_id VARCHAR(36) NOT NULL COMMENT '活动ID',
    check_in_method ENUM('qr_code', 'manual') NOT NULL COMMENT '签到方式',
    check_in_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '签到时间',
    check_in_by VARCHAR(36) COMMENT '签到操作者ID',
    notes TEXT COMMENT '备注',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (registration_id) REFERENCES registrations(registration_id) ON DELETE CASCADE,
    FOREIGN KEY (event_id) REFERENCES events(event_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='签到记录表';

-- ============================================
-- 8. 操作日志表 (操作记录)
-- ============================================
CREATE TABLE IF NOT EXISTS operation_logs (
    log_id VARCHAR(36) PRIMARY KEY COMMENT '日志ID',
    user_id VARCHAR(36) COMMENT '操作者ID',
    event_id VARCHAR(36) COMMENT '活动ID',
    registration_id VARCHAR(36) COMMENT '报名ID',
    action VARCHAR(100) NOT NULL COMMENT '操作动作',
    details JSON COMMENT '操作详情',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

-- ============================================
-- 创建索引
-- ============================================
CREATE INDEX idx_events_organizer ON events(organizer_id);
CREATE INDEX idx_events_status ON events(status);
CREATE INDEX idx_form_fields_event ON form_fields(event_id);
CREATE INDEX idx_tickets_event ON tickets(event_id);
CREATE INDEX idx_tickets_status ON tickets(status);
CREATE INDEX idx_registrations_event ON registrations(event_id);
CREATE INDEX idx_registrations_status ON registrations(status);
CREATE INDEX idx_registrations_checkin ON registrations(check_in_status);
CREATE INDEX idx_notifications_status ON notifications(status);
CREATE INDEX idx_checkins_event ON check_ins(event_id);

-- ============================================
-- 插入初始数据 (测试用)
-- ============================================
-- 插入测试用户
INSERT INTO users (user_id, username, email, password_hash, role) 
VALUES ('user_org_01', 'test_organizer', 'organizer@example.com', '$2a$10$placeholder_hash', 'organizer');

-- 插入测试活动
INSERT INTO events (
    event_id, organizer_id, title, description, start_time, end_time, 
    location, max_attendees, status, need_approval
) VALUES (
    'event_001', 'user_org_01', '2026年技术峰会', '年度技术分享与交流大会',
    '2026-06-15 09:00:00', '2026-06-15 18:00:00',
    '北京国际会议中心', 500, 'published', TRUE
);

-- 插入测试表单字段
INSERT INTO form_fields (field_id, event_id, field_name, field_label, field_type, required, sort_order)
VALUES 
    ('field_001', 'event_001', 'name', '姓名', 'text', TRUE, 1),
    ('field_002', 'event_001', 'email', '邮箱', 'email', TRUE, 2),
    ('field_003', 'event_001', 'phone', '手机号', 'phone', TRUE, 3),
    ('field_004', 'event_001', 'company', '公司', 'text', FALSE, 4),
    ('field_005', 'event_001', 'position', '职位', 'text', FALSE, 5);

-- 插入测试票务
INSERT INTO tickets (ticket_id, event_id, ticket_name, price, quota, sold_count, description, status)
VALUES 
    ('ticket_vip_01', 'event_001', 'VIP票', 500.00, 100, 45, '前排座位+午餐+周边礼品', 'available'),
    ('ticket_std_01', 'event_001', '普通票', 200.00, 400, 120, '标准座位', 'available');
