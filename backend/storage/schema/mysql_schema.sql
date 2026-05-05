-- GameStats 游戏数据分析系统 MySQL 数据库 schema
-- 创建日期: 2026-05-05

-- 创建数据库
CREATE DATABASE IF NOT EXISTS gamestats 
DEFAULT CHARACTER SET utf8mb4 
DEFAULT COLLATE utf8mb4_unicode_ci;

USE gamestats;

-- 事件表 - 存储所有游戏事件
CREATE TABLE IF NOT EXISTS events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE COMMENT '事件唯一ID',
    player_id VARCHAR(64) NOT NULL COMMENT '玩家ID',
    game_id VARCHAR(64) NOT NULL COMMENT '游戏ID',
    server_id VARCHAR(64) NOT NULL COMMENT '服务器ID',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型: login, logout, payment, level_up等',
    event_time DATETIME(3) NOT NULL COMMENT '事件发生时间',
    event_data JSON COMMENT '事件扩展数据(JSON格式)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_player_id (player_id),
    INDEX idx_game_id (game_id),
    INDEX idx_event_type (event_type),
    INDEX idx_event_time (event_time),
    INDEX idx_player_time (player_id, event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='游戏事件表';

-- 玩家画像表 - 存储玩家画像数据
CREATE TABLE IF NOT EXISTS player_profiles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id VARCHAR(64) NOT NULL UNIQUE COMMENT '玩家ID',
    profile_tags JSON COMMENT '画像标签列表(JSON数组)',
    level INT DEFAULT 1 COMMENT '玩家等级',
    vip_level INT DEFAULT 0 COMMENT 'VIP等级',
    total_play_time INT DEFAULT 0 COMMENT '累计游戏时长(分钟)',
    pay_amount DECIMAL(10, 2) DEFAULT 0.00 COMMENT '累计付费金额',
    last_active DATETIME COMMENT '最后活跃时间',
    churn_risk VARCHAR(16) DEFAULT 'low' COMMENT '流失风险等级: low, medium, high',
    activity_score DECIMAL(5, 2) DEFAULT 0.00 COMMENT '活跃度评分',
    payment_score DECIMAL(5, 2) DEFAULT 0.00 COMMENT '付费评分',
    social_score DECIMAL(5, 2) DEFAULT 0.00 COMMENT '社交评分',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_churn_risk (churn_risk),
    INDEX idx_last_active (last_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家画想表';

-- 分析结果表 - 存储批量分析结果
CREATE TABLE IF NOT EXISTS analysis_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    analysis_type VARCHAR(64) NOT NULL COMMENT '分析类型: retention, funnel, etc.',
    game_id VARCHAR(64) NOT NULL COMMENT '游戏ID',
    result_data JSON COMMENT '分析结果数据(JSON格式)',
    period_start DATE COMMENT '统计周期开始',
    period_end DATE COMMENT '统计周期结束',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_analysis_type (analysis_type),
    INDEX idx_period (period_start, period_end),
    INDEX idx_game_time (game_id, period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分析结果表';

-- 每日指标统计表 - 存储每日聚合指标
CREATE TABLE IF NOT EXISTS daily_metrics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    metric_date DATE NOT NULL COMMENT '统计日期',
    game_id VARCHAR(64) NOT NULL COMMENT '游戏ID',
    dau INT DEFAULT 0 COMMENT '日活跃用户数',
    dnu INT DEFAULT 0 COMMENT '日新增用户数',
    dpu INT DEFAULT 0 COMMENT '日付费用户数',
    revenue DECIMAL(12, 2) DEFAULT 0.00 COMMENT '日收入',
    arpu DECIMAL(10, 2) DEFAULT 0.00 COMMENT '每用户平均收入',
    arppu DECIMAL(10, 2) DEFAULT 0.00 COMMENT '每付费用户平均收入',
    retention_day1 DECIMAL(5, 2) DEFAULT 0.00 COMMENT '次日留存率',
    retention_day7 DECIMAL(5, 2) DEFAULT 0.00 COMMENT '7日留存率',
    retention_day30 DECIMAL(5, 2) DEFAULT 0.00 COMMENT '30日留存率',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_metric_game (metric_date, game_id),
    INDEX idx_metric_date (metric_date),
    INDEX idx_game_date (game_id, metric_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日指标统计表';

-- 服务器统计表 - 存储服务器维度的统计数据
CREATE TABLE IF NOT EXISTS server_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_id VARCHAR(64) NOT NULL COMMENT '游戏ID',
    server_id VARCHAR(64) NOT NULL COMMENT '服务器ID',
    stat_time DATETIME(3) NOT NULL COMMENT '统计时间',
    online_count INT DEFAULT 0 COMMENT '在线人数',
    peak_online INT DEFAULT 0 COMMENT '峰值在线',
    new_players INT DEFAULT 0 COMMENT '新增玩家数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_game_server (game_id, server_id),
    INDEX idx_stat_time (stat_time),
    INDEX idx_game_time (game_id, stat_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务器统计表';

-- 事件类型配置表 - 配置事件类型和字段
CREATE TABLE IF NOT EXISTS event_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_id VARCHAR(64) NOT NULL COMMENT '游戏ID',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型',
    event_name VARCHAR(128) COMMENT '事件显示名称',
    description TEXT COMMENT '事件描述',
    required_fields JSON COMMENT '必填字段(JSON格式)',
    optional_fields JSON COMMENT '可选字段(JSON格式)',
    is_active BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_game_event (game_id, event_type),
    INDEX idx_game_id (game_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件类型配置表';

-- 插入默认事件类型配置
INSERT INTO event_configs (game_id, event_type, event_name, description, required_fields, optional_fields) VALUES
('default', 'login', '玩家登录', '玩家登录游戏事件', '{"login_method":"登录方式","device_type":"设备类型"}', '{"ip_region":"IP地区"}'),
('default', 'logout', '玩家登出', '玩家登出游戏事件', '{}', '{"session_duration":"会话时长","reason":"登出原因"}'),
('default', 'payment', '支付事件', '玩家付费事件', '{"amount":"金额","currency":"币种","item_id":"商品ID"}', '{"payment_method":"支付方式"}'),
('default', 'level_up', '等级提升', '玩家等级提升事件', '{"new_level":"新等级","previous_level":"旧等级"}', '{}'),
('default', 'quest_complete', '任务完成', '玩家完成任务事件', '{"quest_id":"任务ID","quest_name":"任务名称"}', '{"reward":"奖励"}'),
('default', 'item_purchase', '物品购买', '玩家购买商店物品事件', '{"item_id":"物品ID","item_name":"物品名称","price":"价格"}', '{"quantity":"数量"}'),
('default', 'social_interaction', '社交互动', '玩家社交行为事件', '{"interaction_type":"互动类型","target_player_id":"目标玩家ID"}', '{}'),
('default', 'game_start', '游戏开始', '玩家开始一局游戏事件', '{}', '{"game_mode":"游戏模式"}'),
('default', 'game_end', '游戏结束', '玩家结束一局游戏事件', '{}', '{"game_result":"游戏结果","duration":"游戏时长"}');
