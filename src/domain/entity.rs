use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Entity {
    pub id: String,
    pub entity_type: String,
    pub status: String,
    pub attributes: HashMap<String, String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl Entity {
    pub fn new(entity_type: impl Into<String>, attributes: HashMap<String, String>) -> Self {
        let now = Utc::now();
        Self {
            id: format!("ent_{}", Uuid::new_v4().simple()),
            entity_type: entity_type.into(),
            status: "created".to_string(),
            attributes,
            created_at: now,
            updated_at: now,
        }
    }

    pub fn update_status(&mut self, status: impl Into<String>) {
        self.status = status.into();
        self.updated_at = Utc::now();
    }

    pub fn set_attribute(&mut self, key: impl Into<String>, value: impl Into<String>) {
        self.attributes.insert(key.into(), value.into());
        self.updated_at = Utc::now();
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SignedRequest {
    pub payload: Vec<u8>,
    pub signature: String,
    pub timestamp: i64,
    pub public_key: String,
}

impl SignedRequest {
    pub fn verify_timestamp(&self, max_age_seconds: i64) -> bool {
        let now = Utc::now().timestamp();
        (now - self.timestamp).abs() <= max_age_seconds
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BinaryResponse {
    pub length_prefix: u32,
    pub payload: Vec<u8>,
    pub checksum: u32,
}

impl BinaryResponse {
    pub fn new(payload: Vec<u8>) -> Self {
        let length_prefix = payload.len() as u32;
        let checksum = Self::compute_checksum(&payload);
        Self {
            length_prefix,
            payload,
            checksum,
        }
    }

    fn compute_checksum(data: &[u8]) -> u32 {
        use sha2::{Digest, Sha256};
        let mut hasher = Sha256::new();
        hasher.update(data);
        let result = hasher.finalize();
        let mut checksum = 0u32;
        for &byte in result.iter().take(4) {
            checksum = (checksum << 8) | byte as u32;
        }
        checksum
    }

    pub fn verify(&self) -> bool {
        self.length_prefix == self.payload.len() as u32
            && Self::compute_checksum(&self.payload) == self.checksum
    }
}
