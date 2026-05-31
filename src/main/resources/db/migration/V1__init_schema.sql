-- 地址派生与管理模块
CREATE TABLE IF NOT EXISTS `hd_wallet` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `wallet_name` VARCHAR(128) NOT NULL COMMENT '钱包名称',
    `mnemonic` VARCHAR(512) NOT NULL COMMENT '助记词（加密存储）',
    `root_xpub` VARCHAR(256) NOT NULL COMMENT '根扩展公钥',
    `root_xpriv` VARCHAR(512) NOT NULL COMMENT '根扩展私钥（加密存储）',
    `derivation_path` VARCHAR(64) NOT NULL COMMENT '派生路径',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型：ETH/BTC/BSC等',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_wallet_name` (`wallet_name`),
    INDEX `idx_chain_type` (`chain_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='HD钱包表';

CREATE TABLE IF NOT EXISTS `derived_address` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `wallet_id` VARCHAR(64) NOT NULL COMMENT '钱包ID',
    `address` VARCHAR(128) NOT NULL COMMENT '派生地址',
    `address_index` INT NOT NULL COMMENT '地址索引',
    `derivation_path` VARCHAR(128) NOT NULL COMMENT '完整派生路径',
    `public_key` VARCHAR(256) NOT NULL COMMENT '公钥',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_address_chain` (`address`, `chain_type`),
    INDEX `idx_wallet_id` (`wallet_id`),
    INDEX `idx_address_index` (`address_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='派生地址表';

CREATE TABLE IF NOT EXISTS `address_book` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `address` VARCHAR(128) NOT NULL COMMENT '地址',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型',
    `label` VARCHAR(128) NOT NULL COMMENT '标签',
    `description` VARCHAR(512) COMMENT '描述',
    `category` VARCHAR(64) COMMENT '分类',
    `is_whitelist` TINYINT DEFAULT 0 COMMENT '是否白名单',
    `is_blacklist` TINYINT DEFAULT 0 COMMENT '是否黑名单',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_address_chain` (`address`, `chain_type`),
    INDEX `idx_label` (`label`),
    INDEX `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地址簿表';

CREATE TABLE IF NOT EXISTS `address_tag` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `address_book_id` VARCHAR(64) NOT NULL COMMENT '地址簿ID',
    `tag_name` VARCHAR(64) NOT NULL COMMENT '标签名',
    `tag_value` VARCHAR(256) COMMENT '标签值',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_address_book_id` (`address_book_id`),
    INDEX `idx_tag_name` (`tag_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地址标签表';

-- 多签钱包协调模块
CREATE TABLE IF NOT EXISTS `multisig_wallet` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `wallet_name` VARCHAR(128) NOT NULL COMMENT '钱包名称',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型',
    `wallet_address` VARCHAR(128) NOT NULL COMMENT '多签钱包地址',
    `threshold` INT NOT NULL COMMENT '签名阈值',
    `total_signers` INT NOT NULL COMMENT '签名者总数',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_wallet_address_chain` (`wallet_address`, `chain_type`),
    INDEX `idx_wallet_name` (`wallet_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多签钱包表';

CREATE TABLE IF NOT EXISTS `multisig_signer` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `wallet_id` VARCHAR(64) NOT NULL COMMENT '多签钱包ID',
    `signer_address` VARCHAR(128) NOT NULL COMMENT '签名者地址',
    `signer_index` INT NOT NULL COMMENT '签名者索引',
    `public_key` VARCHAR(256) COMMENT '公钥',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_wallet_id` (`wallet_id`),
    INDEX `idx_signer_address` (`signer_address`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多签签名者表';

CREATE TABLE IF NOT EXISTS `multisig_proposal` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `wallet_id` VARCHAR(64) NOT NULL COMMENT '多签钱包ID',
    `proposal_type` VARCHAR(32) NOT NULL COMMENT '提案类型：TRANSFER/UPGRADE/CONFIG',
    `title` VARCHAR(256) NOT NULL COMMENT '提案标题',
    `description` TEXT COMMENT '提案描述',
    `to_address` VARCHAR(128) NOT NULL COMMENT '目标地址',
    `value` DECIMAL(36,18) NOT NULL DEFAULT 0 COMMENT '转账金额',
    `data` TEXT COMMENT '交易数据',
    `nonce` BIGINT NOT NULL COMMENT '交易nonce',
    `gas_limit` BIGINT COMMENT 'Gas限制',
    `gas_price` DECIMAL(36,18) COMMENT 'Gas价格',
    `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/APPROVED/REJECTED/EXECUTED/FAILED',
    `required_confirmations` INT NOT NULL COMMENT '所需确认数',
    `current_confirmations` INT NOT NULL DEFAULT 0 COMMENT '当前确认数',
    `expire_at` DATETIME COMMENT '过期时间',
    `executed_at` DATETIME COMMENT '执行时间',
    `tx_hash` VARCHAR(128) COMMENT '交易哈希',
    `creator_address` VARCHAR(128) NOT NULL COMMENT '创建者地址',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_wallet_id` (`wallet_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多签提案表';

CREATE TABLE IF NOT EXISTS `multisig_approval` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `proposal_id` VARCHAR(64) NOT NULL COMMENT '提案ID',
    `signer_address` VARCHAR(128) NOT NULL COMMENT '签名者地址',
    `signature` VARCHAR(512) NOT NULL COMMENT '签名数据',
    `approval_type` VARCHAR(16) NOT NULL COMMENT '类型：APPROVE/REJECT',
    `signed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '签名时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_proposal_signer` (`proposal_id`, `signer_address`),
    INDEX `idx_proposal_id` (`proposal_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多签审批表';

-- 资产跨链桥接模块
CREATE TABLE IF NOT EXISTS `bridge_chain` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `chain_name` VARCHAR(64) NOT NULL COMMENT '链名称',
    `chain_id` BIGINT NOT NULL COMMENT '链ID',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型',
    `rpc_url` VARCHAR(256) NOT NULL COMMENT 'RPC地址',
    `bridge_contract` VARCHAR(128) NOT NULL COMMENT '桥接合约地址',
    `confirmations` INT NOT NULL DEFAULT 15 COMMENT '确认区块数',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chain_id` (`chain_id`),
    INDEX `idx_chain_name` (`chain_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='跨链配置表';

CREATE TABLE IF NOT EXISTS `bridge_transfer` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `transfer_id` VARCHAR(128) NOT NULL COMMENT '跨链转账ID',
    `from_chain_id` BIGINT NOT NULL COMMENT '源链ID',
    `to_chain_id` BIGINT NOT NULL COMMENT '目标链ID',
    `from_address` VARCHAR(128) NOT NULL COMMENT '源地址',
    `to_address` VARCHAR(128) NOT NULL COMMENT '目标地址',
    `token_address` VARCHAR(128) NOT NULL COMMENT 'Token地址',
    `token_symbol` VARCHAR(32) NOT NULL COMMENT 'Token符号',
    `amount` DECIMAL(36,18) NOT NULL COMMENT '转账金额',
    `fee` DECIMAL(36,18) DEFAULT 0 COMMENT '手续费',
    `status` VARCHAR(32) NOT NULL DEFAULT 'INIT' COMMENT '状态：INIT/LOCKED/MINTED/CONFIRMED/FAILED',
    `lock_tx_hash` VARCHAR(128) COMMENT '锁定交易哈希',
    `lock_block_number` BIGINT COMMENT '锁定区块号',
    `mint_tx_hash` VARCHAR(128) COMMENT '铸造交易哈希',
    `mint_block_number` BIGINT COMMENT '铸造区块号',
    `message_hash` VARCHAR(256) COMMENT '跨链消息哈希',
    `proof_data` TEXT COMMENT '证明数据',
    `error_message` VARCHAR(512) COMMENT '错误信息',
    `expire_at` DATETIME COMMENT '过期时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_transfer_id` (`transfer_id`),
    INDEX `idx_from_chain` (`from_chain_id`),
    INDEX `idx_to_chain` (`to_chain_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='跨链转账记录表';

CREATE TABLE IF NOT EXISTS `bridge_message` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `message_id` VARCHAR(128) NOT NULL COMMENT '消息ID',
    `from_chain_id` BIGINT NOT NULL COMMENT '源链ID',
    `to_chain_id` BIGINT NOT NULL COMMENT '目标链ID',
    `message_type` VARCHAR(32) NOT NULL COMMENT '消息类型',
    `payload` TEXT NOT NULL COMMENT '消息载荷',
    `signature` VARCHAR(512) COMMENT '签名',
    `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/VERIFIED/DELIVERED/FAILED',
    `nonce` BIGINT NOT NULL COMMENT '消息nonce',
    `verified_at` DATETIME COMMENT '验证时间',
    `delivered_at` DATETIME COMMENT '递送时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_id` (`message_id`),
    INDEX `idx_from_chain` (`from_chain_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='跨链消息表';

-- 交易构造与签名模块
CREATE TABLE IF NOT EXISTS `transaction_template` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `template_name` VARCHAR(128) NOT NULL COMMENT '模板名称',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型',
    `tx_type` VARCHAR(32) NOT NULL COMMENT '交易类型',
    `contract_address` VARCHAR(128) COMMENT '合约地址',
    `method_abi` TEXT COMMENT '方法ABI',
    `method_name` VARCHAR(128) COMMENT '方法名',
    `parameters` TEXT COMMENT '参数定义JSON',
    `gas_limit` BIGINT COMMENT '默认Gas限制',
    `gas_price` DECIMAL(36,18) COMMENT '默认Gas价格',
    `value` DECIMAL(36,18) DEFAULT 0 COMMENT '默认转账金额',
    `description` VARCHAR(512) COMMENT '描述',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_template_name` (`template_name`),
    INDEX `idx_chain_type` (`chain_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易模板表';

CREATE TABLE IF NOT EXISTS `pending_transaction` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型',
    `from_address` VARCHAR(128) NOT NULL COMMENT '发起地址',
    `to_address` VARCHAR(128) NOT NULL COMMENT '目标地址',
    `value` DECIMAL(36,18) NOT NULL DEFAULT 0 COMMENT '转账金额',
    `data` TEXT COMMENT '交易数据',
    `nonce` BIGINT NOT NULL COMMENT '交易nonce',
    `gas_limit` BIGINT NOT NULL COMMENT 'Gas限制',
    `gas_price` DECIMAL(36,18) NOT NULL COMMENT 'Gas价格',
    `max_priority_fee` DECIMAL(36,18) COMMENT '最大优先费(EIP-1559)',
    `max_fee_per_gas` DECIMAL(36,18) COMMENT '最大Gas费(EIP-1559)',
    `tx_type` INT DEFAULT 2 COMMENT '交易类型：0 Legacy, 2 EIP-1559',
    `signature` VARCHAR(512) COMMENT '签名数据',
    `signed_tx` TEXT COMMENT '已签名交易',
    `tx_hash` VARCHAR(128) COMMENT '交易哈希',
    `status` VARCHAR(32) NOT NULL DEFAULT 'CREATED' COMMENT '状态：CREATED/SIGNED/BROADCAST/PENDING/CONFIRMED/FAILED',
    `block_number` BIGINT COMMENT '打包区块号',
    `error_message` VARCHAR(512) COMMENT '错误信息',
    `multisig_wallet_id` VARCHAR(64) COMMENT '多签钱包ID',
    `template_id` VARCHAR(64) COMMENT '模板ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_from_address` (`from_address`),
    INDEX `idx_status` (`status`),
    INDEX `idx_tx_hash` (`tx_hash`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='待处理交易表';

CREATE TABLE IF NOT EXISTS `signing_policy` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `policy_name` VARCHAR(128) NOT NULL COMMENT '策略名称',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型',
    `policy_type` VARCHAR(32) NOT NULL COMMENT '策略类型：SINGLE/MULTISIG/HIERARCHICAL',
    `min_signatures` INT DEFAULT 1 COMMENT '最小签名数',
    `max_signatures` INT DEFAULT 1 COMMENT '最大签名数',
    `signer_addresses` TEXT COMMENT '签名者地址列表JSON',
    `gas_strategy` VARCHAR(32) NOT NULL DEFAULT 'AUTO' COMMENT 'Gas策略：AUTO/FAST/NORMAL/SLOW/CUSTOM',
    `custom_gas_multiplier` DECIMAL(5,2) DEFAULT 1.0 COMMENT '自定义Gas倍数',
    `nonce_strategy` VARCHAR(32) NOT NULL DEFAULT 'AUTO' COMMENT 'Nonce策略：AUTO/MANUAL',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_policy_name` (`policy_name`),
    INDEX `idx_chain_type` (`chain_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='签名策略表';

-- 去中心化存储适配模块
CREATE TABLE IF NOT EXISTS `storage_config` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `storage_type` VARCHAR(32) NOT NULL COMMENT '存储类型：IPFS/ARWEAVE/FILECOIN',
    `config_name` VARCHAR(128) NOT NULL COMMENT '配置名称',
    `gateway_url` VARCHAR(256) NOT NULL COMMENT '网关地址',
    `api_key` VARCHAR(256) COMMENT 'API密钥',
    `api_secret` VARCHAR(512) COMMENT 'API密钥（加密）',
    `timeout` INT DEFAULT 30000 COMMENT '超时时间(ms)',
    `pin_enabled` TINYINT DEFAULT 1 COMMENT '是否启用Pin',
    `default_pin_duration` INT DEFAULT 0 COMMENT '默认Pin时长(秒)，0表示永久',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `is_default` TINYINT DEFAULT 0 COMMENT '是否默认',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_storage_type` (`storage_type`),
    INDEX `idx_config_name` (`config_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='存储配置表';

CREATE TABLE IF NOT EXISTS `stored_content` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `content_id` VARCHAR(256) NOT NULL COMMENT '内容ID：CID/Transaction ID',
    `storage_type` VARCHAR(32) NOT NULL COMMENT '存储类型',
    `config_id` VARCHAR(64) NOT NULL COMMENT '存储配置ID',
    `content_hash` VARCHAR(128) NOT NULL COMMENT '内容哈希',
    `content_size` BIGINT NOT NULL COMMENT '内容大小(字节)',
    `mime_type` VARCHAR(128) COMMENT 'MIME类型',
    `metadata` TEXT COMMENT '元数据JSON',
    `pin_status` VARCHAR(32) DEFAULT 'PINNED' COMMENT 'Pin状态：PINNING/PINNED/UNPINNED/FAILED',
    `pin_expire_at` DATETIME COMMENT 'Pin过期时间',
    `access_url` VARCHAR(512) COMMENT '访问URL',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_content_id` (`content_id`, `storage_type`),
    INDEX `idx_content_hash` (`content_hash`),
    INDEX `idx_pin_status` (`pin_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='存储内容表';

CREATE TABLE IF NOT EXISTS `storage_pin` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `content_id` VARCHAR(256) NOT NULL COMMENT '内容ID',
    `storage_type` VARCHAR(32) NOT NULL COMMENT '存储类型',
    `request_id` VARCHAR(128) COMMENT 'Pin请求ID',
    `status` VARCHAR(32) NOT NULL DEFAULT 'PINNING' COMMENT '状态：PINNING/PINNED/UNPINNED/FAILED',
    `pin_count` INT DEFAULT 1 COMMENT 'Pin节点数',
    `region` VARCHAR(64) COMMENT '区域',
    `expire_at` DATETIME COMMENT '过期时间',
    `error_message` VARCHAR(512) COMMENT '错误信息',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_content_id` (`content_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Pin管理表';

-- 零知识证明验证模块
CREATE TABLE IF NOT EXISTS `zkp_circuit` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `circuit_name` VARCHAR(128) NOT NULL COMMENT '电路名称',
    `circuit_type` VARCHAR(32) NOT NULL COMMENT '电路类型：GROTH16/PLONK/STARK',
    `version` VARCHAR(32) NOT NULL DEFAULT '1.0.0' COMMENT '版本',
    `verifying_key` TEXT NOT NULL COMMENT '验证密钥',
    `proving_key_cid` VARCHAR(256) COMMENT '证明密钥CID',
    `circuit_cid` VARCHAR(256) COMMENT '电路文件CID',
    `input_schema` TEXT COMMENT '输入Schema JSON',
    `description` VARCHAR(512) COMMENT '描述',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_circuit_name` (`circuit_name`),
    INDEX `idx_circuit_type` (`circuit_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ZKP电路表';

CREATE TABLE IF NOT EXISTS `zkp_verification` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `verification_id` VARCHAR(128) NOT NULL COMMENT '验证ID',
    `circuit_id` VARCHAR(64) NOT NULL COMMENT '电路ID',
    `proof_data` TEXT NOT NULL COMMENT '证明数据',
    `public_inputs` TEXT COMMENT '公开输入JSON',
    `verifier_address` VARCHAR(128) COMMENT '验证者地址',
    `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/VERIFIED/INVALID/FAILED',
    `verify_result` TINYINT COMMENT '验证结果：0失败 1成功',
    `verify_time` BIGINT COMMENT '验证耗时(ms)',
    `error_message` VARCHAR(512) COMMENT '错误信息',
    `verified_at` DATETIME COMMENT '验证时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_verification_id` (`verification_id`),
    INDEX `idx_circuit_id` (`circuit_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ZKP验证记录表';

-- 合约事件监听模块
CREATE TABLE IF NOT EXISTS `event_listener` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `listener_name` VARCHAR(128) NOT NULL COMMENT '监听器名称',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型',
    `contract_address` VARCHAR(128) NOT NULL COMMENT '合约地址',
    `event_name` VARCHAR(128) NOT NULL COMMENT '事件名',
    `event_signature` VARCHAR(256) NOT NULL COMMENT '事件签名',
    `abi_definition` TEXT NOT NULL COMMENT 'ABI定义JSON',
    `start_block` BIGINT DEFAULT 0 COMMENT '起始区块',
    `current_block` BIGINT DEFAULT 0 COMMENT '当前扫描区块',
    `callback_type` VARCHAR(32) NOT NULL COMMENT '回调类型：HTTP/WORKFLOW/WEBHOOK',
    `callback_url` VARCHAR(512) COMMENT '回调URL',
    `callback_method` VARCHAR(16) DEFAULT 'POST' COMMENT '回调方法',
    `callback_headers` TEXT COMMENT '回调头JSON',
    `retry_count` INT DEFAULT 3 COMMENT '重试次数',
    `retry_interval` INT DEFAULT 5000 COMMENT '重试间隔(ms)',
    `filter_params` TEXT COMMENT '过滤参数JSON',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停止 1运行',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_listener_name` (`listener_name`),
    INDEX `idx_contract_address` (`contract_address`),
    INDEX `idx_event_name` (`event_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件监听器表';

CREATE TABLE IF NOT EXISTS `event_log` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `listener_id` VARCHAR(64) NOT NULL COMMENT '监听器ID',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型',
    `contract_address` VARCHAR(128) NOT NULL COMMENT '合约地址',
    `event_name` VARCHAR(128) NOT NULL COMMENT '事件名',
    `tx_hash` VARCHAR(128) NOT NULL COMMENT '交易哈希',
    `block_number` BIGINT NOT NULL COMMENT '区块号',
    `block_hash` VARCHAR(128) NOT NULL COMMENT '区块哈希',
    `log_index` INT NOT NULL COMMENT '日志索引',
    `event_data` TEXT NOT NULL COMMENT '事件数据JSON',
    `decoded_data` TEXT COMMENT '解码后数据JSON',
    `status` VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' COMMENT '状态：RECEIVED/PROCESSED/CALLBACK/CALLBACK_FAILED',
    `callback_status` VARCHAR(32) COMMENT '回调状态',
    `callback_response` TEXT COMMENT '回调响应',
    `error_message` VARCHAR(512) COMMENT '错误信息',
    `processed_at` DATETIME COMMENT '处理时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tx_log` (`tx_hash`, `log_index`),
    INDEX `idx_listener_id` (`listener_id`),
    INDEX `idx_block_number` (`block_number`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件日志表';

-- Gas费用预估模块
CREATE TABLE IF NOT EXISTS `gas_fee_history` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型',
    `block_number` BIGINT NOT NULL COMMENT '区块号',
    `block_timestamp` BIGINT NOT NULL COMMENT '区块时间戳',
    `base_fee` DECIMAL(36,18) COMMENT '基础费用',
    `gas_price_standard` DECIMAL(36,18) NOT NULL COMMENT '标准Gas价格',
    `gas_price_fast` DECIMAL(36,18) NOT NULL COMMENT '快速Gas价格',
    `gas_price_slow` DECIMAL(36,18) NOT NULL COMMENT '慢速Gas价格',
    `max_priority_fee` DECIMAL(36,18) COMMENT '最大优先费',
    `max_fee_per_gas` DECIMAL(36,18) COMMENT '最大Gas费',
    `gas_used` BIGINT COMMENT 'Gas使用量',
    `gas_limit` BIGINT COMMENT 'Gas限制',
    `tx_count` INT COMMENT '交易数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_chain_block` (`chain_type`, `block_number`),
    INDEX `idx_block_timestamp` (`block_timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Gas费用历史表';

CREATE TABLE IF NOT EXISTS `gas_estimation` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `estimation_id` VARCHAR(128) NOT NULL COMMENT '预估ID',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型',
    `to_address` VARCHAR(128) COMMENT '目标地址',
    `data` TEXT COMMENT '交易数据',
    `gas_limit_estimate` BIGINT NOT NULL COMMENT '预估Gas限制',
    `gas_price_standard` DECIMAL(36,18) NOT NULL COMMENT '预估标准Gas价格',
    `gas_price_fast` DECIMAL(36,18) NOT NULL COMMENT '预估快速Gas价格',
    `gas_price_slow` DECIMAL(36,18) NOT NULL COMMENT '预估慢速Gas价格',
    `priority_fee` DECIMAL(36,18) COMMENT '预估优先费',
    `estimated_cost_standard` DECIMAL(36,18) COMMENT '预估标准费用',
    `estimated_cost_fast` DECIMAL(36,18) COMMENT '预估快速费用',
    `estimated_cost_slow` DECIMAL(36,18) COMMENT '预估慢速费用',
    `confidence` DECIMAL(5,2) COMMENT '预估置信度',
    `historical_blocks` INT COMMENT '使用历史区块数',
    `expire_at` DATETIME NOT NULL COMMENT '预估过期时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_estimation_id` (`estimation_id`),
    INDEX `idx_chain_type` (`chain_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Gas预估记录表';

CREATE TABLE IF NOT EXISTS `gas_price_oracle` (
    `id` VARCHAR(64) NOT NULL COMMENT '主键ID',
    `chain_type` VARCHAR(32) NOT NULL COMMENT '链类型',
    `oracle_name` VARCHAR(128) NOT NULL COMMENT '预言机名称',
    `oracle_url` VARCHAR(512) NOT NULL COMMENT '预言机URL',
    `api_key` VARCHAR(256) COMMENT 'API密钥',
    `update_interval` INT DEFAULT 15000 COMMENT '更新间隔(ms)',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `is_default` TINYINT DEFAULT 0 COMMENT '是否默认',
    `last_update` DATETIME COMMENT '最后更新时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_chain_type` (`chain_type`),
    INDEX `idx_oracle_name` (`oracle_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Gas价格预言机表';
