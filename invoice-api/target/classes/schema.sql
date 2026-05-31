CREATE DATABASE IF NOT EXISTS invoice_mgmt DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE invoice_mgmt;

CREATE TABLE IF NOT EXISTS t_invoice_type (
    type_id VARCHAR(64) NOT NULL PRIMARY KEY,
    type_code VARCHAR(32) NOT NULL UNIQUE,
    type_name VARCHAR(100) NOT NULL,
    tax_rate DECIMAL(5,4) NOT NULL,
    enabled TINYINT(1) DEFAULT 1,
    description VARCHAR(500),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_type_code (type_code),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_invoice_number (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_type VARCHAR(32) NOT NULL,
    invoice_code VARCHAR(32) NOT NULL,
    start_no VARCHAR(32) NOT NULL,
    end_no VARCHAR(32) NOT NULL,
    current_no VARCHAR(32) NOT NULL,
    total_count INT NOT NULL,
    used_count INT DEFAULT 0,
    remaining_count INT NOT NULL,
    status VARCHAR(16) DEFAULT 'active',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_type (invoice_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_invoice (
    invoice_id VARCHAR(64) NOT NULL PRIMARY KEY,
    invoice_type VARCHAR(32) NOT NULL,
    invoice_no VARCHAR(32) NOT NULL,
    invoice_code VARCHAR(32) NOT NULL,
    buyer_name VARCHAR(200) NOT NULL,
    buyer_tax_no VARCHAR(50),
    seller_name VARCHAR(200) NOT NULL,
    seller_tax_no VARCHAR(50),
    invoice_amount DECIMAL(15,2) NOT NULL,
    tax_amount DECIMAL(15,2) NOT NULL,
    total_amount DECIMAL(15,2) NOT NULL,
    invoice_status VARCHAR(32) NOT NULL,
    issue_time DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_no_code (invoice_no, invoice_code),
    INDEX idx_status (invoice_status),
    INDEX idx_type (invoice_type),
    INDEX idx_issue_time (issue_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_invoice_status_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_id VARCHAR(64) NOT NULL,
    previous_status VARCHAR(32),
    current_status VARCHAR(32) NOT NULL,
    operator VARCHAR(64),
    remark VARCHAR(500),
    change_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_invoice_id (invoice_id),
    INDEX idx_change_time (change_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_invoice_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_id VARCHAR(64) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    action_content VARCHAR(1000),
    operator VARCHAR(64),
    action_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_invoice_id (invoice_id),
    INDEX idx_action_type (action_type),
    INDEX idx_action_time (action_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_invoice_archive (
    archive_id VARCHAR(64) NOT NULL PRIMARY KEY,
    invoice_id VARCHAR(64) NOT NULL,
    archive_type VARCHAR(16) NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    file_name VARCHAR(200),
    file_size BIGINT,
    md5 VARCHAR(32),
    archived_by VARCHAR(64),
    archived_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_invoice_id (invoice_id),
    INDEX idx_archive_type (archive_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_invoice_verify (
    verify_id VARCHAR(64) NOT NULL PRIMARY KEY,
    invoice_id VARCHAR(64),
    verify_type VARCHAR(16) NOT NULL,
    verify_result VARCHAR(16) NOT NULL,
    verify_source VARCHAR(32),
    verify_detail VARCHAR(500),
    verified_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_invoice_id (invoice_id),
    INDEX idx_verify_result (verify_result),
    INDEX idx_verified_at (verified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_invoice_reimburse (
    reimburse_id VARCHAR(64) NOT NULL PRIMARY KEY,
    invoice_id VARCHAR(64) NOT NULL,
    reimburse_user VARCHAR(64) NOT NULL,
    reimburse_department VARCHAR(100),
    reimburse_amount DECIMAL(15,2) NOT NULL,
    reimburse_reason VARCHAR(500),
    reimburse_status VARCHAR(16) NOT NULL,
    approver VARCHAR(64),
    approve_remark VARCHAR(500),
    apply_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    approve_time DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_invoice_id (invoice_id),
    INDEX idx_user (reimburse_user),
    INDEX idx_status (reimburse_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_invoice_statistics (
    stat_id VARCHAR(64) NOT NULL PRIMARY KEY,
    stat_month VARCHAR(7) NOT NULL UNIQUE,
    issue_count INT DEFAULT 0,
    total_amount DECIMAL(18,2) DEFAULT 0,
    total_tax DECIMAL(18,2) DEFAULT 0,
    verify_count INT DEFAULT 0,
    verify_pass_count INT DEFAULT 0,
    reimburse_count INT DEFAULT 0,
    reimburse_approve_count INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_month (stat_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_invoice_type_statistics (
    stat_id VARCHAR(64) NOT NULL PRIMARY KEY,
    stat_day VARCHAR(8) NOT NULL,
    invoice_type VARCHAR(32) NOT NULL,
    issue_count INT DEFAULT 0,
    total_amount DECIMAL(18,2) DEFAULT 0,
    total_tax DECIMAL(18,2) DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_day_type (stat_day, invoice_type),
    INDEX idx_invoice_type (invoice_type),
    INDEX idx_stat_day (stat_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO t_invoice_type (type_id, type_code, type_name, tax_rate, enabled, description) VALUES
('type_vat_sp', 'vat_special', '增值税专用发票', 0.13, 1, '一般纳税人适用，可抵扣进项税'),
('type_vat_cm', 'vat_common', '增值税普通发票', 0.13, 1, '普通发票，不可抵扣'),
('type_vat_el', 'vat_electronic', '增值税电子普通发票', 0.13, 1, '电子形式的增值税发票'),
('type_vat_06', 'vat_6pct', '增值税6%税率发票', 0.06, 1, '现代服务业6%税率发票'),
('type_vat_09', 'vat_9pct', '增值税9%税率发票', 0.09, 1, '建筑、交通业9%税率发票'),
('type_vat_zr', 'vat_zero', '增值税零税率发票', 0.00, 1, '出口货物、跨境服务零税率');

INSERT IGNORE INTO t_invoice_number (invoice_type, invoice_code, start_no, end_no, current_no, total_count, used_count, remaining_count, status) VALUES
('vat_special', '1100', '00000001', '00009999', '00000001', 9999, 0, 9999, 'active'),
('vat_common', '1101', '00000001', '00009999', '00000001', 9999, 0, 9999, 'active'),
('vat_electronic', '1102', '00000001', '00009999', '00000001', 9999, 0, 9999, 'active'),
('vat_6pct', '1106', '00000001', '00009999', '00000001', 9999, 0, 9999, 'active'),
('vat_9pct', '1109', '00000001', '00009999', '00000001', 9999, 0, 9999, 'active'),
('vat_zero', '1100', '00000001', '00009999', '00000001', 9999, 0, 9999, 'active');
