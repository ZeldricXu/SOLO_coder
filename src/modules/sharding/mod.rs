use num_bigint::{BigInt, RandBigInt, ToBigInt};
use num_traits::{One, Zero};
use rand::Rng;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

use crate::domain::run_instance::RunInstance;
use crate::infra::config::ShardingConfig;
use crate::infra::crypto::CryptoService;
use crate::infra::error::{AppError, AppResult};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KeyShare {
    pub share_id: String,
    pub key_id: String,
    pub index: u32,
    pub value: Vec<u8>,
    pub threshold: u32,
    pub total_shares: u32,
    pub owner: String,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub encrypted: bool,
    pub encryption_key_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShardedKey {
    pub key_id: String,
    pub algorithm: String,
    pub threshold: u32,
    pub total_shares: u32,
    pub key_size_bits: u32,
    pub shares: Vec<KeyShare>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub expires_at: Option<chrono::DateTime<chrono::Utc>>,
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateShardedKeyRequest {
    pub algorithm: Option<String>,
    pub threshold: Option<u32>,
    pub total_shares: Option<u32>,
    pub key_size_bits: Option<u32>,
    pub owners: Vec<String>,
    pub secret: Option<Vec<u8>>,
    pub ttl_seconds: Option<u64>,
    pub metadata: Option<HashMap<String, String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReconstructRequest {
    pub key_id: String,
    pub shares: Vec<KeyShare>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReconstructResponse {
    pub key_id: String,
    pub secret: Vec<u8>,
    pub checksum: String,
    pub verified: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RotateShareRequest {
    pub key_id: String,
    pub share_id: String,
    pub new_owner: Option<String>,
}

pub struct ShardingService {
    config: ShardingConfig,
    keys: std::sync::Arc<parking_lot::Mutex<HashMap<String, ShardedKey>>>,
    shares: std::sync::Arc<parking_lot::Mutex<HashMap<String, KeyShare>>>,
    prime: BigInt,
}

impl ShardingService {
    pub fn new(config: ShardingConfig) -> Self {
        let prime = BigInt::parse_bytes(
            b"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F",
            16,
        )
        .unwrap();

        Self {
            config,
            keys: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            shares: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            prime,
        }
    }

    pub async fn create_sharded_key(&self, request: CreateShardedKeyRequest) -> AppResult<ShardedKey> {
        let threshold = request.threshold.unwrap_or(self.config.default_threshold);
        let total_shares = request.total_shares.unwrap_or(self.config.default_total_shares);
        let key_size_bits = request.key_size_bits.unwrap_or(self.config.key_size_bits);

        if threshold == 0 || total_shares == 0 {
            return Err(AppError::ValidationError(
                "Threshold and total shares must be greater than 0".into(),
            ));
        }

        if threshold > total_shares {
            return Err(AppError::ValidationError(
                "Threshold cannot exceed total shares".into(),
            ));
        }

        if request.owners.len() as u32 != total_shares {
            return Err(AppError::ValidationError(format!(
                "Number of owners ({}) must match total shares ({})",
                request.owners.len(),
                total_shares
            )));
        }

        let secret = match request.secret {
            Some(s) => {
                if s.is_empty() {
                    return Err(AppError::ValidationError("Secret cannot be empty".into()));
                }
                s
            }
            None => CryptoService::generate_aes_key(),
        };

        let shares = self.generate_shamir_shares(&secret, threshold, total_shares)?;

        let key_id = format!("key_{}", Uuid::new_v4().simple());
        let now = chrono::Utc::now();
        let expires_at = request
            .ttl_seconds
            .map(|t| now + chrono::Duration::seconds(t as i64));

        let mut key_shares = Vec::new();
        for (i, (index, value)) in shares.iter().enumerate() {
            let share = KeyShare {
                share_id: format!("share_{}", Uuid::new_v4().simple()),
                key_id: key_id.clone(),
                index: *index as u32,
                value: value.to_bytes_be().1,
                threshold,
                total_shares,
                owner: request.owners[i].clone(),
                created_at: now,
                encrypted: false,
                encryption_key_id: None,
            };

            self.shares.lock().insert(share.share_id.clone(), share.clone());
            key_shares.push(share);
        }

        let sharded_key = ShardedKey {
            key_id: key_id.clone(),
            algorithm: request
                .algorithm
                .unwrap_or_else(|| self.config.algorithm.clone()),
            threshold,
            total_shares,
            key_size_bits,
            shares: key_shares,
            created_at: now,
            expires_at,
            metadata: request.metadata.unwrap_or_default(),
        };

        self.keys.lock().insert(key_id, sharded_key.clone());

        Ok(sharded_key)
    }

    fn generate_shamir_shares(
        &self,
        secret: &[u8],
        threshold: u32,
        total_shares: u32,
    ) -> AppResult<Vec<(BigInt, BigInt)>> {
        let secret_int = BigInt::from_bytes_be(num_bigint::Sign::Plus, secret);
        let secret_int = secret_int % &self.prime;

        let mut rng = rand::thread_rng();
        let mut coefficients: Vec<BigInt> = Vec::with_capacity(threshold as usize);
        coefficients.push(secret_int);

        for _ in 1..threshold {
            let coef = rng.gen_bigint_range(&BigInt::zero(), &self.prime);
            coefficients.push(coef);
        }

        let mut shares = Vec::with_capacity(total_shares as usize);
        for i in 1..=total_shares {
            let x = BigInt::from(i);
            let y = self.evaluate_polynomial(&coefficients, &x);
            shares.push((x, y % &self.prime));
        }

        Ok(shares)
    }

    fn evaluate_polynomial(&self, coefficients: &[BigInt], x: &BigInt) -> BigInt {
        let mut result = BigInt::zero();
        let mut x_power = BigInt::one();

        for coef in coefficients {
            result = (result + coef * &x_power) % &self.prime;
            x_power = (x_power * x) % &self.prime;
        }

        result
    }

    pub async fn reconstruct_key(&self, request: ReconstructRequest) -> AppResult<ReconstructResponse> {
        let keys = self.keys.lock();
        let key = keys
            .get(&request.key_id)
            .ok_or_else(|| AppError::NotFound(format!("Key {} not found", request.key_id)))?;

        if request.shares.len() as u32 < key.threshold {
            return Err(AppError::ValidationError(format!(
                "Need at least {} shares to reconstruct, got {}",
                key.threshold,
                request.shares.len()
            )));
        }

        if let Some(expires) = key.expires_at {
            if chrono::Utc::now() > expires {
                return Err(AppError::ValidationError("Key has expired".into()));
            }
        }

        let points: Vec<(BigInt, BigInt)> = request
            .shares
            .iter()
            .map(|s| {
                (
                    BigInt::from(s.index),
                    BigInt::from_bytes_be(num_bigint::Sign::Plus, &s.value),
                )
            })
            .collect();

        let secret = self.lagrange_interpolation(&points)?;
        let secret_bytes = secret.to_bytes_be().1;

        let checksum = CryptoService::sha256_hex(&secret_bytes);
        let verified = self.verify_reconstruction(&request.shares, key.key_size_bits);

        Ok(ReconstructResponse {
            key_id: request.key_id,
            secret: secret_bytes,
            checksum,
            verified,
        })
    }

    fn lagrange_interpolation(&self, points: &[(BigInt, BigInt)]) -> AppResult<BigInt> {
        if points.is_empty() {
            return Err(AppError::ValidationError("No points provided".into()));
        }

        let mut result = BigInt::zero();

        for (i, (xi, yi)) in points.iter().enumerate() {
            let mut numerator = BigInt::one();
            let mut denominator = BigInt::one();

            for (j, (xj, _)) in points.iter().enumerate() {
                if i != j {
                    numerator = (numerator * (-xj)) % &self.prime;
                    denominator = (denominator * (xi - xj)) % &self.prime;
                }
            }

            let inv_denominator = self.mod_inverse(&denominator, &self.prime)
                .ok_or_else(|| AppError::CryptoError("No modular inverse exists".into()))?;

            let term = (yi * &numerator % &self.prime) * &inv_denominator % &self.prime;
            result = (result + term) % &self.prime;
        }

        Ok(result)
    }

    fn mod_inverse(&self, a: &BigInt, m: &BigInt) -> Option<BigInt> {
        let (g, x, _) = self.extended_gcd(a, m);
        if g != BigInt::one() {
            None
        } else {
            Some((x % m + m) % m)
        }
    }

    fn extended_gcd(&self, a: &BigInt, b: &BigInt) -> (BigInt, BigInt, BigInt) {
        if *a == BigInt::zero() {
            return (b.clone(), BigInt::zero(), BigInt::one());
        }

        let (g, x1, y1) = self.extended_gcd(&(b % a), a);
        let x = y1 - (b / a) * &x1;
        let y = x1;

        (g, x, y)
    }

    fn verify_reconstruction(&self, shares: &[KeyShare], _key_size_bits: u32) -> bool {
        if shares.len() < 2 {
            return true;
        }

        let mut seen_indices = std::collections::HashSet::new();
        for share in shares {
            if !seen_indices.insert(share.index) {
                return false;
            }
        }

        true
    }

    pub async fn rotate_share(&self, request: RotateShareRequest) -> AppResult<KeyShare> {
        let mut shares = self.shares.lock();
        let share = shares
            .get_mut(&request.share_id)
            .ok_or_else(|| AppError::NotFound(format!("Share {} not found", request.share_id)))?;

        if share.key_id != request.key_id {
            return Err(AppError::ValidationError("Share does not belong to key".into()));
        }

        if let Some(new_owner) = request.new_owner {
            share.owner = new_owner;
        }

        let new_value = CryptoService::random_bytes(share.value.len());
        share.value = new_value;

        Ok(share.clone())
    }

    pub async fn get_key(&self, key_id: &str) -> AppResult<ShardedKey> {
        let keys = self.keys.lock();
        keys.get(key_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Key {} not found", key_id)))
    }

    pub async fn get_share(&self, share_id: &str) -> AppResult<KeyShare> {
        let shares = self.shares.lock();
        shares
            .get(share_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Share {} not found", share_id)))
    }

    pub async fn list_keys(&self) -> AppResult<Vec<ShardedKey>> {
        let keys = self.keys.lock();
        Ok(keys.values().cloned().collect())
    }

    pub async fn get_shares_for_key(&self, key_id: &str, owner: Option<&str>) -> AppResult<Vec<KeyShare>> {
        let shares = self.shares.lock();
        Ok(shares
            .values()
            .filter(|s| {
                s.key_id == key_id && owner.map(|o| s.owner == o).unwrap_or(true)
            })
            .cloned()
            .collect())
    }

    pub async fn delete_key(&self, key_id: &str) -> AppResult<()> {
        let mut keys = self.keys.lock();
        let key = keys
            .remove(key_id)
            .ok_or_else(|| AppError::NotFound(format!("Key {} not found", key_id)))?;

        let mut shares = self.shares.lock();
        for share in key.shares {
            shares.remove(&share.share_id);
        }

        Ok(())
    }

    pub async fn verify_key_integrity(&self, key_id: &str) -> AppResult<bool> {
        let keys = self.keys.lock();
        let key = keys
            .get(key_id)
            .ok_or_else(|| AppError::NotFound(format!("Key {} not found", key_id)))?;

        if key.shares.len() as u32 != key.total_shares {
            return Ok(false);
        }

        let mut indices = std::collections::HashSet::new();
        for share in &key.shares {
            if share.threshold != key.threshold || share.total_shares != key.total_shares {
                return Ok(false);
            }
            if !indices.insert(share.index) {
                return Ok(false);
            }
        }

        Ok(true)
    }

    pub fn create_run_instance(&self, key_id: &str) -> RunInstance {
        let mut instance = RunInstance::new(key_id.to_string());
        instance.set_metadata("module", "sharding");
        instance
    }

    pub fn hash_secret(&self, secret: &[u8]) -> String {
        CryptoService::sha256_hex(secret)
    }
}
