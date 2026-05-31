use chrono::{DateTime, Utc};
use rand::Rng;
use rand_chacha::ChaCha20Rng;
use rand::SeedableRng;
use serde::{Deserialize, Serialize};
use sha2::{Sha256, Digest};
use hmac::{Hmac, Mac};
use uuid::Uuid;

pub fn generate_id(prefix: &str) -> String {
    format!("{}_{}", prefix, Uuid::new_v4().simple())
}

pub fn current_timestamp() -> i64 {
    Utc::now().timestamp()
}

pub fn current_datetime() -> DateTime<Utc> {
    Utc::now()
}

pub fn sha256_hash(data: &[u8]) -> Vec<u8> {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hasher.finalize().to_vec()
}

pub fn sha256_hex(data: &[u8]) -> String {
    hex::encode(sha256_hash(data))
}

pub fn hmac_sha256(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut mac = Hmac::<Sha256>::new_from_slice(key)
        .expect("HMAC can take key of any size");
    mac.update(data);
    mac.finalize().into_bytes().to_vec()
}

pub fn hmac_sha256_hex(key: &[u8], data: &[u8]) -> String {
    hex::encode(hmac_sha256(key, data))
}

pub fn create_rng(seed: Option<u64>) -> ChaCha20Rng {
    match seed {
        Some(s) => ChaCha20Rng::seed_from_u64(s),
        None => ChaCha20Rng::from_entropy(),
    }
}

pub fn random_bytes(len: usize) -> Vec<u8> {
    let mut rng = create_rng(None);
    let mut bytes = vec![0u8; len];
    rng.fill(&mut bytes[..]);
    bytes
}

pub fn random_u64() -> u64 {
    let mut rng = create_rng(None);
    rng.gen()
}

pub fn random_f64() -> f64 {
    let mut rng = create_rng(None);
    rng.gen()
}

pub fn verify_signature(data: &[u8], signature: &[u8], secret_key: &[u8]) -> bool {
    let expected = hmac_sha256(secret_key, data);
    constant_time_eq(&expected, signature)
}

pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut result = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        result |= x ^ y;
    }
    result == 0
}

pub fn validate_timestamp(timestamp: i64, max_age_secs: i64) -> bool {
    let now = current_timestamp();
    let diff = now - timestamp;
    diff >= 0 && diff <= max_age_secs
}

pub fn bytes_to_hex(bytes: &[u8]) -> String {
    hex::encode(bytes)
}

pub fn hex_to_bytes(hex_str: &str) -> Result<Vec<u8>, hex::FromHexError> {
    hex::decode(hex_str)
}

pub fn base64_encode(data: &[u8]) -> String {
    base64::encode(data)
}

pub fn base64_decode(data: &str) -> Result<Vec<u8>, base64::DecodeError> {
    base64::decode(data)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BinaryResponse {
    pub length_prefix: u32,
    pub checksum: [u8; 32],
    pub data: Vec<u8>,
}

impl BinaryResponse {
    pub fn new(data: Vec<u8>) -> Self {
        let checksum = {
            let hash = sha256_hash(&data);
            let mut arr = [0u8; 32];
            arr.copy_from_slice(&hash[..32]);
            arr
        };
        Self {
            length_prefix: data.len() as u32,
            checksum,
            data,
        }
    }

    pub fn verify(&self) -> bool {
        let expected = sha256_hash(&self.data);
        constant_time_eq(&expected, &self.checksum)
    }

    pub fn to_bytes(&self) -> Vec<u8> {
        let mut result = Vec::with_capacity(4 + 32 + self.data.len());
        result.extend_from_slice(&self.length_prefix.to_le_bytes());
        result.extend_from_slice(&self.checksum);
        result.extend_from_slice(&self.data);
        result
    }

    pub fn from_bytes(bytes: &[u8]) -> Option<Self> {
        if bytes.len() < 36 {
            return None;
        }
        let length_prefix = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
        let mut checksum = [0u8; 32];
        checksum.copy_from_slice(&bytes[4..36]);
        let data = bytes[36..].to_vec();
        if data.len() as u32 != length_prefix {
            return None;
        }
        Some(Self { length_prefix, checksum, data })
    }
}
