-- Add sys_config table for working hours and other system configurations
CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_key VARCHAR(128) NOT NULL COMMENT '配置key',
    config_value VARCHAR(512) DEFAULT NULL COMMENT '配置value',
    description VARCHAR(256) DEFAULT NULL COMMENT '描述',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置表';

-- Insert default working hours configuration
INSERT INTO sys_config (config_key, config_value, description) VALUES
    ('working_hours.start', '09:00', '工作日开始时间'),
    ('working_hours.end', '18:00', '工作日结束时间'),
    ('working_hours.weekends', '6,7', '周末（1=周一，7=周日）')
ON DUPLICATE KEY UPDATE update_time = CURRENT_TIMESTAMP;
