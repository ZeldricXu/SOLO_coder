CREATE DATABASE IF NOT EXISTS ad_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ad_platform;

CREATE TABLE IF NOT EXISTS ad_info (
    ad_id VARCHAR(50) PRIMARY KEY,
    ad_name VARCHAR(200) NOT NULL,
    ad_type VARCHAR(50) NOT NULL,
    ad_content TEXT NOT NULL,
    ad_status VARCHAR(50) NOT NULL,
    advertiser VARCHAR(100) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ad_status (ad_status),
    INDEX idx_advertiser (advertiser)
);

CREATE TABLE IF NOT EXISTS ad_placement (
    placement_id VARCHAR(50) PRIMARY KEY,
    ad_id VARCHAR(50) NOT NULL,
    placement_channel VARCHAR(100) NOT NULL,
    placement_position VARCHAR(100) NOT NULL,
    placement_start DATETIME NOT NULL,
    placement_end DATETIME NOT NULL,
    placement_status VARCHAR(50) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ad_id (ad_id),
    INDEX idx_placement_status (placement_status)
);

CREATE TABLE IF NOT EXISTS ad_target (
    target_id VARCHAR(50) PRIMARY KEY,
    ad_id VARCHAR(50) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_conditions JSON NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ad_id (ad_id)
);

CREATE TABLE IF NOT EXISTS ad_budget (
    budget_id VARCHAR(50) PRIMARY KEY,
    ad_id VARCHAR(50) NOT NULL,
    budget_type VARCHAR(50) NOT NULL,
    budget_amount DECIMAL(12, 2) NOT NULL,
    budget_consumed DECIMAL(12, 2) DEFAULT 0,
    budget_remaining DECIMAL(12, 2) NOT NULL,
    budget_threshold DECIMAL(12, 2) DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ad_id (ad_id)
);

CREATE TABLE IF NOT EXISTS ad_effect (
    effect_id VARCHAR(50) PRIMARY KEY,
    ad_id VARCHAR(50) NOT NULL,
    stat_date DATE NOT NULL,
    exposure_count BIGINT DEFAULT 0,
    click_count BIGINT DEFAULT 0,
    click_rate DECIMAL(10, 4) DEFAULT 0,
    conversion_count BIGINT DEFAULT 0,
    conversion_rate DECIMAL(10, 4) DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ad_id (ad_id),
    INDEX idx_stat_date (stat_date)
);

CREATE TABLE IF NOT EXISTS ad_consume (
    consume_id VARCHAR(50) PRIMARY KEY,
    ad_id VARCHAR(50) NOT NULL,
    consume_type VARCHAR(50) NOT NULL,
    consume_amount DECIMAL(12, 2) NOT NULL,
    consume_time DATETIME NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ad_id (ad_id),
    INDEX idx_consume_time (consume_time)
);

CREATE TABLE IF NOT EXISTS ad_report (
    report_id VARCHAR(50) PRIMARY KEY,
    ad_id VARCHAR(50) NOT NULL,
    report_type VARCHAR(50) NOT NULL,
    report_data JSON NOT NULL,
    generated_at DATETIME NOT NULL,
    INDEX idx_ad_id (ad_id),
    INDEX idx_generated_at (generated_at)
);

CREATE TABLE IF NOT EXISTS ad_history (
    history_id VARCHAR(50) PRIMARY KEY,
    ad_id VARCHAR(50) NOT NULL,
    history_type VARCHAR(100) NOT NULL,
    history_data JSON NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ad_id (ad_id),
    INDEX idx_history_type (history_type),
    INDEX idx_created_at (created_at)
);
