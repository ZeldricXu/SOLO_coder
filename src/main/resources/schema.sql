CREATE DATABASE IF NOT EXISTS mailservice CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE mailservice;

CREATE TABLE IF NOT EXISTS mail_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mail_id VARCHAR(64) NOT NULL UNIQUE,
    mail_type VARCHAR(16) NOT NULL,
    sender VARCHAR(255) NOT NULL,
    recipients TEXT NOT NULL,
    subject VARCHAR(500),
    content LONGTEXT,
    attachments TEXT,
    mail_status VARCHAR(32),
    category VARCHAR(64),
    sent_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_mail_id (mail_id),
    INDEX idx_mail_type (mail_type),
    INDEX idx_mail_status (mail_status),
    INDEX idx_category (category),
    INDEX idx_sent_at (sent_at),
    FULLTEXT INDEX ft_search (subject, content)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS category_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL UNIQUE,
    rule_name VARCHAR(255) NOT NULL,
    rule_pattern TEXT NOT NULL,
    target_category VARCHAR(64) NOT NULL,
    rule_priority INT DEFAULT 0,
    dynamic_priority INT DEFAULT 0,
    match_count INT DEFAULT 0,
    last_matched_at DATETIME,
    enabled BOOLEAN DEFAULT TRUE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_rule_id (rule_id),
    INDEX idx_enabled (enabled),
    INDEX idx_priority (rule_priority),
    INDEX idx_dynamic_priority (dynamic_priority),
    INDEX idx_match_count (match_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS archive_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    archive_id VARCHAR(64) NOT NULL UNIQUE,
    mail_id VARCHAR(64) NOT NULL,
    category VARCHAR(64) NOT NULL,
    archive_time DATETIME NOT NULL,
    archive_status VARCHAR(32) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_archive_id (archive_id),
    INDEX idx_mail_id (mail_id),
    INDEX idx_category (category),
    INDEX idx_archive_time (archive_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS send_status (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    status_id VARCHAR(64) NOT NULL UNIQUE,
    mail_id VARCHAR(64) NOT NULL,
    send_status VARCHAR(32) NOT NULL,
    smtp_response TEXT,
    error_message TEXT,
    send_attempts INT DEFAULT 1,
    last_attempt DATETIME NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status_id (status_id),
    INDEX idx_mail_id (mail_id),
    INDEX idx_send_status (send_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mail_statistics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stat_id VARCHAR(64) NOT NULL UNIQUE,
    stat_date DATE NOT NULL,
    sent_count INT DEFAULT 0,
    received_count INT DEFAULT 0,
    failed_count INT DEFAULT 0,
    avg_response_time INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_stat_id (stat_id),
    INDEX idx_stat_date (stat_date),
    UNIQUE INDEX uk_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mail_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id VARCHAR(64) NOT NULL UNIQUE,
    template_name VARCHAR(255) NOT NULL,
    template_subject VARCHAR(500),
    template_content TEXT NOT NULL,
    variables TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_template_id (template_id),
    INDEX idx_template_name (template_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mail_attachment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    attachment_id VARCHAR(64) NOT NULL UNIQUE,
    mail_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT DEFAULT 0,
    content_type VARCHAR(128),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_attachment_id (attachment_id),
    INDEX idx_mail_id (mail_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mail_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    history_id VARCHAR(64) NOT NULL UNIQUE,
    mail_id VARCHAR(64) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    action_details TEXT,
    actor VARCHAR(128),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_history_id (history_id),
    INDEX idx_mail_id (mail_id),
    INDEX idx_action_type (action_type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO category_rule (rule_id, rule_name, rule_pattern, target_category, rule_priority, dynamic_priority, match_count, enabled)
SELECT * FROM (SELECT
    'rule_urgent_001' AS rule_id,
    '紧急邮件' AS rule_name,
    '(?i)(urgent|紧急|重要|high priority|priority: high)' AS rule_pattern,
    'urgent' AS target_category,
    100 AS rule_priority,
    100 AS dynamic_priority,
    0 AS match_count,
    TRUE AS enabled
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM category_rule WHERE rule_id = 'rule_urgent_001');

INSERT INTO category_rule (rule_id, rule_name, rule_pattern, target_category, rule_priority, dynamic_priority, match_count, enabled)
SELECT * FROM (SELECT
    'rule_work_001' AS rule_id,
    '工作邮件' AS rule_name,
    '(?i)(project|work|meeting|项目|会议|工作|报告|report|memo)' AS rule_pattern,
    'work' AS target_category,
    80 AS rule_priority,
    80 AS dynamic_priority,
    0 AS match_count,
    TRUE AS enabled
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM category_rule WHERE rule_id = 'rule_work_001');

INSERT INTO category_rule (rule_id, rule_name, rule_pattern, target_category, rule_priority, dynamic_priority, match_count, enabled)
SELECT * FROM (SELECT
    'rule_notification_001' AS rule_id,
    '通知邮件' AS rule_name,
    '(?i)(notification|通知|公告|notice|alert)' AS rule_pattern,
    'notification' AS target_category,
    60 AS rule_priority,
    60 AS dynamic_priority,
    0 AS match_count,
    TRUE AS enabled
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM category_rule WHERE rule_id = 'rule_notification_001');

INSERT INTO category_rule (rule_id, rule_name, rule_pattern, target_category, rule_priority, dynamic_priority, match_count, enabled)
SELECT * FROM (SELECT
    'rule_promotion_001' AS rule_id,
    '促销邮件' AS rule_name,
    '(?i)(discount|sale|促销|优惠|特价|special offer|coupon)' AS rule_pattern,
    'promotion' AS target_category,
    30 AS rule_priority,
    30 AS dynamic_priority,
    0 AS match_count,
    TRUE AS enabled
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM category_rule WHERE rule_id = 'rule_promotion_001');

INSERT INTO category_rule (rule_id, rule_name, rule_pattern, target_category, rule_priority, dynamic_priority, match_count, enabled)
SELECT * FROM (SELECT
    'rule_social_001' AS rule_id,
    '社交邮件' AS rule_name,
    '(?i)(social|facebook|twitter|linkedin|社交|好友|邀请|invite|friend)' AS rule_pattern,
    'social' AS target_category,
    20 AS rule_priority,
    20 AS dynamic_priority,
    0 AS match_count,
    TRUE AS enabled
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM category_rule WHERE rule_id = 'rule_social_001');

INSERT INTO category_rule (rule_id, rule_name, rule_pattern, target_category, rule_priority, dynamic_priority, match_count, enabled)
SELECT * FROM (SELECT
    'rule_spam_001' AS rule_id,
    '垃圾邮件' AS rule_name,
    '(?i)(spam|垃圾|unsubscribe|newsletter)' AS rule_pattern,
    'spam' AS target_category,
    10 AS rule_priority,
    10 AS dynamic_priority,
    0 AS match_count,
    TRUE AS enabled
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM category_rule WHERE rule_id = 'rule_spam_001');
