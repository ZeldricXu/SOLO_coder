use crate::error::PlatformError;
use crate::types::ShamirShare;
use rand::{Rng, thread_rng};
use num_bigint::{BigInt, BigUint, ToBigInt};
use num_traits::{Zero, One, Num, Pow};
use parking_lot::RwLock;
use std::collections::HashMap;
use std::sync::Arc;
use tracing::{info, warn, error};

const PRIME_256: &str = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F";

struct ShamirState {
    master_key_id: Option<String>,
    shares: HashMap<String, Vec<ShamirShare>>,
    thresholds: HashMap<String, (u8, u8)>,
    verification_hashes: HashMap<String, String>,
}

pub struct ShamirSecretManager {
    state: Arc<RwLock<ShamirState>>,
    prime: BigUint,
}

impl ShamirSecretManager {
    pub fn new() -> Self {
        let prime = BigUint::from_str_radix(PRIME_256, 16).unwrap();
        
        ShamirSecretManager {
            state: Arc::new(RwLock::new(ShamirState {
                master_key_id: None,
                shares: HashMap::new(),
                thresholds: HashMap::new(),
                verification_hashes: HashMap::new(),
            })),
            prime,
        }
    }

    pub fn generate_shares(
        &self,
        secret: &[u8],
        threshold: u8,
        total_shares: u8,
    ) -> Result<Vec<ShamirShare>, PlatformError> {
        if threshold < 2 {
            return Err(PlatformError::Validation("Threshold must be at least 2".to_string()));
        }
        
        if threshold > total_shares {
            return Err(PlatformError::Validation(
                "Threshold cannot exceed total shares".to_string()
            ));
        }
        
        if total_shares < 2 {
            return Err(PlatformError::Validation("Total shares must be at least 2".to_string()));
        }
        
        if secret.is_empty() {
            return Err(PlatformError::Validation("Secret cannot be empty".to_string()));
        }
        
        info!(
            threshold = threshold,
            total_shares = total_shares,
            secret_length = secret.len(),
            "Generating Shamir shares"
        );

        let mut shares = Vec::with_capacity(total_shares as usize);
        
        let secret_big = BigUint::from_bytes_be(secret);
        
        if &secret_big >= &self.prime {
            return Err(PlatformError::Crypto(
                "Secret is too large for the chosen prime modulus".to_string()
            ));
        }

        let coefficients = self.generate_coefficients(&secret_big, threshold);

        for i in 1..=total_shares {
            let x = BigUint::from(i as u64);
            let y = self.evaluate_polynomial(&coefficients, &x);
            
            let mut data = Vec::new();
            let y_bytes = y.to_bytes_be();
            data.extend_from_slice(&(y_bytes.len() as u16).to_be_bytes());
            data.extend_from_slice(&y_bytes);
            
            shares.push(ShamirShare {
                share_id: i,
                data,
                x_coordinate: i,
            });
        }

        Ok(shares)
    }

    fn generate_coefficients(&self, secret: &BigUint, threshold: u8) -> Vec<BigUint> {
        let mut rng = thread_rng();
        let mut coefficients = vec![secret.clone()];
        
        for _ in 1..threshold {
            loop {
                let mut bytes = [0u8; 32];
                rng.fill(&mut bytes);
                let coeff = BigUint::from_bytes_be(&bytes);
                if coeff < self.prime {
                    coefficients.push(coeff);
                    break;
                }
            }
        }
        
        coefficients
    }

    fn evaluate_polynomial(&self, coefficients: &[BigUint], x: &BigUint) -> BigUint {
        let mut result = BigUint::zero();
        let mut x_power = BigUint::one();
        
        for coeff in coefficients {
            result = (result + coeff * &x_power) % &self.prime;
            x_power = (&x_power * x) % &self.prime;
        }
        
        result
    }

    pub fn recover_secret(&self, shares: &[ShamirShare], threshold: u8) -> Result<Vec<u8>, PlatformError> {
        if shares.len() < threshold as usize {
            return Err(PlatformError::Validation(format!(
                "Insufficient shares: need {}, have {}",
                threshold,
                shares.len()
            )));
        }

        let threshold_shares = &shares[..threshold as usize];
        
        let mut x_values = Vec::with_capacity(threshold as usize);
        let mut y_values = Vec::with_capacity(threshold as usize);

        for share in threshold_shares {
            let x = BigUint::from(share.x_coordinate as u64);
            let y = self.share_data_to_bigint(&share.data)?;
            
            if x_values.contains(&x) {
                return Err(PlatformError::Crypto("Duplicate share coordinates found".to_string()));
            }
            
            x_values.push(x);
            y_values.push(y);
        }

        let secret = self.lagrange_interpolation(&x_values, &y_values);
        let mut result = secret.to_bytes_be();
        
        while result.len() < 32 {
            result.insert(0, 0);
        }
        
        info!("Secret recovered successfully from {} shares", shares.len());
        Ok(result)
    }

    fn lagrange_interpolation(&self, x_values: &[BigUint], y_values: &[BigUint]) -> BigUint {
        let mut result = BigUint::zero();
        
        for i in 0..x_values.len() {
            let mut numerator = BigUint::one();
            let mut denominator = BigUint::one();
            
            for j in 0..x_values.len() {
                if i != j {
                    numerator = (&numerator * &(&self.prime - &x_values[j])) % &self.prime;
                    
                    let diff = if x_values[i] > x_values[j] {
                        &x_values[i] - &x_values[j]
                    } else {
                        &self.prime + &x_values[i] - &x_values[j]
                    };
                    
                    denominator = (denominator * diff) % &self.prime;
                }
            }
            
            let inv_denominator = self.modular_inverse(&denominator);
            let lagrange_term = (&numerator * inv_denominator) % &self.prime;
            let term = (&y_values[i] * lagrange_term) % &self.prime;
            
            result = (result + term) % &self.prime;
        }
        
        result
    }

    fn modular_inverse(&self, a: &BigUint) -> BigUint {
        let a_int = a.to_bigint().unwrap();
        let p_int = (&self.prime).to_bigint().unwrap();
        
        let mut t = BigInt::zero();
        let mut new_t = BigInt::one();
        let mut r = p_int.clone();
        let mut new_r = a_int.clone();
        
        while !new_r.is_zero() {
            let quotient = &r / &new_r;
            
            let temp_t = t.clone();
            t = new_t.clone();
            new_t = temp_t - &quotient * &new_t;
            
            let temp_r = r.clone();
            r = new_r.clone();
            new_r = temp_r - &quotient * &new_r;
        }
        
        if t < BigInt::zero() {
            t = t + p_int;
        }
        
        t.to_biguint().unwrap()
    }

    fn share_data_to_bigint(&self, data: &[u8]) -> Result<BigUint, PlatformError> {
        if data.len() < 2 {
            return Err(PlatformError::Crypto("Invalid share data format".to_string()));
        }
        
        let len_bytes: [u8; 2] = [data[0], data[1]];
        let len = u16::from_be_bytes(len_bytes) as usize;
        
        if data.len() != 2 + len {
            return Err(PlatformError::Crypto("Share data length mismatch".to_string()));
        }
        
        Ok(BigUint::from_bytes_be(&data[2..]))
    }

    pub fn verify_share(&self, share: &ShamirShare, verification_hash: &str) -> Result<bool, PlatformError> {
        let mut data_to_hash = Vec::new();
        data_to_hash.push(share.share_id);
        data_to_hash.extend_from_slice(&share.data);
        data_to_hash.push(share.x_coordinate);
        
        let hash = crate::utils::hash_bytes(&data_to_hash);
        
        Ok(hash == verification_hash)
    }

    pub fn store_shares(
        &self,
        key_id: &str,
        shares: &[ShamirShare],
        threshold: u8,
        total_shares: u8,
    ) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        
        if state.shares.contains_key(key_id) {
            return Err(PlatformError::Conflict(format!(
                "Shares for key {} already exist",
                key_id
            )));
        }
        
        state.shares.insert(key_id.to_string(), shares.to_vec());
        state.thresholds.insert(key_id.to_string(), (threshold, total_shares));
        
        let mut verification_hashes = Vec::new();
        for share in shares {
            let mut data_to_hash = Vec::new();
            data_to_hash.push(share.share_id);
            data_to_hash.extend_from_slice(&share.data);
            data_to_hash.push(share.x_coordinate);
            
            verification_hashes.push(crate::utils::hash_bytes(&data_to_hash));
        }
        
        let combined_hash = crate::utils::hash_string(
            &verification_hashes.join("|")
        );
        state.verification_hashes.insert(key_id.to_string(), combined_hash);
        
        if state.master_key_id.is_none() {
            state.master_key_id = Some(key_id.to_string());
        }
        
        info!(
            key_id = %key_id,
            threshold = threshold,
            total_shares = total_shares,
            "Shares stored successfully"
        );
        
        Ok(())
    }

    pub fn retrieve_share(&self, key_id: &str, share_id: u8) -> Option<ShamirShare> {
        let state = self.state.read();
        state.shares.get(key_id).and_then(|shares| {
            shares.iter().find(|s| s.share_id == share_id).cloned()
        })
    }

    pub fn retrieve_all_shares(&self, key_id: &str) -> Option<Vec<ShamirShare>> {
        let state = self.state.read();
        state.shares.get(key_id).cloned()
    }

    pub fn get_threshold(&self, key_id: &str) -> Option<(u8, u8)> {
        let state = self.state.read();
        state.thresholds.get(key_id).copied()
    }

    pub fn delete_shares(&self, key_id: &str) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        
        if state.shares.remove(key_id).is_none() {
            return Err(PlatformError::NotFound(format!(
                "No shares found for key {}",
                key_id
            )));
        }
        
        state.thresholds.remove(key_id);
        state.verification_hashes.remove(key_id);
        
        if state.master_key_id.as_ref() == Some(&key_id.to_string()) {
            state.master_key_id = None;
        }
        
        info!(key_id = %key_id, "Shares deleted");
        
        Ok(())
    }

    pub fn verify_integrity(&self, key_id: &str) -> Result<bool, PlatformError> {
        let state = self.state.read();
        
        let shares = state.shares.get(key_id)
            .ok_or_else(|| PlatformError::NotFound(format!("No shares found for key {}", key_id)))?;
        
        let stored_hash = state.verification_hashes.get(key_id)
            .ok_or_else(|| PlatformError::NotFound(format!("No verification hash for key {}", key_id)))?;
        
        let mut verification_hashes = Vec::new();
        for share in shares {
            let mut data_to_hash = Vec::new();
            data_to_hash.push(share.share_id);
            data_to_hash.extend_from_slice(&share.data);
            data_to_hash.push(share.x_coordinate);
            
            verification_hashes.push(crate::utils::hash_bytes(&data_to_hash));
        }
        
        let computed_hash = crate::utils::hash_string(&verification_hashes.join("|"));
        
        Ok(computed_hash == *stored_hash)
    }

    pub fn get_key_ids(&self) -> Vec<String> {
        let state = self.state.read();
        state.shares.keys().cloned().collect()
    }

    pub fn has_master_key(&self) -> bool {
        let state = self.state.read();
        state.master_key_id.is_some()
    }

    pub fn rotate_master_key(
        &self,
        new_secret: &[u8],
        threshold: u8,
        total_shares: u8,
    ) -> Result<Vec<ShamirShare>, PlatformError> {
        let old_key_id = {
            let state = self.state.read();
            state.master_key_id.clone()
        };
        
        let new_shares = self.generate_shares(new_secret, threshold, total_shares)?;
        let new_key_id = format!("key_{}", uuid::Uuid::new_v4().simple());
        
        {
            let mut state = self.state.write();
            state.master_key_id = Some(new_key_id.clone());
            state.shares.insert(new_key_id.clone(), new_shares.clone());
            state.thresholds.insert(new_key_id.clone(), (threshold, total_shares));
            
            let mut verification_hashes = Vec::new();
            for share in &new_shares {
                let mut data_to_hash = Vec::new();
                data_to_hash.push(share.share_id);
                data_to_hash.extend_from_slice(&share.data);
                data_to_hash.push(share.x_coordinate);
                verification_hashes.push(crate::utils::hash_bytes(&data_to_hash));
            }
            
            let combined_hash = crate::utils::hash_string(&verification_hashes.join("|"));
            state.verification_hashes.insert(new_key_id.clone(), combined_hash);
            
            if let Some(old_id) = old_key_id {
                state.shares.remove(&old_id);
                state.thresholds.remove(&old_id);
                state.verification_hashes.remove(&old_id);
            }
        }
        
        info!("Master key rotated successfully");
        
        Ok(new_shares)
    }
}

impl Default for ShamirSecretManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_secret_split_and_recovery() {
        let manager = ShamirSecretManager::new();
        let secret = b"test_secret_12345";
        
        let shares = manager.generate_shares(secret, 3, 5).unwrap();
        assert_eq!(shares.len(), 5);
        
        let subset = &shares[0..3];
        let recovered = manager.recover_secret(subset, 3).unwrap();
        
        let mut padded_secret = secret.to_vec();
        while padded_secret.len() < 32 {
            padded_secret.insert(0, 0);
        }
        
        assert_eq!(recovered, padded_secret);
    }

    #[test]
    fn test_insufficient_shares() {
        let manager = ShamirSecretManager::new();
        let secret = b"test_secret_12345";
        
        let shares = manager.generate_shares(secret, 3, 5).unwrap();
        let subset = &shares[0..2];
        
        let result = manager.recover_secret(subset, 3);
        assert!(result.is_err());
    }

    #[test]
    fn test_store_and_retrieve() {
        let manager = ShamirSecretManager::new();
        let secret = b"test_secret";
        
        let shares = manager.generate_shares(secret, 2, 3).unwrap();
        manager.store_shares("test_key", &shares, 2, 3).unwrap();
        
        let retrieved = manager.retrieve_share("test_key", 1);
        assert!(retrieved.is_some());
        
        let all_shares = manager.retrieve_all_shares("test_key");
        assert_eq!(all_shares.unwrap().len(), 3);
    }

    #[test]
    fn test_integrity_verification() {
        let manager = ShamirSecretManager::new();
        let secret = b"test_secret";
        
        let shares = manager.generate_shares(secret, 2, 3).unwrap();
        manager.store_shares("test_key", &shares, 2, 3).unwrap();
        
        assert!(manager.verify_integrity("test_key").unwrap());
    }
}
