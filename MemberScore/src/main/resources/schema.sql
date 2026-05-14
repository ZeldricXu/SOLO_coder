CREATE DATABASE IF NOT EXISTS memberscore DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE memberscore;

CREATE TABLE IF NOT EXISTS members (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id VARCHAR(50) NOT NULL UNIQUE,
    user_id VARCHAR(50) NOT NULL UNIQUE,
    member_level VARCHAR(20) NOT NULL DEFAULT 'bronze',
    total_points INT NOT NULL DEFAULT 0,
    available_points INT NOT NULL DEFAULT 0,
    used_points INT NOT NULL DEFAULT 0,
    member_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    registered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    level_updated_at DATETIME NULL,
    INDEX idx_member_id (member_id),
    INDEX idx_user_id (user_id),
    INDEX idx_member_status (member_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS point_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    point_id VARCHAR(50) NOT NULL UNIQUE,
    member_id VARCHAR(50) NOT NULL,
    point_type VARCHAR(20) NOT NULL,
    point_amount INT NOT NULL,
    point_source VARCHAR(50) NULL,
    consume_type VARCHAR(50) NULL,
    point_balance INT NOT NULL,
    expire_at DATE NULL,
    is_expired BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_point_id (point_id),
    INDEX idx_member_id (member_id),
    INDEX idx_point_type (point_type),
    INDEX idx_created_at (created_at),
    INDEX idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS level_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    level_id VARCHAR(50) NOT NULL UNIQUE,
    level_name VARCHAR(50) NOT NULL,
    level_points_required INT NOT NULL DEFAULT 0,
    level_benefits TEXT NULL,
    level_order INT NOT NULL DEFAULT 0,
    point_multiplier DOUBLE NOT NULL DEFAULT 1.0,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    INDEX idx_level_id (level_id),
    INDEX idx_level_order (level_order),
    INDEX idx_is_enabled (is_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS benefit_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    benefit_id VARCHAR(50) NOT NULL UNIQUE,
    member_id VARCHAR(50) NOT NULL,
    level_id VARCHAR(50) NULL,
    benefit_type VARCHAR(50) NOT NULL,
    benefit_content VARCHAR(500) NOT NULL,
    benefit_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    issued_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_at DATETIME NULL,
    INDEX idx_benefit_id (benefit_id),
    INDEX idx_member_id (member_id),
    INDEX idx_level_id (level_id),
    INDEX idx_benefit_status (benefit_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS point_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id VARCHAR(50) NOT NULL UNIQUE,
    rule_name VARCHAR(100) NOT NULL,
    rule_type VARCHAR(50) NOT NULL,
    rule_points INT NOT NULL DEFAULT 0,
    rule_multiplier DOUBLE NOT NULL DEFAULT 1.0,
    rule_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    rule_description VARCHAR(500) NULL,
    start_date DATETIME NULL,
    end_date DATETIME NULL,
    validation_rule_id VARCHAR(50) NULL,
    expire_policy_id VARCHAR(50) NULL,
    rule_config TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL,
    INDEX idx_rule_id (rule_id),
    INDEX idx_rule_type (rule_type),
    INDEX idx_rule_enabled (rule_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS point_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stat_id VARCHAR(50) NOT NULL UNIQUE,
    stat_date DATE NOT NULL UNIQUE,
    earn_count INT NOT NULL DEFAULT 0,
    earn_points INT NOT NULL DEFAULT 0,
    consume_count INT NOT NULL DEFAULT 0,
    consume_points INT NOT NULL DEFAULT 0,
    INDEX idx_stat_id (stat_id),
    INDEX idx_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS validation_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id VARCHAR(50) NOT NULL UNIQUE,
    rule_name VARCHAR(100) NOT NULL,
    validation_type VARCHAR(30) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    min_amount INT NULL,
    max_amount INT NULL,
    amount_factor DOUBLE NULL DEFAULT 1.0,
    fixed_points INT NULL,
    time_window_minutes INT NULL,
    max_points_per_window INT NULL,
    validation_config TEXT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL,
    INDEX idx_rule_id (rule_id),
    INDEX idx_source_type (source_type),
    INDEX idx_validation_type (validation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS expire_policy_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    policy_id VARCHAR(50) NOT NULL UNIQUE,
    policy_name VARCHAR(100) NOT NULL,
    policy_type VARCHAR(30) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    fixed_expire_days INT NULL,
    flexible_base_days INT NULL,
    flexible_max_days INT NULL,
    level_expire_config TEXT NULL,
    point_threshold INT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NULL,
    INDEX idx_policy_id (policy_id),
    INDEX idx_policy_type (policy_type),
    INDEX idx_is_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS benefit_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(50) NOT NULL UNIQUE,
    member_id VARCHAR(50) NOT NULL,
    level_id VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 3,
    error_message TEXT NULL,
    task_data TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at DATETIME NULL,
    completed_at DATETIME NULL,
    next_retry_at DATETIME NULL,
    INDEX idx_task_id (task_id),
    INDEX idx_member_id (member_id),
    INDEX idx_status (status),
    INDEX idx_next_retry_at (next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO expire_policy_configs (policy_id, policy_name, policy_type, is_default, fixed_expire_days, flexible_base_days, flexible_max_days, level_expire_config, point_threshold, is_enabled, description) VALUES
('exp_fixed_365', '固定365天过期', 'FIXED_TERM', FALSE, 365, NULL, NULL, NULL, NULL, TRUE, '固定期限策略：所有积分365天后过期'),
('exp_flexible', '灵活期限', 'FLEXIBLE_TERM', FALSE, NULL, 180, 365, NULL, 1000, TRUE, '灵活期限策略：积分越多，有效期越长(180-365天)'),
('exp_level_diff', '等级差异期限', 'LEVEL_DIFFERENCE', TRUE, 365, NULL, NULL, '{"bronze":180,"silver":270,"gold":365,"platinum":730}', NULL, TRUE, '等级差异策略：青铜180天、白银270天、黄金365天、铂金730天'),
('exp_never', '永不过期', 'NEVER_EXPIRE', FALSE, NULL, NULL, NULL, NULL, NULL, TRUE, '永久有效策略：积分永不过期');

INSERT INTO validation_rules (rule_id, rule_name, validation_type, source_type, min_amount, max_amount, amount_factor, fixed_points, time_window_minutes, max_points_per_window, is_enabled, description) VALUES
('val_purchase', '购物积分校验规则', 'AMOUNT_RELATED', 'purchase', 1, 100000, 1.0, NULL, NULL, NULL, TRUE, '购物积分：金额关联校验，1元=1积分，最低1元最高10万元'),
('val_sign', '签到积分校验规则', 'FIXED_AMOUNT', 'sign', NULL, NULL, NULL, 10, NULL, NULL, TRUE, '签到积分：固定10积分/天'),
('val_share', '分享积分校验规则', 'FIXED_AMOUNT', 'share', NULL, NULL, NULL, 20, 1440, 200, TRUE, '分享积分：固定20积分/次，每日最多200积分'),
('val_comment', '评价积分校验规则', 'FIXED_AMOUNT', 'comment', NULL, NULL, NULL, 15, NULL, NULL, TRUE, '评价积分：固定15积分/条'),
('val_promotion', '活动积分校验规则', 'TIME_RELATED', 'promotion', NULL, NULL, NULL, NULL, 60, 1000, TRUE, '活动积分：时间窗口校验，每小时最多1000积分');

INSERT INTO level_configs (level_id, level_name, level_points_required, level_benefits, level_order, point_multiplier, is_enabled) VALUES
('bronze', '青铜会员', 0, '[{"type":"birthday","content":"生日双倍积分"},{"type":"general","content":"基础会员权益"}]', 1, 1.0, TRUE),
('silver', '白银会员', 1000, '[{"type":"birthday","content":"生日双倍积分"},{"type":"discount","content":"购物折扣5%"},{"type":"service","content":"优先客服"}]', 2, 1.2, TRUE),
('gold', '黄金会员', 3000, '[{"type":"birthday","content":"生日三倍积分"},{"type":"discount","content":"购物折扣10%"},{"type":"service","content":"专属客服"},{"type":"points","content":"积分加倍"}]', 3, 1.5, TRUE),
('platinum', '铂金会员', 10000, '[{"type":"birthday","content":"生日五倍积分"},{"type":"discount","content":"购物折扣15%"},{"type":"service","content":"VIP专属客服"},{"type":"points","content":"积分翻倍"},{"type":"gift","content":"年度礼品"}]', 4, 2.0, TRUE);

INSERT INTO point_rules (rule_id, rule_name, rule_type, rule_points, rule_multiplier, rule_enabled, validation_rule_id, expire_policy_id, rule_description) VALUES
('rule_purchase', '购物积分规则', 'purchase', 1, 1.0, TRUE, 'val_purchase', 'exp_level_diff', '每消费1元获得1积分'),
('rule_sign', '签到积分规则', 'sign', 10, 1.0, TRUE, 'val_sign', 'exp_level_diff', '每日签到获得10积分'),
('rule_share', '分享积分规则', 'share', 20, 1.0, TRUE, 'val_share', 'exp_level_diff', '分享商品获得20积分'),
('rule_comment', '评价积分规则', 'comment', 15, 1.0, TRUE, 'val_comment', 'exp_level_diff', '商品评价获得15积分'),
('rule_promotion', '促销活动积分规则', 'promotion', 5, 2.0, TRUE, 'val_promotion', 'exp_flexible', '促销活动双倍积分');
