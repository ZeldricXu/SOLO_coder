CREATE TABLE IF NOT EXISTS core_entities (
    id VARCHAR(64) PRIMARY KEY,
    type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attributes JSON,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_type (type),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS config_definitions (
    config_id VARCHAR(64) PRIMARY KEY,
    namespace VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    parameters JSON,
    enabled BOOLEAN DEFAULT true,
    applied_at DATETIME NOT NULL,
    INDEX idx_namespace (namespace),
    INDEX idx_version (version),
    UNIQUE KEY uk_namespace_version (namespace, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS run_instances (
    run_id VARCHAR(64) PRIMARY KEY,
    entity_id VARCHAR(64) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    progress DOUBLE DEFAULT 0,
    started_at DATETIME NOT NULL,
    completed_at DATETIME,
    error_detail TEXT,
    INDEX idx_entity_id (entity_id),
    INDEX idx_phase (phase),
    INDEX idx_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS metric_snapshots (
    snapshot_id VARCHAR(64) PRIMARY KEY,
    timestamp DATETIME NOT NULL,
    metrics JSON,
    dimensions JSON,
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS multisig_proposals (
    proposal_id VARCHAR(64) PRIMARY KEY,
    wallet_id VARCHAR(64) NOT NULL,
    chain_id VARCHAR(32) NOT NULL,
    transaction_data LONGTEXT NOT NULL,
    required_signatures INT NOT NULL,
    current_signatures INT DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    proposer VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL,
    executed_at DATETIME,
    expires_at DATETIME,
    INDEX idx_wallet_id (wallet_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS multisig_signatures (
    signature_id VARCHAR(64) PRIMARY KEY,
    proposal_id VARCHAR(64) NOT NULL,
    signer_address VARCHAR(128) NOT NULL,
    signature_data LONGTEXT NOT NULL,
    signed_at DATETIME NOT NULL,
    INDEX idx_proposal_id (proposal_id),
    INDEX idx_signer (signer_address),
    UNIQUE KEY uk_proposal_signer (proposal_id, signer_address)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS storage_records (
    record_id VARCHAR(64) PRIMARY KEY,
    storage_type VARCHAR(32) NOT NULL,
    content_hash VARCHAR(256) NOT NULL,
    content_url VARCHAR(512),
    pin_status VARCHAR(32) NOT NULL,
    size BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    pinned_at DATETIME,
    metadata JSON,
    INDEX idx_storage_type (storage_type),
    INDEX idx_content_hash (content_hash),
    INDEX idx_pin_status (pin_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS indexed_blocks (
    block_id VARCHAR(64) PRIMARY KEY,
    chain_id VARCHAR(32) NOT NULL,
    block_number BIGINT NOT NULL,
    block_hash VARCHAR(128) NOT NULL,
    parent_hash VARCHAR(128),
    timestamp DATETIME NOT NULL,
    transaction_count INT DEFAULT 0,
    raw_data LONGTEXT,
    indexed_at DATETIME NOT NULL,
    INDEX idx_chain_number (chain_id, block_number),
    INDEX idx_block_hash (block_hash),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS indexed_transactions (
    tx_id VARCHAR(64) PRIMARY KEY,
    chain_id VARCHAR(32) NOT NULL,
    block_number BIGINT NOT NULL,
    tx_hash VARCHAR(128) NOT NULL,
    from_address VARCHAR(128),
    to_address VARCHAR(128),
    value DECIMAL(38, 0),
    gas_used BIGINT,
    gas_price BIGINT,
    status VARCHAR(32),
    input_data LONGTEXT,
    indexed_at DATETIME NOT NULL,
    INDEX idx_chain_block (chain_id, block_number),
    INDEX idx_tx_hash (tx_hash),
    INDEX idx_from_address (from_address),
    INDEX idx_to_address (to_address)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS contract_event_listeners (
    listener_id VARCHAR(64) PRIMARY KEY,
    chain_id VARCHAR(32) NOT NULL,
    contract_address VARCHAR(128) NOT NULL,
    event_signature VARCHAR(256) NOT NULL,
    callback_url VARCHAR(512) NOT NULL,
    start_block BIGINT,
    status VARCHAR(32) NOT NULL,
    last_processed_block BIGINT,
    created_at DATETIME NOT NULL,
    INDEX idx_chain_contract (chain_id, contract_address),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS event_logs (
    log_id VARCHAR(64) PRIMARY KEY,
    chain_id VARCHAR(32) NOT NULL,
    block_number BIGINT NOT NULL,
    tx_hash VARCHAR(128) NOT NULL,
    log_index INT NOT NULL,
    contract_address VARCHAR(128) NOT NULL,
    event_signature VARCHAR(256),
    topics JSON,
    data LONGTEXT,
    processed BOOLEAN DEFAULT false,
    processed_at DATETIME,
    INDEX idx_chain_block (chain_id, block_number),
    INDEX idx_contract (contract_address),
    INDEX idx_processed (processed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS zkp_proofs (
    proof_id VARCHAR(64) PRIMARY KEY,
    circuit_id VARCHAR(64) NOT NULL,
    proof_data LONGTEXT NOT NULL,
    public_inputs JSON,
    verification_key JSON,
    verification_result BOOLEAN,
    verified_at DATETIME,
    created_at DATETIME NOT NULL,
    error_message VARCHAR(1024),
    INDEX idx_circuit_id (circuit_id),
    INDEX idx_verification_result (verification_result)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS gas_estimates (
    estimate_id VARCHAR(64) PRIMARY KEY,
    chain_id VARCHAR(32) NOT NULL,
    transaction_type VARCHAR(64) NOT NULL,
    estimated_gas BIGINT NOT NULL,
    gas_price_low BIGINT,
    gas_price_medium BIGINT,
    gas_price_high BIGINT,
    priority_fee_low BIGINT,
    priority_fee_medium BIGINT,
    priority_fee_high BIGINT,
    confidence_level DOUBLE,
    historical_data JSON,
    created_at DATETIME NOT NULL,
    INDEX idx_chain_type (chain_id, transaction_type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS constructed_transactions (
    tx_id VARCHAR(64) PRIMARY KEY,
    chain_id VARCHAR(32) NOT NULL,
    from_address VARCHAR(128) NOT NULL,
    to_address VARCHAR(128),
    value DECIMAL(38, 0) DEFAULT 0,
    gas_limit BIGINT,
    gas_price BIGINT,
    nonce BIGINT,
    data LONGTEXT,
    signed_tx LONGTEXT,
    multisig_wallet_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    tx_hash VARCHAR(128),
    submitted_at DATETIME,
    created_at DATETIME NOT NULL,
    INDEX idx_chain_id (chain_id),
    INDEX idx_from_address (from_address),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chain_nodes (
    node_id VARCHAR(64) PRIMARY KEY,
    chain_id VARCHAR(32) NOT NULL,
    rpc_url VARCHAR(512) NOT NULL,
    ws_url VARCHAR(512),
    status VARCHAR(32) NOT NULL,
    priority INT DEFAULT 0,
    latency BIGINT,
    last_checked DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_chain_id (chain_id),
    INDEX idx_status (status),
    UNIQUE KEY uk_chain_rpc_url (chain_id, rpc_url)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
