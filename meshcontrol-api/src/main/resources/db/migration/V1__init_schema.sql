-- Event Store Tables
CREATE TABLE IF NOT EXISTS `event_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `event_id` VARCHAR(64) NOT NULL UNIQUE,
    `aggregate_id` VARCHAR(64) NOT NULL,
    `aggregate_type` VARCHAR(64) NOT NULL,
    `event_type` VARCHAR(128) NOT NULL,
    `version` INT NOT NULL DEFAULT 0,
    `payload` JSON,
    `metadata` JSON,
    `source` VARCHAR(128),
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_aggregate` (`aggregate_id`, `aggregate_type`, `version`),
    INDEX `idx_event_type` (`event_type`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `snapshot_id` VARCHAR(64) NOT NULL UNIQUE,
    `aggregate_id` VARCHAR(64) NOT NULL,
    `aggregate_type` VARCHAR(64) NOT NULL,
    `version` INT NOT NULL,
    `state` JSON,
    `metrics` JSON,
    `dimensions` JSON,
    `timestamp` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_aggregate` (`aggregate_id`, `aggregate_type`, `version`),
    INDEX `idx_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Sidecar Tables
CREATE TABLE IF NOT EXISTS `sidecar_instance` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `sidecar_id` VARCHAR(64) NOT NULL UNIQUE,
    `pod_name` VARCHAR(128) NOT NULL,
    `namespace` VARCHAR(64) NOT NULL,
    `node_name` VARCHAR(128),
    `service_name` VARCHAR(128),
    `version` VARCHAR(64),
    `status` VARCHAR(32) NOT NULL,
    `config_version` INT NOT NULL DEFAULT 1,
    `resources` JSON,
    `injected_at` DATETIME,
    `last_heartbeat` DATETIME,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    INDEX `idx_namespace` (`namespace`, `status`),
    INDEX `idx_service` (`service_name`),
    INDEX `idx_heartbeat` (`last_heartbeat`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `injection_policy` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `policy_id` VARCHAR(64) NOT NULL UNIQUE,
    `name` VARCHAR(128) NOT NULL,
    `namespace` VARCHAR(64),
    `selector` JSON,
    `sidecar_template` JSON,
    `resources` JSON,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `priority` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `sidecar_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `config_id` VARCHAR(64) NOT NULL UNIQUE,
    `namespace` VARCHAR(64) NOT NULL,
    `version` INT NOT NULL DEFAULT 1,
    `parameters` JSON,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `applied_at` DATETIME,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    INDEX `idx_namespace_version` (`namespace`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- DNS Tables
CREATE TABLE IF NOT EXISTS `dns_upstream` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `upstream_id` VARCHAR(64) NOT NULL UNIQUE,
    `name` VARCHAR(128) NOT NULL,
    `address` VARCHAR(256) NOT NULL,
    `port` INT NOT NULL DEFAULT 53,
    `protocol` VARCHAR(16) NOT NULL DEFAULT 'udp',
    `timeout_ms` INT NOT NULL DEFAULT 5000,
    `priority` INT NOT NULL DEFAULT 0,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `health_check_enabled` TINYINT NOT NULL DEFAULT 1,
    `last_health_check` DATETIME,
    `health_status` VARCHAR(32),
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `dns_zone` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `zone_id` VARCHAR(64) NOT NULL UNIQUE,
    `domain` VARCHAR(256) NOT NULL,
    `upstream_ids` JSON,
    `resolution_policy` VARCHAR(32) NOT NULL DEFAULT 'round_robin',
    `cache_ttl` INT NOT NULL DEFAULT 300,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `dns_cache` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `cache_key` VARCHAR(512) NOT NULL UNIQUE,
    `query_type` VARCHAR(16) NOT NULL,
    `responses` JSON,
    `expires_at` DATETIME NOT NULL,
    `hit_count` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Traffic Control Tables
CREATE TABLE IF NOT EXISTS `traffic_policy` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `policy_id` VARCHAR(64) NOT NULL UNIQUE,
    `name` VARCHAR(128) NOT NULL,
    `type` VARCHAR(32) NOT NULL,
    `namespace` VARCHAR(64),
    `service_name` VARCHAR(128),
    `match_rules` JSON,
    `routes` JSON,
    `mirror_config` JSON,
    `circuit_breaker` JSON,
    `retry_policy` JSON,
    `timeout_ms` INT,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `priority` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    INDEX `idx_service` (`service_name`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `canary_release` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `release_id` VARCHAR(64) NOT NULL UNIQUE,
    `name` VARCHAR(128) NOT NULL,
    `service_name` VARCHAR(128) NOT NULL,
    `namespace` VARCHAR(64) NOT NULL,
    `primary_version` VARCHAR(64) NOT NULL,
    `canary_version` VARCHAR(64) NOT NULL,
    `traffic_split` JSON,
    `strategy` VARCHAR(32) NOT NULL DEFAULT 'percentage',
    `status` VARCHAR(32) NOT NULL,
    `started_at` DATETIME,
    `completed_at` DATETIME,
    `rollback_at` DATETIME,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- mTLS Tables
CREATE TABLE IF NOT EXISTS `certificate` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `cert_id` VARCHAR(64) NOT NULL UNIQUE,
    `serial_number` VARCHAR(128),
    `common_name` VARCHAR(256) NOT NULL,
    `sans` JSON,
    `cert_type` VARCHAR(32) NOT NULL,
    `issuer` VARCHAR(256),
    `not_before` DATETIME NOT NULL,
    `not_after` DATETIME NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `pem_data` TEXT,
    `private_key_pem` TEXT,
    `issuer_cert_id` VARCHAR(64),
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    INDEX `idx_status` (`status`),
    INDEX `idx_expiry` (`not_after`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ca_bundle` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `bundle_id` VARCHAR(64) NOT NULL UNIQUE,
    `name` VARCHAR(128) NOT NULL,
    `root_cert_id` VARCHAR(64) NOT NULL,
    `intermediate_cert_ids` JSON,
    `rotation_days` INT NOT NULL DEFAULT 365,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `certificate_revocation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `revocation_id` VARCHAR(64) NOT NULL UNIQUE,
    `cert_id` VARCHAR(64) NOT NULL,
    `serial_number` VARCHAR(128),
    `reason` VARCHAR(256),
    `revoked_at` DATETIME NOT NULL,
    `crl_entry` TEXT,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_cert` (`cert_id`),
    INDEX `idx_revoked_at` (`revoked_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Fault Injection Tables
CREATE TABLE IF NOT EXISTS `fault_scenario` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `scenario_id` VARCHAR(64) NOT NULL UNIQUE,
    `name` VARCHAR(128) NOT NULL,
    `description` TEXT,
    `fault_type` VARCHAR(32) NOT NULL,
    `target_selector` JSON,
    `injection_config` JSON,
    `duration_seconds` INT,
    `auto_rollback` TINYINT NOT NULL DEFAULT 1,
    `rollback_config` JSON,
    `enabled` TINYINT NOT NULL DEFAULT 0,
    `status` VARCHAR(32),
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `fault_injection` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `injection_id` VARCHAR(64) NOT NULL UNIQUE,
    `scenario_id` VARCHAR(64) NOT NULL,
    `targets` JSON,
    `status` VARCHAR(32) NOT NULL,
    `started_at` DATETIME,
    `ended_at` DATETIME,
    `rollback_started_at` DATETIME,
    `rollback_completed_at` DATETIME,
    `error_detail` TEXT,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_scenario` (`scenario_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Audit Tables
CREATE TABLE IF NOT EXISTS `command_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `command_id` VARCHAR(64) NOT NULL UNIQUE,
    `command_type` VARCHAR(128) NOT NULL,
    `aggregate_id` VARCHAR(64),
    `aggregate_type` VARCHAR(64),
    `payload` JSON,
    `metadata` JSON,
    `status` VARCHAR(32) NOT NULL,
    `result` JSON,
    `error_message` TEXT,
    `executed_by` VARCHAR(128),
    `executed_at` DATETIME NOT NULL,
    `duration_ms` BIGINT,
    INDEX `idx_command_type` (`command_type`),
    INDEX `idx_aggregate` (`aggregate_id`, `aggregate_type`),
    INDEX `idx_executed_at` (`executed_at`),
    INDEX `idx_executor` (`executed_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `audit_id` VARCHAR(64) NOT NULL UNIQUE,
    `command_id` VARCHAR(64),
    `event_id` VARCHAR(64),
    `action` VARCHAR(128) NOT NULL,
    `resource_type` VARCHAR(64),
    `resource_id` VARCHAR(64),
    `old_value` JSON,
    `new_value` JSON,
    `operator` VARCHAR(128) NOT NULL,
    `source_ip` VARCHAR(64),
    `user_agent` VARCHAR(512),
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_command` (`command_id`),
    INDEX `idx_event` (`event_id`),
    INDEX `idx_resource` (`resource_type`, `resource_id`),
    INDEX `idx_operator` (`operator`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Image Distribution Tables
CREATE TABLE IF NOT EXISTS `image_registry` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `registry_id` VARCHAR(64) NOT NULL UNIQUE,
    `name` VARCHAR(128) NOT NULL,
    `url` VARCHAR(256) NOT NULL,
    `type` VARCHAR(32) NOT NULL,
    `auth_type` VARCHAR(32) NOT NULL DEFAULT 'none',
    `username` VARCHAR(128),
    `password_encrypted` TEXT,
    `tls_enabled` TINYINT NOT NULL DEFAULT 1,
    `insecure_skip_verify` TINYINT NOT NULL DEFAULT 0,
    `priority` INT NOT NULL DEFAULT 0,
    `enabled` TINYINT NOT NULL DEFAULT 1,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `image_repository` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `repo_id` VARCHAR(64) NOT NULL UNIQUE,
    `registry_id` VARCHAR(64) NOT NULL,
    `name` VARCHAR(256) NOT NULL,
    `description` TEXT,
    `tags` JSON,
    `sync_enabled` TINYINT NOT NULL DEFAULT 0,
    `sync_policy` JSON,
    `last_sync_at` DATETIME,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    INDEX `idx_registry` (`registry_id`),
    INDEX `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `image_manifest` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `manifest_id` VARCHAR(64) NOT NULL UNIQUE,
    `repo_id` VARCHAR(64) NOT NULL,
    `digest` VARCHAR(128) NOT NULL,
    `tag` VARCHAR(128),
    `layers` JSON,
    `total_size` BIGINT,
    `architecture` VARCHAR(32),
    `os` VARCHAR(32),
    `p2p_enabled` TINYINT NOT NULL DEFAULT 0,
    `p2p_seed_nodes` JSON,
    `pull_count` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_repo_digest` (`repo_id`, `digest`),
    INDEX `idx_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `image_sync_task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `task_id` VARCHAR(64) NOT NULL UNIQUE,
    `source_registry_id` VARCHAR(64) NOT NULL,
    `target_registry_id` VARCHAR(64) NOT NULL,
    `source_repo` VARCHAR(256) NOT NULL,
    `target_repo` VARCHAR(256) NOT NULL,
    `tag_filter` JSON,
    `status` VARCHAR(32) NOT NULL,
    `progress` DOUBLE NOT NULL DEFAULT 0,
    `total_images` INT NOT NULL DEFAULT 0,
    `synced_images` INT NOT NULL DEFAULT 0,
    `error_detail` TEXT,
    `started_at` DATETIME,
    `completed_at` DATETIME,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_status` (`status`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
