CREATE TABLE IF NOT EXISTS t_audit_log (
    id VARCHAR(64) PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64),
    module VARCHAR(64) NOT NULL,
    operation VARCHAR(128) NOT NULL,
    request_params TEXT,
    response_result TEXT,
    status VARCHAR(32) NOT NULL,
    error_message VARCHAR(1024),
    ip_address VARCHAR(64),
    user_agent VARCHAR(512),
    duration_ms BIGINT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_trace_id (trace_id),
    INDEX idx_module (module),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_zkp_proof (
    id VARCHAR(64) PRIMARY KEY,
    proof_id VARCHAR(128) NOT NULL UNIQUE,
    circuit_id VARCHAR(64) NOT NULL,
    proof_data LONGTEXT NOT NULL,
    public_inputs LONGTEXT,
    verify_result VARCHAR(32),
    verify_time_ms BIGINT,
    error_message VARCHAR(1024),
    status VARCHAR(32) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_proof_id (proof_id),
    INDEX idx_circuit_id (circuit_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_hd_wallet (
    id VARCHAR(64) PRIMARY KEY,
    wallet_id VARCHAR(64) NOT NULL UNIQUE,
    chain_type VARCHAR(32) NOT NULL,
    derivation_path VARCHAR(128) NOT NULL,
    address VARCHAR(128) NOT NULL,
    public_key VARCHAR(512),
    private_key_encrypted LONGTEXT,
    label VARCHAR(128),
    tags VARCHAR(512),
    user_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_wallet_id (wallet_id),
    INDEX idx_chain_type (chain_type),
    INDEX idx_address (address),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_address_book (
    id VARCHAR(64) PRIMARY KEY,
    address VARCHAR(128) NOT NULL,
    chain_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    label VARCHAR(128),
    tags VARCHAR(512),
    user_id VARCHAR(64),
    is_whitelist TINYINT DEFAULT 0,
    is_blacklist TINYINT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_address_chain (address, chain_type, user_id),
    INDEX idx_user_id (user_id),
    INDEX idx_chain_type (chain_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_block_index (
    id VARCHAR(64) PRIMARY KEY,
    chain_type VARCHAR(32) NOT NULL,
    block_number BIGINT NOT NULL,
    block_hash VARCHAR(128) NOT NULL,
    parent_hash VARCHAR(128),
    miner VARCHAR(128),
    timestamp BIGINT NOT NULL,
    transaction_count INT DEFAULT 0,
    gas_used VARCHAR(64),
    gas_limit VARCHAR(64),
    extra_data TEXT,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chain_block (chain_type, block_number),
    INDEX idx_block_hash (block_hash),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_transaction_index (
    id VARCHAR(64) PRIMARY KEY,
    chain_type VARCHAR(32) NOT NULL,
    block_number BIGINT NOT NULL,
    tx_hash VARCHAR(128) NOT NULL,
    tx_index INT NOT NULL,
    from_address VARCHAR(128),
    to_address VARCHAR(128),
    value VARCHAR(128),
    gas_price VARCHAR(64),
    gas_limit VARCHAR(64),
    gas_used VARCHAR(64),
    input_data LONGTEXT,
    status VARCHAR(32),
    contract_address VARCHAR(128),
    timestamp BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chain_tx (chain_type, tx_hash),
    INDEX idx_block_number (block_number),
    INDEX idx_from_address (from_address),
    INDEX idx_to_address (to_address),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_chain_rpc_node (
    id VARCHAR(64) PRIMARY KEY,
    chain_type VARCHAR(32) NOT NULL,
    rpc_url VARCHAR(256) NOT NULL,
    chain_id BIGINT,
    name VARCHAR(128),
    priority INT DEFAULT 0,
    is_active TINYINT DEFAULT 1,
    health_status VARCHAR(32),
    last_check_at DATETIME,
    latency_ms INT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_chain_type (chain_type),
    INDEX idx_is_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_storage_content (
    id VARCHAR(64) PRIMARY KEY,
    content_id VARCHAR(128) NOT NULL UNIQUE,
    storage_type VARCHAR(32) NOT NULL,
    cid VARCHAR(256) NOT NULL,
    content_hash VARCHAR(128),
    content_size BIGINT,
    pin_status VARCHAR(32),
    metadata LONGTEXT,
    user_id VARCHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_storage_type (storage_type),
    INDEX idx_cid (cid),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_multisig_wallet (
    id VARCHAR(64) PRIMARY KEY,
    wallet_id VARCHAR(64) NOT NULL UNIQUE,
    chain_type VARCHAR(32) NOT NULL,
    address VARCHAR(128) NOT NULL,
    threshold INT NOT NULL,
    signer_count INT NOT NULL,
    signers LONGTEXT NOT NULL,
    name VARCHAR(128),
    user_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_wallet_id (wallet_id),
    INDEX idx_chain_type (chain_type),
    INDEX idx_address (address)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_multisig_proposal (
    id VARCHAR(64) PRIMARY KEY,
    proposal_id VARCHAR(64) NOT NULL UNIQUE,
    wallet_id VARCHAR(64) NOT NULL,
    chain_type VARCHAR(32) NOT NULL,
    transaction_data LONGTEXT NOT NULL,
    to_address VARCHAR(128),
    value VARCHAR(128),
    data LONGTEXT,
    nonce VARCHAR(64),
    threshold INT NOT NULL,
    signatures LONGTEXT,
    signer_addresses LONGTEXT,
    signed_count INT DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    tx_hash VARCHAR(128),
    error_message VARCHAR(1024),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_proposal_id (proposal_id),
    INDEX idx_wallet_id (wallet_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_gas_estimate (
    id VARCHAR(64) PRIMARY KEY,
    chain_type VARCHAR(32) NOT NULL,
    priority_level VARCHAR(32) NOT NULL,
    gas_price VARCHAR(64),
    max_fee_per_gas VARCHAR(64),
    max_priority_fee_per_gas VARCHAR(64),
    base_fee VARCHAR(64),
    estimated_gas_limit VARCHAR(64),
    estimated_usd_cost DECIMAL(20, 10),
    timestamp BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chain_type (chain_type),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_gas_history (
    id VARCHAR(64) PRIMARY KEY,
    chain_type VARCHAR(32) NOT NULL,
    block_number BIGINT NOT NULL,
    base_fee VARCHAR(64),
    avg_gas_price VARCHAR(64),
    gas_used_ratio DECIMAL(10, 8),
    timestamp BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chain_block (chain_type, block_number),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_cross_chain_bridge (
    id VARCHAR(64) PRIMARY KEY,
    bridge_id VARCHAR(64) NOT NULL UNIQUE,
    source_chain VARCHAR(32) NOT NULL,
    target_chain VARCHAR(32) NOT NULL,
    asset_symbol VARCHAR(32) NOT NULL,
    asset_address VARCHAR(128),
    bridge_contract VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_source_chain (source_chain),
    INDEX idx_target_chain (target_chain)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_cross_chain_transfer (
    id VARCHAR(64) PRIMARY KEY,
    transfer_id VARCHAR(64) NOT NULL UNIQUE,
    bridge_id VARCHAR(64) NOT NULL,
    source_chain VARCHAR(32) NOT NULL,
    target_chain VARCHAR(32) NOT NULL,
    sender_address VARCHAR(128) NOT NULL,
    recipient_address VARCHAR(128) NOT NULL,
    amount VARCHAR(128) NOT NULL,
    asset_symbol VARCHAR(32) NOT NULL,
    source_tx_hash VARCHAR(128),
    target_tx_hash VARCHAR(128),
    message_proof LONGTEXT,
    status VARCHAR(32) NOT NULL,
    error_message VARCHAR(1024),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_transfer_id (transfer_id),
    INDEX idx_source_tx (source_tx_hash),
    INDEX idx_target_tx (target_tx_hash),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_transaction (
    id VARCHAR(64) PRIMARY KEY,
    tx_id VARCHAR(64) NOT NULL UNIQUE,
    chain_type VARCHAR(32) NOT NULL,
    from_address VARCHAR(128),
    to_address VARCHAR(128),
    value VARCHAR(128),
    gas_price VARCHAR(64),
    gas_limit VARCHAR(64),
    nonce VARCHAR(64),
    data LONGTEXT,
    signed_tx LONGTEXT,
    tx_hash VARCHAR(128),
    sign_type VARCHAR(32),
    multisig_wallet_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    error_message VARCHAR(1024),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tx_id (tx_id),
    INDEX idx_tx_hash (tx_hash),
    INDEX idx_from_address (from_address),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_contract_event (
    id VARCHAR(64) PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    chain_type VARCHAR(32) NOT NULL,
    contract_address VARCHAR(128) NOT NULL,
    event_name VARCHAR(128) NOT NULL,
    topic0 VARCHAR(128),
    topic1 VARCHAR(128),
    topic2 VARCHAR(128),
    topic3 VARCHAR(128),
    filter_params LONGTEXT,
    callback_url VARCHAR(512),
    callback_type VARCHAR(32),
    is_active TINYINT DEFAULT 1,
    user_id VARCHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_chain_type (chain_type),
    INDEX idx_contract_address (contract_address),
    INDEX idx_is_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_contract_event_log (
    id VARCHAR(64) PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    chain_type VARCHAR(32) NOT NULL,
    block_number BIGINT NOT NULL,
    tx_hash VARCHAR(128) NOT NULL,
    log_index INT NOT NULL,
    contract_address VARCHAR(128),
    event_data LONGTEXT,
    decoded_data LONGTEXT,
    callback_status VARCHAR(32),
    callback_response TEXT,
    timestamp BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_event_id (event_id),
    INDEX idx_tx_hash (tx_hash),
    INDEX idx_block_number (block_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_sys_config (
    id VARCHAR(64) PRIMARY KEY,
    config_id VARCHAR(64) NOT NULL,
    namespace VARCHAR(64) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    parameters LONGTEXT,
    enabled TINYINT DEFAULT 1,
    applied_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_config_namespace (config_id, namespace),
    INDEX idx_namespace (namespace)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
