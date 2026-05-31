use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

use crate::domain::run_instance::RunInstance;
use crate::infra::config::AuditConfig;
use crate::infra::crypto::CryptoService;
use crate::infra::error::{AppError, AppResult};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditLogEntry {
    pub log_id: String,
    pub timestamp: chrono::DateTime<chrono::Utc>,
    pub actor: String,
    pub action: String,
    pub resource_type: String,
    pub resource_id: Option<String>,
    pub details: Option<serde_json::Value>,
    pub previous_hash: String,
    pub current_hash: String,
    pub block_height: u64,
    pub signature: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogBlock {
    pub block_height: u64,
    pub entries: Vec<AuditLogEntry>,
    pub merkle_root: String,
    pub previous_block_hash: String,
    pub timestamp: chrono::DateTime<chrono::Utc>,
    pub nonce: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditQuery {
    pub start_time: Option<chrono::DateTime<chrono::Utc>>,
    pub end_time: Option<chrono::DateTime<chrono::Utc>>,
    pub actor: Option<String>,
    pub action: Option<String>,
    pub resource_type: Option<String>,
    pub resource_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IntegrityCheckResult {
    pub valid: bool,
    pub first_invalid_block: Option<u64>,
    pub checked_blocks: u64,
    pub total_blocks: u64,
    pub errors: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogEntryRequest {
    pub actor: String,
    pub action: String,
    pub resource_type: String,
    pub resource_id: Option<String>,
    pub details: Option<serde_json::Value>,
}

pub struct AuditLogService {
    config: AuditConfig,
    logs: std::sync::Arc<parking_lot::Mutex<Vec<AuditLogEntry>>>,
    blocks: std::sync::Arc<parking_lot::Mutex<Vec<LogBlock>>>,
    current_block_entries: std::sync::Arc<parking_lot::Mutex<Vec<AuditLogEntry>>>,
    genesis_hash: String,
    last_hash: std::sync::Arc<parking_lot::Mutex<String>>,
    block_height: std::sync::Arc<parking_lot::Mutex<u64>>,
}

impl AuditLogService {
    pub fn new(config: AuditConfig) -> Self {
        let genesis_hash = CryptoService::sha256_hex(b"zero_trust_genesis_block");

        Self {
            config,
            logs: std::sync::Arc::new(parking_lot::Mutex::new(Vec::new())),
            blocks: std::sync::Arc::new(parking_lot::Mutex::new(Vec::new())),
            current_block_entries: std::sync::Arc::new(parking_lot::Mutex::new(Vec::new())),
            genesis_hash: genesis_hash.clone(),
            last_hash: std::sync::Arc::new(parking_lot::Mutex::new(genesis_hash)),
            block_height: std::sync::Arc::new(parking_lot::Mutex::new(0)),
        }
    }

    pub async fn log_event(&self, request: LogEntryRequest) -> AppResult<AuditLogEntry> {
        let previous_hash = self.last_hash.lock().clone();
        let mut block_height = self.block_height.lock();
        let current_height = *block_height;

        let mut data_to_hash = Vec::new();
        data_to_hash.extend_from_slice(previous_hash.as_bytes());
        data_to_hash.extend_from_slice(request.actor.as_bytes());
        data_to_hash.extend_from_slice(request.action.as_bytes());
        data_to_hash.extend_from_slice(request.resource_type.as_bytes());
        if let Some(rid) = &request.resource_id {
            data_to_hash.extend_from_slice(rid.as_bytes());
        }
        if let Some(details) = &request.details {
            if let Ok(s) = serde_json::to_string(details) {
                data_to_hash.extend_from_slice(s.as_bytes());
            }
        }
        data_to_hash.extend_from_slice(&current_height.to_le_bytes());

        let timestamp = chrono::Utc::now();
        data_to_hash.extend_from_slice(&timestamp.timestamp_nanos().to_le_bytes());

        let current_hash = CryptoService::sha256_hex(&data_to_hash);

        let entry = AuditLogEntry {
            log_id: format!("log_{}", Uuid::new_v4().simple()),
            timestamp,
            actor: request.actor,
            action: request.action,
            resource_type: request.resource_type,
            resource_id: request.resource_id,
            details: request.details,
            previous_hash: previous_hash.clone(),
            current_hash: current_hash.clone(),
            block_height: current_height,
            signature: None,
        };

        *self.last_hash.lock() = current_hash;
        *block_height += 1;

        self.logs.lock().push(entry.clone());
        self.current_block_entries.lock().push(entry.clone());

        Ok(entry)
    }

    pub async fn seal_block(&self) -> AppResult<Option<LogBlock>> {
        let mut entries = self.current_block_entries.lock();
        if entries.is_empty() {
            return Ok(None);
        }

        let current_entries: Vec<AuditLogEntry> = entries.drain(..).collect();
        drop(entries);

        let blocks = self.blocks.lock();
        let previous_block_hash = blocks
            .last()
            .map(|b| Self::hash_block(b))
            .unwrap_or_else(|| self.genesis_hash.clone());
        drop(blocks);

        let merkle_root = Self::compute_merkle_root(&current_entries);

        let mut nonce = 0u64;
        let timestamp = chrono::Utc::now();

        let block = LogBlock {
            block_height: current_entries.first().map(|e| e.block_height).unwrap_or(0),
            entries: current_entries,
            merkle_root,
            previous_block_hash,
            timestamp,
            nonce,
        };

        self.blocks.lock().push(block.clone());

        Ok(Some(block))
    }

    pub fn compute_merkle_root(entries: &[AuditLogEntry]) -> String {
        if entries.is_empty() {
            return CryptoService::sha256_hex(b"empty");
        }

        let mut hashes: Vec<String> = entries
            .iter()
            .map(|e| e.current_hash.clone())
            .collect();

        while hashes.len() > 1 {
            if hashes.len() % 2 != 0 {
                hashes.push(hashes.last().unwrap().clone());
            }

            let mut new_hashes = Vec::new();
            for i in (0..hashes.len()).step_by(2) {
                let combined = format!("{}{}", hashes[i], hashes[i + 1]);
                new_hashes.push(CryptoService::sha256_hex(combined.as_bytes()));
            }
            hashes = new_hashes;
        }

        hashes.pop().unwrap_or_else(|| CryptoService::sha256_hex(b"empty"))
    }

    fn hash_block(block: &LogBlock) -> String {
        let mut data = Vec::new();
        data.extend_from_slice(&block.block_height.to_le_bytes());
        data.extend_from_slice(block.merkle_root.as_bytes());
        data.extend_from_slice(block.previous_block_hash.as_bytes());
        data.extend_from_slice(&block.timestamp.timestamp_nanos().to_le_bytes());
        data.extend_from_slice(&block.nonce.to_le_bytes());
        CryptoService::sha256_hex(&data)
    }

    pub async fn verify_integrity(&self) -> AppResult<IntegrityCheckResult> {
        let blocks = self.blocks.lock();
        let mut result = IntegrityCheckResult {
            valid: true,
            first_invalid_block: None,
            checked_blocks: 0,
            total_blocks: blocks.len() as u64,
            errors: Vec::new(),
        };

        let mut prev_hash = self.genesis_hash.clone();

        for (i, block) in blocks.iter().enumerate() {
            result.checked_blocks = i as u64 + 1;

            if block.previous_block_hash != prev_hash {
                result.valid = false;
                result.first_invalid_block = Some(block.block_height);
                result.errors.push(format!(
                    "Block {}: Previous hash mismatch. Expected: {}, Got: {}",
                    block.block_height, prev_hash, block.previous_block_hash
                ));
                break;
            }

            let computed_merkle = Self::compute_merkle_root(&block.entries);
            if computed_merkle != block.merkle_root {
                result.valid = false;
                result.first_invalid_block = Some(block.block_height);
                result.errors.push(format!(
                    "Block {}: Merkle root mismatch. Expected: {}, Got: {}",
                    block.block_height, block.merkle_root, computed_merkle
                ));
                break;
            }

            for entry in &block.entries {
                let mut data_to_hash = Vec::new();
                data_to_hash.extend_from_slice(entry.previous_hash.as_bytes());
                data_to_hash.extend_from_slice(entry.actor.as_bytes());
                data_to_hash.extend_from_slice(entry.action.as_bytes());
                data_to_hash.extend_from_slice(entry.resource_type.as_bytes());
                if let Some(rid) = &entry.resource_id {
                    data_to_hash.extend_from_slice(rid.as_bytes());
                }
                if let Some(details) = &entry.details {
                    if let Ok(s) = serde_json::to_string(details) {
                        data_to_hash.extend_from_slice(s.as_bytes());
                    }
                }
                data_to_hash.extend_from_slice(&entry.block_height.to_le_bytes());
                data_to_hash.extend_from_slice(&entry.timestamp.timestamp_nanos().to_le_bytes());

                let computed_hash = CryptoService::sha256_hex(&data_to_hash);
                if computed_hash != entry.current_hash {
                    result.valid = false;
                    result.first_invalid_block = Some(block.block_height);
                    result.errors.push(format!(
                        "Block {}: Log entry {} hash mismatch",
                        block.block_height, entry.log_id
                    ));
                    break;
                }
            }

            if !result.valid {
                break;
            }

            prev_hash = Self::hash_block(block);
        }

        Ok(result)
    }

    pub async fn query_logs(&self, query: AuditQuery) -> AppResult<Vec<AuditLogEntry>> {
        let logs = self.logs.lock();

        let filtered: Vec<AuditLogEntry> = logs
            .iter()
            .filter(|log| {
                if let Some(start) = query.start_time {
                    if log.timestamp < start {
                        return false;
                    }
                }
                if let Some(end) = query.end_time {
                    if log.timestamp > end {
                        return false;
                    }
                }
                if let Some(actor) = &query.actor {
                    if log.actor != *actor {
                        return false;
                    }
                }
                if let Some(action) = &query.action {
                    if log.action != *action {
                        return false;
                    }
                }
                if let Some(resource_type) = &query.resource_type {
                    if log.resource_type != *resource_type {
                        return false;
                    }
                }
                if let Some(resource_id) = &query.resource_id {
                    if log.resource_id.as_ref() != Some(resource_id) {
                        return false;
                    }
                }
                true
            })
            .cloned()
            .collect();

        Ok(filtered)
    }

    pub async fn get_log(&self, log_id: &str) -> AppResult<AuditLogEntry> {
        let logs = self.logs.lock();
        logs.iter()
            .find(|l| l.log_id == log_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Log entry {} not found", log_id)))
    }

    pub async fn get_block(&self, block_height: u64) -> AppResult<LogBlock> {
        let blocks = self.blocks.lock();
        blocks.iter()
            .find(|b| b.block_height == block_height)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Block {} not found", block_height)))
    }

    pub async fn list_blocks(&self, offset: u64, limit: u64) -> AppResult<Vec<LogBlock>> {
        let blocks = self.blocks.lock();
        Ok(blocks
            .iter()
            .skip(offset as usize)
            .take(limit as usize)
            .cloned()
            .collect())
    }

    pub async fn tamper_detection(&self) -> AppResult<Vec<String>> {
        let result = self.verify_integrity().await?;
        if result.valid {
            Ok(Vec::new())
        } else {
            Ok(result.errors)
        }
    }

    pub async fn export_logs_to_file(&self, path: &str) -> AppResult<()> {
        let logs = self.logs.lock();
        let json = serde_json::to_string_pretty(&*logs)
            .map_err(|e| AppError::InternalError(format!("Serialization error: {}", e)))?;
        std::fs::write(path, json)
            .map_err(|e| AppError::InternalError(format!("File write error: {}", e)))?;
        Ok(())
    }

    pub fn create_run_instance(&self, operation_id: &str) -> RunInstance {
        let mut instance = RunInstance::new(operation_id.to_string());
        instance.set_metadata("module", "audit");
        instance
    }
}
