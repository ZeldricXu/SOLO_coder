use crate::error::PlatformError;
use crate::types::AuditLogEntry;
use crate::utils::{current_timestamp, hash_bytes, hash_string};
use chrono::Utc;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tracing::{info, warn, error};
use uuid::Uuid;

const GENESIS_HASH: &str = "0000000000000000000000000000000000000000000000000000000000000000";

struct AuditState {
    entries: Vec<AuditLogEntry>,
    entry_map: HashMap<String, usize>,
    sequence_counter: u64,
    last_hash: String,
    is_verified: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditVerificationResult {
    pub entry_id: String,
    pub sequence: u64,
    pub is_valid: bool,
    pub computed_hash: Option<String>,
    pub stored_hash: Option<String>,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditChainReport {
    pub total_entries: u64,
    pub valid_entries: u64,
    pub invalid_entries: u64,
    pub first_entry_timestamp: Option<String>,
    pub last_entry_timestamp: Option<String>,
    pub chain_root_hash: String,
    pub is_chain_intact: bool,
    pub verification_results: Vec<AuditVerificationResult>,
}

pub struct AuditLogChain {
    state: Arc<RwLock<AuditState>>,
}

impl AuditLogChain {
    pub fn new() -> Self {
        AuditLogChain {
            state: Arc::new(RwLock::new(AuditState {
                entries: Vec::new(),
                entry_map: HashMap::new(),
                sequence_counter: 0,
                last_hash: GENESIS_HASH.to_string(),
                is_verified: true,
            })),
        }
    }

    pub async fn append(
        &self,
        actor: &str,
        operation: &str,
        resource: &str,
        details: serde_json::Value,
    ) -> Result<AuditLogEntry, PlatformError> {
        if actor.is_empty() {
            return Err(PlatformError::Validation("Actor cannot be empty".to_string()));
        }
        
        if operation.is_empty() {
            return Err(PlatformError::Validation("Operation cannot be empty".to_string()));
        }
        
        let mut state = self.state.write();
        
        let sequence = state.sequence_counter;
        let previous_hash = state.last_hash.clone();
        
        let entry_id = format!("audit_{}", Uuid::new_v4().simple());
        let timestamp = current_timestamp();
        
        let details_str = serde_json::to_string(&details)
            .map_err(|e| PlatformError::Internal(format!("Failed to serialize details: {}", e)))?;
        
        let mut hash_input = Vec::new();
        hash_input.extend_from_slice(entry_id.as_bytes());
        hash_input.extend_from_slice(&sequence.to_be_bytes());
        hash_input.extend_from_slice(previous_hash.as_bytes());
        hash_input.extend_from_slice(timestamp.to_rfc3339().as_bytes());
        hash_input.extend_from_slice(operation.as_bytes());
        hash_input.extend_from_slice(actor.as_bytes());
        hash_input.extend_from_slice(resource.as_bytes());
        hash_input.extend_from_slice(details_str.as_bytes());
        
        let hash = hash_bytes(&hash_input);
        
        let entry = AuditLogEntry {
            entry_id: entry_id.clone(),
            sequence,
            previous_hash: previous_hash.clone(),
            hash: hash.clone(),
            timestamp,
            operation: operation.to_string(),
            actor: actor.to_string(),
            resource: resource.to_string(),
            details,
        };
        
        state.entry_map.insert(entry_id.clone(), state.entries.len());
        state.entries.push(entry.clone());
        state.sequence_counter += 1;
        state.last_hash = hash;
        
        info!(
            entry_id = %entry_id,
            sequence = sequence,
            operation = %operation,
            actor = %actor,
            resource = %resource,
            "Audit log entry appended"
        );
        
        Ok(entry)
    }

    pub fn verify_entry(&self, entry_id: &str) -> Result<AuditVerificationResult, PlatformError> {
        let state = self.state.read();
        
        let index = state.entry_map.get(entry_id)
            .copied()
            .ok_or_else(|| PlatformError::NotFound(format!("Audit entry {} not found", entry_id)))?;
        
        let entry = &state.entries[index];
        
        let mut hash_input = Vec::new();
        let details_str = serde_json::to_string(&entry.details).unwrap_or_default();
        
        hash_input.extend_from_slice(entry.entry_id.as_bytes());
        hash_input.extend_from_slice(&entry.sequence.to_be_bytes());
        hash_input.extend_from_slice(entry.previous_hash.as_bytes());
        hash_input.extend_from_slice(entry.timestamp.to_rfc3339().as_bytes());
        hash_input.extend_from_slice(entry.operation.as_bytes());
        hash_input.extend_from_slice(entry.actor.as_bytes());
        hash_input.extend_from_slice(entry.resource.as_bytes());
        hash_input.extend_from_slice(details_str.as_bytes());
        
        let computed_hash = hash_bytes(&hash_input);
        let is_valid = computed_hash == entry.hash;
        
        if !is_valid {
            warn!(
                entry_id = %entry_id,
                sequence = entry.sequence,
                "Audit entry verification failed - possible tampering detected"
            );
        }
        
        Ok(AuditVerificationResult {
            entry_id: entry.entry_id.clone(),
            sequence: entry.sequence,
            is_valid,
            computed_hash: Some(computed_hash),
            stored_hash: Some(entry.hash.clone()),
            error_message: if is_valid {
                None
            } else {
                Some("Hash mismatch detected - entry may have been tampered with".to_string())
            },
        })
    }

    pub fn verify_chain(&self) -> Result<AuditChainReport, PlatformError> {
        let state = self.state.read();
        
        let mut valid_entries = 0;
        let mut invalid_entries = 0;
        let mut verification_results = Vec::with_capacity(state.entries.len());
        let mut previous_hash = GENESIS_HASH.to_string();
        let mut chain_is_intact = true;
        
        for entry in &state.entries {
            if entry.previous_hash != previous_hash {
                chain_is_intact = false;
                warn!(
                    entry_id = %entry.entry_id,
                    expected_prev_hash = %previous_hash,
                    actual_prev_hash = %entry.previous_hash,
                    "Chain link broken - previous hash mismatch"
                );
            }
            
            let details_str = serde_json::to_string(&entry.details).unwrap_or_default();
            let mut hash_input = Vec::new();
            hash_input.extend_from_slice(entry.entry_id.as_bytes());
            hash_input.extend_from_slice(&entry.sequence.to_be_bytes());
            hash_input.extend_from_slice(entry.previous_hash.as_bytes());
            hash_input.extend_from_slice(entry.timestamp.to_rfc3339().as_bytes());
            hash_input.extend_from_slice(entry.operation.as_bytes());
            hash_input.extend_from_slice(entry.actor.as_bytes());
            hash_input.extend_from_slice(entry.resource.as_bytes());
            hash_input.extend_from_slice(details_str.as_bytes());
            
            let computed_hash = hash_bytes(&hash_input);
            let is_valid = computed_hash == entry.hash;
            
            if is_valid {
                valid_entries += 1;
            } else {
                invalid_entries += 1;
                chain_is_intact = false;
            }
            
            verification_results.push(AuditVerificationResult {
                entry_id: entry.entry_id.clone(),
                sequence: entry.sequence,
                is_valid,
                computed_hash: Some(computed_hash),
                stored_hash: Some(entry.hash.clone()),
                error_message: if is_valid {
                    None
                } else {
                    Some("Hash mismatch detected".to_string())
                },
            });
            
            previous_hash = entry.hash.clone();
        }
        
        let report = AuditChainReport {
            total_entries: state.sequence_counter,
            valid_entries,
            invalid_entries,
            first_entry_timestamp: state.entries.first().map(|e| e.timestamp.to_rfc3339()),
            last_entry_timestamp: state.entries.last().map(|e| e.timestamp.to_rfc3339()),
            chain_root_hash: state.last_hash.clone(),
            is_chain_intact: chain_is_intact,
            verification_results,
        };
        
        if chain_is_intact {
            info!("Audit chain verification passed - {} entries verified", valid_entries);
        } else {
            error!(
                valid_entries = valid_entries,
                invalid_entries = invalid_entries,
                "Audit chain verification FAILED - tampering detected"
            );
        }
        
        Ok(report)
    }

    pub fn get_entry(&self, entry_id: &str) -> Option<AuditLogEntry> {
        let state = self.state.read();
        state.entry_map.get(entry_id)
            .and_then(|&idx| state.entries.get(idx).cloned())
    }

    pub fn get_entry_by_sequence(&self, sequence: u64) -> Option<AuditLogEntry> {
        let state = self.state.read();
        state.entries.iter()
            .find(|e| e.sequence == sequence)
            .cloned()
    }

    pub fn get_entries_by_actor(&self, actor: &str) -> Vec<AuditLogEntry> {
        let state = self.state.read();
        state.entries.iter()
            .filter(|e| e.actor == actor)
            .cloned()
            .collect()
    }

    pub fn get_entries_by_operation(&self, operation: &str) -> Vec<AuditLogEntry> {
        let state = self.state.read();
        state.entries.iter()
            .filter(|e| e.operation == operation)
            .cloned()
            .collect()
    }

    pub fn get_entries_by_resource(&self, resource: &str) -> Vec<AuditLogEntry> {
        let state = self.state.read();
        state.entries.iter()
            .filter(|e| e.resource == resource)
            .cloned()
            .collect()
    }

    pub fn get_entries_in_range(&self, start_seq: u64, end_seq: u64) -> Vec<AuditLogEntry> {
        let state = self.state.read();
        state.entries.iter()
            .filter(|e| e.sequence >= start_seq && e.sequence <= end_seq)
            .cloned()
            .collect()
    }

    pub fn get_recent_entries(&self, limit: usize) -> Vec<AuditLogEntry> {
        let state = self.state.read();
        state.entries.iter()
            .rev()
            .take(limit)
            .cloned()
            .collect()
    }

    pub fn len(&self) -> usize {
        let state = self.state.read();
        state.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        let state = self.state.read();
        state.entries.is_empty()
    }

    pub fn get_chain_root_hash(&self) -> String {
        let state = self.state.read();
        state.last_hash.clone()
    }

    pub fn get_sequence_counter(&self) -> u64 {
        let state = self.state.read();
        state.sequence_counter
    }

    pub fn export_chain(&self) -> Vec<AuditLogEntry> {
        let state = self.state.read();
        state.entries.clone()
    }

    pub async fn import_chain(&self, entries: Vec<AuditLogEntry>) -> Result<(), PlatformError> {
        if entries.is_empty() {
            return Err(PlatformError::Validation("Cannot import empty chain".to_string()));
        }
        
        let mut sorted_entries = entries.clone();
        sorted_entries.sort_by_key(|e| e.sequence);
        
        let mut previous_hash = GENESIS_HASH.to_string();
        let mut expected_sequence = 0;
        
        for entry in &sorted_entries {
            if entry.sequence != expected_sequence {
                return Err(PlatformError::AuditTampered(format!(
                    "Sequence gap detected: expected {}, got {}",
                    expected_sequence,
                    entry.sequence
                )));
            }
            
            if entry.previous_hash != previous_hash {
                return Err(PlatformError::AuditTampered(format!(
                    "Previous hash mismatch at sequence {}",
                    entry.sequence
                )));
            }
            
            let details_str = serde_json::to_string(&entry.details)
                .map_err(|e| PlatformError::Internal(format!("Serialization error: {}", e)))?;
            
            let mut hash_input = Vec::new();
            hash_input.extend_from_slice(entry.entry_id.as_bytes());
            hash_input.extend_from_slice(&entry.sequence.to_be_bytes());
            hash_input.extend_from_slice(entry.previous_hash.as_bytes());
            hash_input.extend_from_slice(entry.timestamp.to_rfc3339().as_bytes());
            hash_input.extend_from_slice(entry.operation.as_bytes());
            hash_input.extend_from_slice(entry.actor.as_bytes());
            hash_input.extend_from_slice(entry.resource.as_bytes());
            hash_input.extend_from_slice(details_str.as_bytes());
            
            let computed_hash = hash_bytes(&hash_input);
            if computed_hash != entry.hash {
                return Err(PlatformError::AuditTampered(format!(
                    "Hash verification failed at sequence {}",
                    entry.sequence
                )));
            }
            
            previous_hash = entry.hash.clone();
            expected_sequence += 1;
        }
        
        let mut state = self.state.write();
        state.entries = sorted_entries.clone();
        state.entry_map.clear();
        
        for (idx, entry) in sorted_entries.iter().enumerate() {
            state.entry_map.insert(entry.entry_id.clone(), idx);
        }
        
        state.sequence_counter = expected_sequence;
        state.last_hash = previous_hash;
        state.is_verified = true;
        
        info!("Audit chain imported successfully - {} entries", sorted_entries.len());
        
        Ok(())
    }

    pub fn compute_merkle_root(&self) -> String {
        let state = self.state.read();
        
        if state.entries.is_empty() {
            return GENESIS_HASH.to_string();
        }
        
        let mut leaves: Vec<String> = state.entries.iter()
            .map(|e| e.hash.clone())
            .collect();
        
        while leaves.len() > 1 {
            let mut next_level = Vec::new();
            let mut i = 0;
            
            while i < leaves.len() {
                let left = &leaves[i];
                let right = if i + 1 < leaves.len() {
                    &leaves[i + 1]
                } else {
                    &leaves[i]
                };
                
                let combined = format!("{}{}", left, right);
                next_level.push(hash_string(&combined));
                i += 2;
            }
            
            leaves = next_level;
        }
        
        leaves.first().cloned().unwrap_or_else(|| GENESIS_HASH.to_string())
    }

    pub fn generate_proof(&self, entry_id: &str) -> Result<Vec<String>, PlatformError> {
        let state = self.state.read();
        
        let idx = state.entry_map.get(entry_id)
            .copied()
            .ok_or_else(|| PlatformError::NotFound(format!("Entry {} not found", entry_id)))?;
        
        if state.entries.is_empty() {
            return Err(PlatformError::NotFound("Chain is empty".to_string()));
        }
        
        let mut proof = Vec::new();
        let mut tree: Vec<Vec<String>> = Vec::new();
        
        let mut current_level: Vec<String> = state.entries.iter()
            .map(|e| e.hash.clone())
            .collect();
        
        tree.push(current_level.clone());
        
        while current_level.len() > 1 {
            let mut next_level = Vec::new();
            let mut i = 0;
            
            while i < current_level.len() {
                let left = &current_level[i];
                let right = if i + 1 < current_level.len() {
                    &current_level[i + 1]
                } else {
                    &current_level[i]
                };
                
                let combined = format!("{}{}", left, right);
                next_level.push(hash_string(&combined));
                i += 2;
            }
            
            tree.push(next_level.clone());
            current_level = next_level;
        }
        
        let mut current_idx = idx;
        
        for level in 0..tree.len() - 1 {
            let level_nodes = &tree[level];
            
            let sibling_idx = if current_idx % 2 == 0 {
                if current_idx + 1 < level_nodes.len() {
                    current_idx + 1
                } else {
                    current_idx
                }
            } else {
                current_idx - 1
            };
            
            if sibling_idx != current_idx {
                proof.push(level_nodes[sibling_idx].clone());
            }
            
            current_idx = current_idx / 2;
        }
        
        Ok(proof)
    }

    pub fn verify_proof(
        &self,
        entry_id: &str,
        proof: Vec<String>,
        expected_root: &str,
    ) -> Result<bool, PlatformError> {
        let state = self.state.read();
        
        let entry = state.entry_map.get(entry_id)
            .and_then(|&idx| state.entries.get(idx))
            .ok_or_else(|| PlatformError::NotFound(format!("Entry {} not found", entry_id)))?;
        
        let mut current_hash = entry.hash.clone();
        
        for sibling in proof {
            let combined = format!("{}{}", current_hash, sibling);
            current_hash = hash_string(&combined);
        }
        
        Ok(current_hash == expected_root)
    }
}

impl Default for AuditLogChain {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_append_and_verify_entry() {
        let chain = AuditLogChain::new();
        
        let entry = chain.append(
            "test_user",
            "create",
            "resource_001",
            serde_json::json!({ "action": "test" }),
        ).await.unwrap();
        
        let result = chain.verify_entry(&entry.entry_id).unwrap();
        assert!(result.is_valid);
    }

    #[tokio::test]
    async fn test_chain_verification() {
        let chain = AuditLogChain::new();
        
        for i in 0..5 {
            chain.append(
                "user_001",
                "operation",
                &format!("resource_{}", i),
                serde_json::json!({ "index": i }),
            ).await.unwrap();
        }
        
        let report = chain.verify_chain().unwrap();
        assert_eq!(report.total_entries, 5);
        assert_eq!(report.valid_entries, 5);
        assert_eq!(report.invalid_entries, 0);
        assert!(report.is_chain_intact);
    }

    #[tokio::test]
    async fn test_merkle_root() {
        let chain = AuditLogChain::new();
        
        for i in 0..4 {
            chain.append(
                "test_user",
                "test",
                &format!("res_{}", i),
                serde_json::json!({ "i": i }),
            ).await.unwrap();
        }
        
        let root = chain.compute_merkle_root();
        assert!(!root.is_empty());
    }

    #[tokio::test]
    async fn test_retrieve_entries() {
        let chain = AuditLogChain::new();
        
        chain.append(
            "alice",
            "create",
            "resource_001",
            serde_json::json!({}),
        ).await.unwrap();
        
        chain.append(
            "bob",
            "update",
            "resource_001",
            serde_json::json!({}),
        ).await.unwrap();
        
        let alice_entries = chain.get_entries_by_actor("alice");
        assert_eq!(alice_entries.len(), 1);
        
        let update_entries = chain.get_entries_by_operation("update");
        assert_eq!(update_entries.len(), 1);
    }
}
