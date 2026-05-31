CREATE TABLE IF NOT EXISTS dm_key_shard (
    id VARCHAR(64) PRIMARY KEY,
    secret_id VARCHAR(128) NOT NULL,
    shard_index INT NOT NULL,
    shard_data TEXT NOT NULL,
    threshold INT NOT NULL,
    total_shares INT NOT NULL,
    owner VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dm_privacy_budget (
    id VARCHAR(64) PRIMARY KEY,
    query_id VARCHAR(128) NOT NULL,
    epsilon_consumed DOUBLE NOT NULL,
    delta_consumed DOUBLE NOT NULL,
    total_budget DOUBLE NOT NULL,
    remaining_budget DOUBLE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dm_mpc_session (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    protocol_type VARCHAR(64) NOT NULL,
    party_count INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_encrypted TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dm_federation_task (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(128) NOT NULL,
    round_number INT NOT NULL,
    participant_count INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    global_model_hash VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dm_tee_enclave (
    id VARCHAR(64) PRIMARY KEY,
    enclave_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attestation_report TEXT,
    measurement_hash VARCHAR(128),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dm_classification_result (
    id VARCHAR(64) PRIMARY KEY,
    data_source VARCHAR(256) NOT NULL,
    field_name VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL,
    level VARCHAR(32) NOT NULL,
    confidence DOUBLE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dm_audit_log (
    id VARCHAR(64) PRIMARY KEY,
    log_hash VARCHAR(128) NOT NULL,
    prev_hash VARCHAR(128),
    operation VARCHAR(128) NOT NULL,
    operator VARCHAR(128) NOT NULL,
    module VARCHAR(64) NOT NULL,
    detail TEXT,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dm_masking_rule (
    id VARCHAR(64) PRIMARY KEY,
    field_pattern VARCHAR(256) NOT NULL,
    strategy VARCHAR(64) NOT NULL,
    level_required VARCHAR(32) NOT NULL,
    params TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
