CREATE TABLE IF NOT EXISTS assets (
    asset_id VARCHAR(64) PRIMARY KEY,
    asset_name VARCHAR(255) NOT NULL,
    asset_type VARCHAR(100) NOT NULL,
    asset_category VARCHAR(100),
    asset_model VARCHAR(255),
    asset_sn VARCHAR(100),
    purchase_date DATE,
    purchase_price DECIMAL(18,2),
    current_value DECIMAL(18,2),
    depreciation_method VARCHAR(50),
    depreciation_rate DECIMAL(10,4),
    useful_life INT,
    accumulated_depreciation DECIMAL(18,2),
    asset_status VARCHAR(50),
    location VARCHAR(255),
    department VARCHAR(100),
    current_user_id VARCHAR(64),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS usage_records (
    usage_id VARCHAR(64) PRIMARY KEY,
    asset_id VARCHAR(64),
    user_id VARCHAR(64),
    usage_type VARCHAR(50),
    usage_start TIMESTAMP,
    expected_return DATE,
    actual_return TIMESTAMP,
    usage_status VARCHAR(50),
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS maintenance_records (
    maintenance_id VARCHAR(64) PRIMARY KEY,
    asset_id VARCHAR(64),
    maintenance_type VARCHAR(50),
    maintenance_date DATE,
    maintenance_content TEXT,
    maintenance_cost DECIMAL(18,2),
    next_maintenance DATE,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS depreciation_records (
    depreciation_id VARCHAR(64) PRIMARY KEY,
    asset_id VARCHAR(64),
    depreciation_period VARCHAR(20),
    depreciation_value DECIMAL(18,2),
    accumulated_depreciation DECIMAL(18,2),
    current_value DECIMAL(18,2),
    calculated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS inventory_checks (
    check_id VARCHAR(64) PRIMARY KEY,
    check_type VARCHAR(50),
    check_department VARCHAR(100),
    check_status VARCHAR(50),
    total_assets INT,
    checked_assets INT,
    matched_assets INT,
    diff_assets INT,
    checked_at TIMESTAMP,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS inventory_differences (
    diff_id VARCHAR(64) PRIMARY KEY,
    check_id VARCHAR(64),
    asset_id VARCHAR(64),
    system_location VARCHAR(255),
    actual_location VARCHAR(255),
    diff_type VARCHAR(50),
    diff_status VARCHAR(50),
    handled_at TIMESTAMP,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS scrap_records (
    scrap_id VARCHAR(64) PRIMARY KEY,
    asset_id VARCHAR(64),
    scrap_reason TEXT,
    scrap_status VARCHAR(50),
    residual_value DECIMAL(18,2),
    scrap_time TIMESTAMP,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS asset_statistics (
    stat_id VARCHAR(64) PRIMARY KEY,
    stat_date DATE,
    total_assets INT,
    in_use_assets INT,
    idle_assets INT,
    maintenance_assets INT,
    scraped_assets INT,
    total_value DECIMAL(18,2),
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS asset_history (
    history_id VARCHAR(64) PRIMARY KEY,
    asset_id VARCHAR(64),
    action_type VARCHAR(50),
    action_details TEXT,
    operator_id VARCHAR(64),
    created_at TIMESTAMP
);
