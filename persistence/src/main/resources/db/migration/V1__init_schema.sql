CREATE TABLE IF NOT EXISTS `storage_pin` (
    `id` BIGINT NOT NULL,
    `cid` VARCHAR(255) NOT NULL,
    `storage_type` VARCHAR(50) NOT NULL COMMENT 'IPFS or ARWEAVE',
    `pin_status` VARCHAR(20) NOT NULL,
    `pinned_at` DATETIME DEFAULT NULL,
    `size_bytes` BIGINT DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_storage_pin_cid` (`cid`),
    INDEX `idx_storage_pin_status` (`pin_status`),
    INDEX `idx_storage_pin_storage_type` (`storage_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `cross_chain_lock` (
    `id` BIGINT NOT NULL,
    `source_chain` VARCHAR(50) NOT NULL,
    `target_chain` VARCHAR(50) NOT NULL,
    `tx_hash` VARCHAR(128) NOT NULL,
    `lock_amount` DECIMAL(38, 18) NOT NULL,
    `lock_status` VARCHAR(20) NOT NULL,
    `locker_address` VARCHAR(128) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_cross_chain_lock_tx_hash` (`tx_hash`),
    INDEX `idx_cross_chain_lock_status` (`lock_status`),
    INDEX `idx_cross_chain_lock_locker` (`locker_address`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `cross_chain_mint` (
    `id` BIGINT NOT NULL,
    `lock_id` BIGINT NOT NULL,
    `target_chain` VARCHAR(50) NOT NULL,
    `mint_tx_hash` VARCHAR(128) DEFAULT NULL,
    `mint_amount` DECIMAL(38, 18) NOT NULL,
    `mint_status` VARCHAR(20) NOT NULL,
    `minter_address` VARCHAR(128) NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_cross_chain_mint_lock_id` (`lock_id`),
    INDEX `idx_cross_chain_mint_status` (`mint_status`),
    INDEX `idx_cross_chain_mint_minter` (`minter_address`),
    CONSTRAINT `fk_cross_chain_mint_lock_id` FOREIGN KEY (`lock_id`) REFERENCES `cross_chain_lock` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `multisig_proposal` (
    `id` BIGINT NOT NULL,
    `wallet_address` VARCHAR(128) NOT NULL,
    `proposal_type` VARCHAR(50) NOT NULL,
    `target_address` VARCHAR(128) NOT NULL,
    `value` DECIMAL(38, 18) NOT NULL,
    `data` TEXT DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/EXECUTED/REJECTED',
    `threshold` INT NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_multisig_proposal_wallet` (`wallet_address`),
    INDEX `idx_multisig_proposal_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `multisig_signature` (
    `id` BIGINT NOT NULL,
    `proposal_id` BIGINT NOT NULL,
    `signer_address` VARCHAR(128) NOT NULL,
    `signature` VARCHAR(512) NOT NULL,
    `signed_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_multisig_signature_proposal_id` (`proposal_id`),
    INDEX `idx_multisig_signature_signer` (`signer_address`),
    CONSTRAINT `fk_multisig_signature_proposal_id` FOREIGN KEY (`proposal_id`) REFERENCES `multisig_proposal` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `chain_block` (
    `id` BIGINT NOT NULL,
    `chain_id` VARCHAR(50) NOT NULL,
    `block_number` BIGINT NOT NULL,
    `block_hash` VARCHAR(128) NOT NULL,
    `parent_hash` VARCHAR(128) DEFAULT NULL,
    `timestamp` BIGINT NOT NULL,
    `tx_count` INT NOT NULL DEFAULT 0,
    `raw_json` MEDIUMTEXT DEFAULT NULL,
    `indexed_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_chain_block_chain_id_number` (`chain_id`, `block_number`),
    INDEX `idx_chain_block_hash` (`block_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `chain_transaction` (
    `id` BIGINT NOT NULL,
    `chain_id` VARCHAR(50) NOT NULL,
    `block_number` BIGINT NOT NULL,
    `tx_hash` VARCHAR(128) NOT NULL,
    `from_address` VARCHAR(128) DEFAULT NULL,
    `to_address` VARCHAR(128) DEFAULT NULL,
    `value` DECIMAL(38, 18) NOT NULL DEFAULT 0,
    `gas_used` BIGINT DEFAULT NULL,
    `status` INT DEFAULT NULL,
    `raw_json` MEDIUMTEXT DEFAULT NULL,
    `indexed_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_chain_transaction_tx_hash` (`tx_hash`),
    INDEX `idx_chain_transaction_chain_block` (`chain_id`, `block_number`),
    INDEX `idx_chain_transaction_from` (`from_address`),
    INDEX `idx_chain_transaction_to` (`to_address`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `address_entry` (
    `id` BIGINT NOT NULL,
    `address` VARCHAR(128) NOT NULL,
    `chain_type` VARCHAR(50) NOT NULL,
    `label` VARCHAR(100) DEFAULT NULL,
    `path` VARCHAR(255) DEFAULT NULL,
    `hd_index` INT DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_address_entry_address_chain` (`address`, `chain_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `gas_estimate` (
    `id` BIGINT NOT NULL,
    `chain_id` VARCHAR(50) NOT NULL,
    `gas_price` DECIMAL(38, 18) NOT NULL,
    `base_fee` DECIMAL(38, 18) DEFAULT NULL,
    `priority_fee` DECIMAL(38, 18) DEFAULT NULL,
    `estimated_cost` DECIMAL(38, 18) DEFAULT NULL,
    `block_number` BIGINT DEFAULT NULL,
    `recorded_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_gas_estimate_chain_id` (`chain_id`),
    INDEX `idx_gas_estimate_recorded_at` (`recorded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
