use crate::config::ShamirConfig;
use crate::models::AppError;
use crate::utils::{current_datetime, generate_id};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use num_bigint::{BigInt, BigUint, ToBigInt};
use num_traits::{Zero, One, FromPrimitive};
use rand::Rng;
use rand_chacha::ChaCha20Rng;
use rand::SeedableRng;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::sync::Arc;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SecretShare {
    pub share_id: String,
    pub index: u32,
    pub value: String,
    pub owner_id: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SecretMetadata {
    pub secret_id: String,
    pub total_shares: usize,
    pub threshold: usize,
    pub created_at: DateTime<Utc>,
    pub recovered_at: Option<DateTime<Utc>>,
    pub is_active: bool,
    pub description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShareDistribution {
    pub secret_id: String,
    pub shares: Vec<SecretShare>,
    pub distributed_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SecretRecovery {
    pub secret_id: String,
    pub recovered_secret: String,
    pub shares_used: Vec<u32>,
    pub recovered_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KeyRotation {
    pub rotation_id: String,
    pub old_secret_id: String,
    pub new_secret_id: String,
    pub rotated_at: DateTime<Utc>,
    pub reason: String,
}

pub struct ShamirSecretSharing {
    config: ShamirConfig,
    prime: BigUint,
    secrets: Arc<DashMap<String, SecretMetadata>>,
    shares: Arc<DashMap<String, Vec<SecretShare>>>,
    rng: Arc<DashMap<String, ChaCha20Rng>>,
}

impl ShamirSecretSharing {
    pub fn new(config: ShamirConfig) -> Self {
        let prime = Self::generate_prime(config.prime_bits);
        Self {
            config,
            prime,
            secrets: Arc::new(DashMap::new()),
            shares: Arc::new(DashMap::new()),
            rng: Arc::new(DashMap::new()),
        }
    }

    pub fn with_prime(config: ShamirConfig, prime: BigUint) -> Self {
        Self {
            config,
            prime,
            secrets: Arc::new(DashMap::new()),
            shares: Arc::new(DashMap::new()),
            rng: Arc::new(DashMap::new()),
        }
    }

    fn generate_prime(bits: usize) -> BigUint {
        if bits == 256 {
            BigUint::from_bytes_be(&hex::decode("ffffffffffffffffc90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b139b22514a08798e3404ddef9519b3cd3a431b302b0a6df25f14374fe1356d6d51c245e485b576625e7ec6f44c42e9a637ed6b0bff5cb6f406b7edee386bfb5a899fa5ae9f24117c4b1fe649286651ece45b3dc2007cb8a163bf0598da48361c55d39a69163fa8fd24cf5f83655d23dca3ad961c62f356208552bb9ed529077096966d670c354e4abc9804f1746c08ca18217c32905e462e36ce3be39e772c180e86039b2783a2ec07a28fb5c55df06f4c52c9de2bcbf6955817183995497cea956ae515d2261898fa051015728e5a8aacaa68ffffffffffffffff").unwrap())
        } else {
            let mut bytes = vec![0u8; bits / 8];
            bytes[0] = 0x80;
            bytes[bytes.len() - 1] = 0x01;
            BigUint::from_bytes_be(&bytes) + BigUint::from_u32(31).unwrap()
        }
    }

    pub fn split_secret(
        &self,
        secret: &[u8],
        threshold: usize,
        total_shares: usize,
        description: &str,
    ) -> Result<ShareDistribution, AppError> {
        if threshold < 2 {
            return Err(AppError::Validation("Threshold must be at least 2".to_string()));
        }

        if total_shares < threshold {
            return Err(AppError::Validation(
                "Total shares must be at least threshold".to_string(),
            ));
        }

        if threshold > self.config.default_threshold * 2 {
            return Err(AppError::Validation(format!(
                "Threshold exceeds maximum of {}",
                self.config.default_threshold * 2
            )));
        }

        if total_shares > self.config.default_shares * 2 {
            return Err(AppError::Validation(format!(
                "Total shares exceeds maximum of {}",
                self.config.default_shares * 2
            )));
        }

        let secret_int = BigUint::from_bytes_be(secret);
        if secret_int >= self.prime {
            return Err(AppError::Validation(
                "Secret is too large for the current prime".to_string(),
            ));
        }

        let coefficients = self.generate_coefficients(&secret_int, threshold);
        let shares = self.generate_shares(&coefficients, total_shares);

        let secret_id = generate_id("sec");
        let now = current_datetime();

        let metadata = SecretMetadata {
            secret_id: secret_id.clone(),
            total_shares,
            threshold,
            created_at: now,
            recovered_at: None,
            is_active: true,
            description: description.to_string(),
        };

        let secret_shares: Vec<SecretShare> = shares
            .iter()
            .enumerate()
            .map(|(i, (idx, val))| SecretShare {
                share_id: generate_id("shr"),
                index: *idx,
                value: Self::biguint_to_hex(val),
                owner_id: None,
                created_at: now,
            })
            .collect();

        self.secrets.insert(secret_id.clone(), metadata);
        self.shares.insert(secret_id.clone(), secret_shares.clone());

        Ok(ShareDistribution {
            secret_id,
            shares: secret_shares,
            distributed_at: now,
        })
    }

    fn generate_coefficients(&self, secret: &BigUint, threshold: usize) -> Vec<BigUint> {
        let mut rng = ChaCha20Rng::from_entropy();
        let mut coefficients = Vec::with_capacity(threshold);
        coefficients.push(secret.clone());

        for _ in 1..threshold {
            let mut bytes = vec![0u8; self.config.prime_bits / 8];
            rng.fill(&mut bytes[..]);
            let coeff = BigUint::from_bytes_be(&bytes) % &self.prime;
            coefficients.push(coeff);
        }

        coefficients
    }

    fn generate_shares(
        &self,
        coefficients: &[BigUint],
        total_shares: usize,
    ) -> Vec<(u32, BigUint)> {
        let mut shares = Vec::with_capacity(total_shares);

        for i in 1..=total_shares {
            let x = BigUint::from_u32(i as u32).unwrap();
            let y = self.evaluate_polynomial(coefficients, &x);
            shares.push((i as u32, y));
        }

        shares
    }

    fn evaluate_polynomial(&self, coefficients: &[BigUint], x: &BigUint) -> BigUint {
        let mut result = BigUint::zero();
        let mut x_power = BigUint::one();

        for coeff in coefficients {
            let term = (coeff * &x_power) % &self.prime;
            result = (result + term) % &self.prime;
            x_power = (&x_power * x) % &self.prime;
        }

        result
    }

    pub fn recover_secret(
        &self,
        secret_id: &str,
        shares: &[SecretShare],
    ) -> Result<SecretRecovery, AppError> {
        let metadata = self
            .secrets
            .get(secret_id)
            .ok_or_else(|| AppError::NotFound(format!("Secret not found: {}", secret_id)))?;

        if shares.len() < metadata.threshold {
            return Err(AppError::Validation(format!(
                "Need at least {} shares to recover secret",
                metadata.threshold
            )));
        }

        let points: Vec<(BigUint, BigUint)> = shares
            .iter()
            .map(|s| {
                let x = BigUint::from_u32(s.index).unwrap();
                let y = Self::hex_to_biguint(&s.value);
                (x, y)
            })
            .collect();

        let secret = self.lagrange_interpolation(&points)?;
        let secret_bytes = secret.to_bytes_be();

        let shares_used: Vec<u32> = shares.iter().map(|s| s.index).collect();

        {
            let mut meta = self.secrets.get_mut(secret_id).unwrap();
            meta.recovered_at = Some(current_datetime());
        }

        Ok(SecretRecovery {
            secret_id: secret_id.to_string(),
            recovered_secret: hex::encode(secret_bytes),
            shares_used,
            recovered_at: current_datetime(),
        })
    }

    fn lagrange_interpolation(&self, points: &[(BigUint, BigUint)]) -> Result<BigUint, AppError> {
        if points.is_empty() {
            return Err(AppError::Validation("No points provided".to_string()));
        }

        let x_zero = BigUint::zero();
        let mut result = BigUint::zero();
        let prime_int = &self.prime;

        for (i, (xi, yi)) in points.iter().enumerate() {
            let mut numerator = BigUint::one();
            let mut denominator = BigUint::one();

            for (j, (xj, _)) in points.iter().enumerate() {
                if i != j {
                    let term1 = (&x_zero + prime_int - xj) % prime_int;
                    numerator = (numerator * &term1) % prime_int;

                    let term2 = if xi > xj {
                        (xi - xj) % prime_int
                    } else {
                        (xi + prime_int - xj) % prime_int
                    };
                    denominator = (denominator * &term2) % prime_int;
                }
            }

            let denom_inv = Self::mod_inverse(&denominator, prime_int)
                .ok_or_else(|| AppError::Internal("Cannot compute modular inverse".to_string()))?;

            let lagrange = (numerator * denom_inv) % prime_int;
            let term = (yi * lagrange) % prime_int;
            result = (result + term) % prime_int;
        }

        Ok(result)
    }

    fn mod_inverse(a: &BigUint, prime: &BigUint) -> Option<BigUint> {
        if a.is_zero() {
            return None;
        }

        let a_int = a.to_bigint().unwrap();
        let prime_int = prime.to_bigint().unwrap();
        let exponent = &prime_int - 2;
        
        let result_int = Self::mod_pow_bigint(&a_int, &exponent, &prime_int);
        if result_int < BigInt::zero() {
            Some((result_int + &prime_int).to_biguint().unwrap())
        } else {
            Some(result_int.to_biguint().unwrap())
        }
    }

    fn mod_pow_bigint(base: &BigInt, exponent: &BigInt, modulus: &BigInt) -> BigInt {
        if exponent.is_zero() {
            return BigInt::one();
        }

        let mut result = BigInt::one();
        let mut base = base.clone() % modulus;
        let mut exp = exponent.clone();

        while exp > BigInt::zero() {
            if &exp % 2 == BigInt::one() {
                result = (&result * &base) % modulus;
            }
            exp = exp / 2;
            base = (&base * &base) % modulus;
        }

        result
    }

    pub fn get_secret_metadata(&self, secret_id: &str) -> Option<SecretMetadata> {
        self.secrets.get(secret_id).map(|m| m.clone())
    }

    pub fn get_shares(&self, secret_id: &str) -> Option<Vec<SecretShare>> {
        self.shares.get(secret_id).map(|s| s.clone())
    }

    pub fn list_secrets(&self) -> Vec<SecretMetadata> {
        self.secrets.iter().map(|m| m.clone()).collect()
    }

    pub fn assign_share_owner(
        &self,
        secret_id: &str,
        share_index: u32,
        owner_id: &str,
    ) -> Result<(), AppError> {
        let mut shares = self
            .shares
            .get_mut(secret_id)
            .ok_or_else(|| AppError::NotFound(format!("Secret not found: {}", secret_id)))?;

        if let Some(share) = shares.iter_mut().find(|s| s.index == share_index) {
            share.owner_id = Some(owner_id.to_string());
            Ok(())
        } else {
            Err(AppError::NotFound(format!(
                "Share with index {} not found",
                share_index
            )))
        }
    }

    pub fn rotate_secret(
        &self,
        old_secret_id: &str,
        new_secret: &[u8],
        reason: &str,
    ) -> Result<(ShareDistribution, KeyRotation), AppError> {
        let old_metadata = self
            .secrets
            .get(old_secret_id)
            .ok_or_else(|| AppError::NotFound(format!("Secret not found: {}", old_secret_id)))?;

        let threshold = old_metadata.threshold;
        let total_shares = old_metadata.total_shares;

        let new_distribution =
            self.split_secret(new_secret, threshold, total_shares, "Rotated secret")?;

        {
            let mut old_meta = self.secrets.get_mut(old_secret_id).unwrap();
            old_meta.is_active = false;
        }

        let rotation = KeyRotation {
            rotation_id: generate_id("rot"),
            old_secret_id: old_secret_id.to_string(),
            new_secret_id: new_distribution.secret_id.clone(),
            rotated_at: current_datetime(),
            reason: reason.to_string(),
        };

        Ok((new_distribution, rotation))
    }

    pub fn destroy_secret(&self, secret_id: &str) -> Result<(), AppError> {
        if self.secrets.remove(secret_id).is_none() {
            return Err(AppError::NotFound(format!("Secret not found: {}", secret_id)));
        }

        self.shares.remove(secret_id);
        Ok(())
    }

    fn biguint_to_hex(value: &BigUint) -> String {
        let bytes = value.to_bytes_be();
        hex::encode(bytes)
    }

    fn hex_to_biguint(hex_str: &str) -> BigUint {
        let bytes = hex::decode(hex_str).unwrap_or_default();
        BigUint::from_bytes_be(&bytes)
    }

    pub fn secrets_count(&self) -> usize {
        self.secrets.len()
    }

    pub fn get_prime(&self) -> &BigUint {
        &self.prime
    }

    pub fn validate_shares(
        &self,
        secret_id: &str,
        shares: &[SecretShare],
    ) -> Result<bool, AppError> {
        let metadata = self
            .secrets
            .get(secret_id)
            .ok_or_else(|| AppError::NotFound(format!("Secret not found: {}", secret_id)))?;

        if shares.len() < metadata.threshold {
            return Ok(false);
        }

        let all_indices: HashSet<u32> = shares.iter().map(|s| s.index).collect();
        if all_indices.len() != shares.len() {
            return Ok(false);
        }

        Ok(true)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShamirEvent {
    pub event_type: String,
    pub secret_id: Option<String>,
    pub timestamp: DateTime<Utc>,
    pub details: serde_json::Value,
}

impl ShamirEvent {
    pub fn new(
        event_type: &str,
        secret_id: Option<String>,
        details: serde_json::Value,
    ) -> Self {
        Self {
            event_type: event_type.to_string(),
            secret_id,
            timestamp: current_datetime(),
            details,
        }
    }
}
