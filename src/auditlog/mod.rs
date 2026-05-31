use crate::config::AuditLogConfig;
use crate::models::AppError;
use crate::utils::{current_datetime, sha256_hex, generate_id};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum OperationType {
    Create,
    Read,
    Update,
    Delete,
    Login,
    Logout,
    Access,
    Modify,
    Execute,
    Admin,
    Audit,
    Custom,
}

impl OperationType {
    pub fn from_str(s: &str) -> Result<Self, AppError> {
        match s.to_lowercase().as_str() {
            "create" => Ok(OperationType::Create),
            "read" => Ok(OperationType::Read),
            "update" => Ok(OperationType::Update),
            "delete" => Ok(OperationType::Delete),
            "login" => Ok(OperationType::Login),
            "logout" => Ok(OperationType::Logout),
            "access" => Ok(OperationType::Access),
            "modify" => Ok(OperationType::Modify),
            "execute" => Ok(OperationType::Execute),
            "admin" => Ok(OperationType::Admin),
            "audit" => Ok(OperationType::Audit),
            "custom" => Ok(OperationType::Custom),
            _ => Err(AppError::Validation(format!("Unknown operation type: {}", s))),
        }
    }

    pub fn to_str(&self) -> &'static str {
        match self {
            OperationType::Create => "create",
            OperationType::Read => "read",
            OperationType::Update => "update",
            OperationType::Delete => "delete",
            OperationType::Login => "login",
            OperationType::Logout => "logout",
            OperationType::Access => "access",
            OperationType::Modify => "modify",
            OperationType::Execute => "execute",
            OperationType::Admin => "admin",
            OperationType::Audit => "audit",
            OperationType::Custom => "custom",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditLogEntry {
    pub log_id: String,
    pub sequence: u64,
    pub operation_type: OperationType,
    pub user_id: String,
    pub resource_type: String,
    pub resource_id: String,
    pub action: String,
    pub timestamp: DateTime<Utc>,
    pub ip_address: Option<String>,
    pub user_agent: Option<String>,
    pub success: bool,
    pub details: serde_json::Value,
    pub previous_hash: String,
    pub current_hash: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogBlock {
    pub block_id: String,
    pub block_number: u64,
    pub entries: Vec<AuditLogEntry>,
    pub previous_block_hash: String,
    pub merkle_root: String,
    pub timestamp: DateTime<Utc>,
    pub nonce: u64,
    pub block_hash: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditLogRequest {
    pub operation_type: OperationType,
    pub user_id: String,
    pub resource_type: String,
    pub resource_id: String,
    pub action: String,
    pub ip_address: Option<String>,
    pub user_agent: Option<String>,
    pub success: bool,
    pub details: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TamperDetectionResult {
    pub is_valid: bool,
    pub tampered_at: Option<u64>,
    pub tampered_log_id: Option<String>,
    pub details: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IntegrityReport {
    pub report_id: String,
    pub generated_at: DateTime<Utc>,
    pub total_logs: u64,
    pub total_blocks: u64,
    pub is_valid: bool,
    pub tamper_detected: bool,
    pub details: Vec<TamperDetectionResult>,
}

pub struct AuditLogManager {
    config: AuditLogConfig,
    logs: Arc<DashMap<String, AuditLogEntry>>,
    blocks: Arc<DashMap<String, LogBlock>>,
    sequence_counter: Arc<Mutex<u64>>,
    block_counter: Arc<Mutex<u64>>,
    last_hash: Arc<Mutex<String>>,
    last_block_hash: Arc<Mutex<String>>,
    pending_entries: Arc<Mutex<Vec<AuditLogEntry>>>,
    genesis_hash: String,
}

impl AuditLogManager {
    pub fn new(config: AuditLogConfig) -> Self {
        let genesis_hash = sha256_hex("genesis_block".as_bytes());

        Self {
            config,
            logs: Arc::new(DashMap::new()),
            blocks: Arc::new(DashMap::new()),
            sequence_counter: Arc::new(Mutex::new(0)),
            block_counter: Arc::new(Mutex::new(0)),
            last_hash: Arc::new(Mutex::new(genesis_hash.clone())),
            last_block_hash: Arc::new(Mutex::new(genesis_hash.clone())),
            pending_entries: Arc::new(Mutex::new(Vec::new())),
            genesis_hash,
        }
    }

    pub fn log(&self, request: AuditLogRequest) -> Result<AuditLogEntry, AppError> {
        let sequence = {
            let mut counter = self.sequence_counter.lock().unwrap();
            *counter += 1;
            *counter
        };

        let previous_hash = self.last_hash.lock().unwrap().clone();
        let timestamp = current_datetime();

        let log_id = generate_id("log");

        let entry_content = format!(
            "{}:{}:{}:{}:{}:{}:{}:{}",
            sequence,
            request.operation_type.to_str(),
            request.user_id,
            request.resource_type,
            request.resource_id,
            request.action,
            timestamp.timestamp(),
            serde_json::to_string(&request.details).unwrap_or_default(),
        );

        let hash_input = format!("{}:{}", previous_hash, entry_content);
        let current_hash = sha256_hex(hash_input.as_bytes());

        let entry = AuditLogEntry {
            log_id: log_id.clone(),
            sequence,
            operation_type: request.operation_type,
            user_id: request.user_id,
            resource_type: request.resource_type,
            resource_id: request.resource_id,
            action: request.action,
            timestamp,
            ip_address: request.ip_address,
            user_agent: request.user_agent,
            success: request.success,
            details: request.details,
            previous_hash: previous_hash.clone(),
            current_hash: current_hash.clone(),
        };

        {
            let mut last_hash = self.last_hash.lock().unwrap();
            *last_hash = current_hash;
        }

        self.logs.insert(log_id.clone(), entry.clone());

        {
            let mut pending = self.pending_entries.lock().unwrap();
            pending.push(entry.clone());

            if pending.len() >= self.config.max_logs_per_block {
                self.create_block_internal(&mut pending);
            }
        }

        Ok(entry)
    }

    fn create_block_internal(&self, entries: &mut Vec<AuditLogEntry>) {
        if entries.is_empty() {
            return;
        }

        let block_number = {
            let mut counter = self.block_counter.lock().unwrap();
            *counter += 1;
            *counter
        };

        let previous_block_hash = self.last_block_hash.lock().unwrap().clone();

        let block_entries: Vec<AuditLogEntry> = entries.drain(..).collect();

        let merkle_root = self.calculate_merkle_root(&block_entries);

        let mut nonce = 0u64;
        let block_hash = loop {
            let block_content = format!(
                "{}:{}:{}:{}:{}",
                block_number,
                previous_block_hash,
                merkle_root,
                current_datetime().timestamp(),
                nonce,
            );
            let hash = sha256_hex(block_content.as_bytes());
            
            if hash.starts_with("00") || nonce > 1000 {
                break hash;
            }
            nonce += 1;
        };

        let block = LogBlock {
            block_id: generate_id("blk"),
            block_number,
            entries: block_entries,
            previous_block_hash: previous_block_hash.clone(),
            merkle_root,
            timestamp: current_datetime(),
            nonce,
            block_hash: block_hash.clone(),
        };

        {
            let mut last_block_hash = self.last_block_hash.lock().unwrap();
            *last_block_hash = block_hash;
        }

        self.blocks.insert(block.block_id.clone(), block);
    }

    fn calculate_merkle_root(&self, entries: &[AuditLogEntry]) -> String {
        if entries.is_empty() {
            return sha256_hex("empty".as_bytes());
        }

        let mut hashes: Vec<String> = entries
            .iter()
            .map(|e| e.current_hash.clone())
            .collect();

        while hashes.len() > 1 {
            let mut next_level = Vec::new();
            for chunk in hashes.chunks(2) {
                let combined = if chunk.len() == 2 {
                    format!("{}{}", chunk[0], chunk[1])
                } else {
                    format!("{}{}", chunk[0], chunk[0])
                };
                next_level.push(sha256_hex(combined.as_bytes()));
            }
            hashes = next_level;
        }

        hashes.first().cloned().unwrap_or_default()
    }

    pub fn flush_to_block(&self) -> Result<LogBlock, AppError> {
        let mut pending = self.pending_entries.lock().unwrap();
        if pending.is_empty() {
            return Err(AppError::Validation("No pending entries to flush".to_string()));
        }

        self.create_block_internal(&mut pending);

        let block_id = self
            .blocks
            .iter()
            .max_by_key(|b| b.block_number)
            .map(|b| b.block_id.clone())
            .ok_or_else(|| AppError::Internal("Failed to get latest block".to_string()))?;

        self.blocks
            .get(&block_id)
            .map(|b| b.clone())
            .ok_or_else(|| AppError::Internal("Block not found after creation".to_string()))
    }

    pub fn get_log(&self, log_id: &str) -> Option<AuditLogEntry> {
        self.logs.get(log_id).map(|e| e.clone())
    }

    pub fn get_logs_by_user(&self, user_id: &str) -> Vec<AuditLogEntry> {
        self.logs
            .iter()
            .filter(|e| e.user_id == user_id)
            .map(|e| e.clone())
            .collect()
    }

    pub fn get_logs_by_resource(&self, resource_type: &str, resource_id: &str) -> Vec<AuditLogEntry> {
        self.logs
            .iter()
            .filter(|e| e.resource_type == resource_type && e.resource_id == resource_id)
            .map(|e| e.clone())
            .collect()
    }

    pub fn get_logs_by_type(&self, operation_type: OperationType) -> Vec<AuditLogEntry> {
        self.logs
            .iter()
            .filter(|e| e.operation_type == operation_type)
            .map(|e| e.clone())
            .collect()
    }

    pub fn get_all_logs(&self) -> Vec<AuditLogEntry> {
        let mut logs: Vec<AuditLogEntry> = self.logs.iter().map(|e| e.clone()).collect();
        logs.sort_by_key(|e| e.sequence);
        logs
    }

    pub fn get_block(&self, block_id: &str) -> Option<LogBlock> {
        self.blocks.get(block_id).map(|b| b.clone())
    }

    pub fn get_all_blocks(&self) -> Vec<LogBlock> {
        let mut blocks: Vec<LogBlock> = self.blocks.iter().map(|b| b.clone()).collect();
        blocks.sort_by_key(|b| b.block_number);
        blocks
    }

    pub fn verify_log_integrity(&self) -> TamperDetectionResult {
        let logs = self.get_all_logs();
        if logs.is_empty() {
            return TamperDetectionResult {
                is_valid: true,
                tampered_at: None,
                tampered_log_id: None,
                details: "No logs to verify".to_string(),
            };
        }

        let mut expected_prev_hash = self.genesis_hash.clone();

        for entry in logs {
            if entry.previous_hash != expected_prev_hash {
                return TamperDetectionResult {
                    is_valid: false,
                    tampered_at: Some(entry.sequence),
                    tampered_log_id: Some(entry.log_id.clone()),
                    details: format!(
                        "Hash chain broken at log {}: expected previous hash {}, found {}",
                        entry.log_id, expected_prev_hash, entry.previous_hash
                    ),
                };
            }

            let entry_content = format!(
                "{}:{}:{}:{}:{}:{}:{}:{}",
                entry.sequence,
                entry.operation_type.to_str(),
                entry.user_id,
                entry.resource_type,
                entry.resource_id,
                entry.action,
                entry.timestamp.timestamp(),
                serde_json::to_string(&entry.details).unwrap_or_default(),
            );

            let hash_input = format!("{}:{}", entry.previous_hash, entry_content);
            let expected_hash = sha256_hex(hash_input.as_bytes());

            if expected_hash != entry.current_hash {
                return TamperDetectionResult {
                    is_valid: false,
                    tampered_at: Some(entry.sequence),
                    tampered_log_id: Some(entry.log_id.clone()),
                    details: format!(
                        "Log {} has been tampered: hash mismatch",
                        entry.log_id
                    ),
                };
            }

            expected_prev_hash = entry.current_hash;
        }

        TamperDetectionResult {
            is_valid: true,
            tampered_at: None,
            tampered_log_id: None,
            details: "All logs verified successfully".to_string(),
        }
    }

    pub fn verify_blockchain_integrity(&self) -> TamperDetectionResult {
        let blocks = self.get_all_blocks();
        if blocks.is_empty() {
            return TamperDetectionResult {
                is_valid: true,
                tampered_at: None,
                tampered_log_id: None,
                details: "No blocks to verify".to_string(),
            };
        }

        let mut expected_prev_hash = self.genesis_hash.clone();

        for block in blocks {
            if block.previous_block_hash != expected_prev_hash {
                return TamperDetectionResult {
                    is_valid: false,
                    tampered_at: Some(block.block_number),
                    tampered_log_id: Some(block.block_id.clone()),
                    details: format!(
                        "Block chain broken at block {}: expected previous hash {}, found {}",
                        block.block_number, expected_prev_hash, block.previous_block_hash
                    ),
                };
            }

            let merkle_root = self.calculate_merkle_root(&block.entries);
            if merkle_root != block.merkle_root {
                return TamperDetectionResult {
                    is_valid: false,
                    tampered_at: Some(block.block_number),
                    tampered_log_id: Some(block.block_id.clone()),
                    details: format!(
                        "Block {} merkle root mismatch: entries have been modified",
                        block.block_number
                    ),
                };
            }

            expected_prev_hash = block.block_hash.clone();
        }

        TamperDetectionResult {
            is_valid: true,
            tampered_at: None,
            tampered_log_id: None,
            details: "All blocks verified successfully".to_string(),
        }
    }

    pub fn generate_integrity_report(&self) -> IntegrityReport {
        let log_result = self.verify_log_integrity();
        let block_result = self.verify_blockchain_integrity();

        let total_logs = self.logs.len() as u64;
        let total_blocks = self.blocks.len() as u64;

        let is_valid = log_result.is_valid && block_result.is_valid;
        let tamper_detected = !is_valid;

        let mut details = Vec::new();
        if !log_result.is_valid {
            details.push(log_result);
        }
        if !block_result.is_valid {
            details.push(block_result);
        }

        IntegrityReport {
            report_id: generate_id("rpt"),
            generated_at: current_datetime(),
            total_logs,
            total_blocks,
            is_valid,
            tamper_detected,
            details,
        }
    }

    pub fn logs_count(&self) -> usize {
        self.logs.len()
    }

    pub fn blocks_count(&self) -> usize {
        self.blocks.len()
    }

    pub fn get_genesis_hash(&self) -> &str {
        &self.genesis_hash
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditLogEvent {
    pub event_type: String,
    pub log_id: Option<String>,
    pub block_id: Option<String>,
    pub timestamp: DateTime<Utc>,
    pub details: serde_json::Value,
}

impl AuditLogEvent {
    pub fn new(
        event_type: &str,
        log_id: Option<String>,
        block_id: Option<String>,
        details: serde_json::Value,
    ) -> Self {
        Self {
            event_type: event_type.to_string(),
            log_id,
            block_id,
            timestamp: current_datetime(),
            details,
        }
    }
}
