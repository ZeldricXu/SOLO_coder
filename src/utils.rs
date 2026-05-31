use sha2::{Sha256, Digest};
use hmac::{Hmac, Mac};
use chrono::{DateTime, Utc};
use uuid::Uuid;
use std::time::SystemTime;

pub fn init_tracing() {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .with_target(true)
        .with_thread_ids(true)
        .init();
}

pub fn generate_uuid() -> String {
    Uuid::new_v4().to_string()
}

pub fn current_timestamp() -> DateTime<Utc> {
    Utc::now()
}

pub fn hash_bytes(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    let result = hasher.finalize();
    format!("{:x}", result)
}

pub fn hash_string(data: &str) -> String {
    hash_bytes(data.as_bytes())
}

pub fn compute_hmac_sha256(key: &[u8], data: &[u8]) -> Result<Vec<u8>, String> {
    type HmacSha256 = Hmac<Sha256>;
    let mut mac = HmacSha256::new_from_slice(key)
        .map_err(|e| format!("HMAC initialization failed: {}", e))?;
    mac.update(data);
    Ok(mac.finalize().into_bytes().to_vec())
}

pub fn verify_hmac_sha256(key: &[u8], data: &[u8], signature: &[u8]) -> bool {
    type HmacSha256 = Hmac<Sha256>;
    if let Ok(mut mac) = HmacSha256::new_from_slice(key) {
        mac.update(data);
        mac.verify_slice(signature).is_ok()
    } else {
        false
    }
}

pub fn system_time_to_datetime(system_time: SystemTime) -> DateTime<Utc> {
    system_time.into()
}

pub fn format_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

pub fn parse_hex(hex: &str) -> Result<Vec<u8>, String> {
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16)
            .map_err(|e| format!("Invalid hex character at position {}: {}", i, e)))
        .collect()
}

pub fn validate_timestamp(timestamp: DateTime<Utc>, max_age_seconds: i64) -> bool {
    let now = Utc::now();
    let diff = now.signed_duration_since(timestamp);
    diff.num_seconds() <= max_age_seconds && diff.num_seconds() >= 0
}

pub fn checksum_with_prefix(data: &[u8]) -> Vec<u8> {
    let length = data.len() as u32;
    let checksum = hash_bytes(data);
    
    let mut result = Vec::with_capacity(4 + data.len() + 32);
    result.extend_from_slice(&length.to_be_bytes());
    result.extend_from_slice(data);
    result.extend_from_slice(checksum.as_bytes());
    
    result
}

pub fn verify_and_extract_prefix(encoded: &[u8]) -> Result<Vec<u8>, String> {
    if encoded.len() < 4 {
        return Err("Data too short to contain length prefix".to_string());
    }
    
    let length_bytes: [u8; 4] = encoded[..4].try_into()
        .map_err(|_| "Invalid length prefix".to_string())?;
    let length = u32::from_be_bytes(length_bytes) as usize;
    
    if 4 + length + 32 > encoded.len() {
        return Err("Invalid data length or missing checksum".to_string());
    }
    
    let data = &encoded[4..4 + length];
    let stored_checksum = &encoded[4 + length..4 + length + 32];
    let computed_checksum = hash_bytes(data);
    
    if stored_checksum != computed_checksum.as_bytes() {
        return Err("Checksum verification failed".to_string());
    }
    
    Ok(data.to_vec())
}
