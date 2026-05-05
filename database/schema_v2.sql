-- ============================================
-- EventHub 活动管理平台数据库表结构 V2
-- 新增：消息队列表、报表配置表
-- ============================================

USE eventhub;

-- ============================================
-- 9. 消息队列表 (异步通知队列)
-- ============================================
CREATE TABLE IF NOT EXISTS message_queues (
    queue_id VARCHAR(36) PRIMARY KEY COMMENT '队列ID',
    message_type ENUM('email', 'sms', 'webhook') NOT NULL COMMENT '消息类型',
    recipient VARCHAR(200) NOT NULL COMMENT '接收者',
    subject VARCHAR(200) COMMENT '主题 (邮件用)',
    content TEXT NOT NULL COMMENT '消息内容 (JSON格式)',
    template_code VARCHAR(50) COMMENT '模板编码',
    template_params JSON COMMENT '模板参数',
    priority INT DEFAULT 0 COMMENT '优先级 (0-10, 越高越优先)',
    status ENUM('pending', 'processing', 'sent', 'failed', 'cancelled') DEFAULT 'pending' COMMENT '状态',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    max_retry INT DEFAULT 3 COMMENT '最大重试次数',
    error_message TEXT COMMENT '错误信息',
    scheduled_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '计划发送时间',
    sent_at DATETIME COMMENT '实际发送时间',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_message_status (status),
    INDEX idx_message_type (message_type),
    INDEX idx_scheduled_at (scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息队列表';

-- ============================================
-- 10. 报表配置表 (自定义报表配置)
-- ============================================
CREATE TABLE IF NOT EXISTS report_configs (
    config_id VARCHAR(36) PRIMARY KEY COMMENT '配置ID',
    event_id VARCHAR(36) COMMENT '活动ID (为空则为全局配置)',
    config_name VARCHAR(100) NOT NULL COMMENT '配置名称',
    config_type ENUM('overview', 'registration_trend', 'ticket_sales', 'checkin_stats', 'custom') NOT NULL COMMENT '配置类型',
    chart_type ENUM('line', 'bar', 'pie', 'area', 'table', 'number') NOT NULL COMMENT '图表类型',
    dimensions JSON NOT NULL COMMENT '统计维度配置',
    metrics JSON NOT NULL COMMENT '指标配置',
    filters JSON COMMENT '过滤条件',
    time_range JSON COMMENT '时间范围配置',
    refresh_interval INT DEFAULT 0 COMMENT '自动刷新间隔 (分钟, 0为不刷新)',
    is_default BOOLEAN DEFAULT FALSE COMMENT '是否默认配置',
    is_public BOOLEAN DEFAULT FALSE COMMENT '是否公开',
    created_by VARCHAR(36) COMMENT '创建者ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (event_id) REFERENCES events(event_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表配置表';

-- ============================================
-- 11. 报表模板表 (预设报表模板)
-- ============================================
CREATE TABLE IF NOT EXISTS report_templates (
    template_id VARCHAR(36) PRIMARY KEY COMMENT '模板ID',
    template_name VARCHAR(100) NOT NULL COMMENT '模板名称',
    template_category VARCHAR(50) NOT NULL COMMENT '模板分类',
    description TEXT COMMENT '模板描述',
    chart_type ENUM('line', 'bar', 'pie', 'area', 'table', 'number') NOT NULL COMMENT '图表类型',
    dimensions JSON NOT NULL COMMENT '统计维度配置',
    metrics JSON NOT NULL COMMENT '指标配置',
    default_filters JSON COMMENT '默认过滤条件',
    is_system BOOLEAN DEFAULT TRUE COMMENT '是否系统预设',
    is_active BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报表模板表';

-- ============================================
-- 插入预设报表模板
-- ============================================
INSERT INTO report_templates (template_id, template_name, template_category, description, chart_type, dimensions, metrics, is_system, is_active) VALUES
('template_reg_trend', '报名趋势图', 'registration', '展示活动报名人数的时间趋势', 'area', 
 '[{"key":"date","label":"日期","type":"time"}]',
 '[{"key":"registrations","label":"报名人数","type":"count","aggregation":"sum"}]',
 TRUE, TRUE),

('template_ticket_pie', '票务销售分布', 'ticket', '展示各票务类型的销售占比', 'pie',
 '[{"key":"ticket_name","label":"票务类型","type":"category"}]',
 '[{"key":"sold_count","label":"销售数量","type":"count"}]',
 TRUE, TRUE),

('template_ticket_bar', '票务销售对比', 'ticket', '展示各票务类型的销售数量对比', 'bar',
 '[{"key":"ticket_name","label":"票务类型","type":"category"}]',
 '[{"key":"sold_count","label":"销售数量","type":"count"},{"key":"revenue","label":"销售额","type":"sum"}]',
 TRUE, TRUE),

('template_checkin_stats', '签到统计', 'checkin', '展示活动签到情况统计', 'bar',
 '[{"key":"status","label":"签到状态","type":"category"}]',
 '[{"key":"count","label":"人数","type":"count"}]',
 TRUE, TRUE),

('template_overview', '总览数据', 'overview', '展示活动整体数据概览', 'number',
 '[]',
 '[{"key":"total_registrations","label":"总报名数","type":"count"},{"key":"approved_registrations","label":"已通过数","type":"count"},{"key":"checked_in_count","label":"已签到数","type":"count"},{"key":"total_revenue","label":"总营收","type":"sum"}]',
 TRUE, TRUE);

-- ============================================
-- 修改通知表添加队列关联
-- ============================================
ALTER TABLE notifications ADD COLUMN queue_id VARCHAR(36) NULL COMMENT '关联的消息队列ID';
ALTER TABLE notifications ADD INDEX idx_queue_id (queue_id);
