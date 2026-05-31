use crate::error::PlatformError;
use crate::types::{EnclaveInstance, EnclaveStatus};
use crate::utils::{current_timestamp, hash_bytes, compute_hmac_sha256, verify_hmac_sha256};
use async_trait::async_trait;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;
use tracing::{info, warn, error, debug};
use uuid::Uuid;

#[async_trait]
pub trait EnclaveBackend: Send + Sync {
    async fn create_enclave(&self, config: &EnclaveConfiguration) -> Result<String, PlatformError>;
    async fn destroy_enclave(&self, enclave_id: &str) -> Result<(), PlatformError>;
    async fn get_attestation(&self, enclave_id: &str) -> Result<Vec<u8>, PlatformError>;
    async fn execute_in_enclave(
        &self,
        enclave_id: &str,
        operation: &str,
        input: &[u8],
    ) -> Result<Vec<u8>, PlatformError>;
    fn backend_name(&self) -> &str;
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnclaveConfiguration {
    pub enclave_type: EnclaveType,
    pub memory_size_mb: u32,
    pub thread_count: u32,
    pub enable_debug: bool,
    pub policy: Option<EnclavePolicy>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EnclaveType {
    SGX,
    TDX,
    SEV,
    TrustZone,
    Simulated,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnclavePolicy {
    pub allowed_operations: Vec<String>,
    pub max_execution_time_ms: u64,
    pub require_attestation: bool,
    pub data_encryption_required: bool,
}

impl Default for EnclavePolicy {
    fn default() -> Self {
        EnclavePolicy {
            allowed_operations: vec!["encrypt".to_string(), "decrypt".to_string(), "sign".to_string()],
            max_execution_time_ms: 30000,
            require_attestation: true,
            data_encryption_required: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AttestationReport {
    pub enclave_id: String,
    pub quote: Vec<u8>,
    pub signature: Vec<u8>,
    pub timestamp: chrono::DateTime<chrono::Utc>,
    pub mr_enclave: Vec<u8>,
    pub mr_signer: Vec<u8>,
    pub isv_prod_id: u16,
    pub isv_svn: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VerificationResult {
    pub is_valid: bool,
    pub enclave_id: String,
    pub timestamp: chrono::DateTime<chrono::Utc>,
    pub details: Option<String>,
    pub trust_level: TrustLevel,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TrustLevel {
    Untrusted,
    Partial,
    Full,
    Verified,
}

struct TEEState {
    enclaves: HashMap<String, EnclaveInstance>,
    configurations: HashMap<String, EnclaveConfiguration>,
    attestation_reports: HashMap<String, AttestationReport>,
    backends: HashMap<String, Arc<dyn EnclaveBackend>>,
    root_key: Vec<u8>,
    enclave_count: u64,
}

pub struct TrustedExecutionEnvironment {
    state: Arc<RwLock<TEEState>>,
}

impl TrustedExecutionEnvironment {
    pub fn new(root_key: &[u8]) -> Self {
        TrustedExecutionEnvironment {
            state: Arc::new(RwLock::new(TEEState {
                enclaves: HashMap::new(),
                configurations: HashMap::new(),
                attestation_reports: HashMap::new(),
                backends: HashMap::new(),
                root_key: root_key.to_vec(),
                enclave_count: 0,
            })),
        }
    }

    pub fn register_backend(&self, backend: Arc<dyn EnclaveBackend>) {
        let mut state = self.state.write();
        state.backends.insert(backend.backend_name().to_string(), backend);
        info!(name = %backend.backend_name(), "TEE backend registered");
    }

    pub async fn create_enclave(
        &self,
        config: EnclaveConfiguration,
    ) -> Result<EnclaveInstance, PlatformError> {
        if config.memory_size_mb == 0 {
            return Err(PlatformError::Validation("Memory size must be greater than 0".to_string()));
        }
        
        if config.thread_count == 0 {
            return Err(PlatformError::Validation("Thread count must be greater than 0".to_string()));
        }
        
        let enclave_id = format!("enclave_{}", Uuid::new_v4().simple());
        
        let instance = EnclaveInstance {
            enclave_id: enclave_id.clone(),
            status: EnclaveStatus::Created,
            attestation_report: None,
            created_at: current_timestamp(),
        };
        
        {
            let mut state = self.state.write();
            state.enclaves.insert(enclave_id.clone(), instance.clone());
            state.configurations.insert(enclave_id.clone(), config.clone());
            state.enclave_count += 1;
        }
        
        info!(
            enclave_id = %enclave_id,
            enclave_type = ?config.enclave_type,
            memory_mb = config.memory_size_mb,
            "Enclave created"
        );
        
        Ok(instance)
    }

    pub async fn attest_enclave(&self, enclave_id: &str) -> Result<AttestationReport, PlatformError> {
        let mut state = self.state.write();
        
        let enclave = state.enclaves.get_mut(enclave_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Enclave {} not found", enclave_id)))?;
        
        if enclave.status != EnclaveStatus::Created {
            return Err(PlatformError::Validation(format!(
                "Cannot attest enclave in status {:?}",
                enclave.status
            )));
        }
        
        let mr_enclave = self.generate_measurement(enclave_id, "mr_enclave");
        let mr_signer = self.generate_measurement(enclave_id, "mr_signer");
        
        let quote = self.generate_quote(enclave_id, &mr_enclave, &mr_signer);
        
        let signature = compute_hmac_sha256(&state.root_key, &quote)
            .map_err(|e| PlatformError::Crypto(format!("Failed to sign attestation: {}", e)))?;
        
        let report = AttestationReport {
            enclave_id: enclave_id.to_string(),
            quote: quote.clone(),
            signature,
            timestamp: current_timestamp(),
            mr_enclave,
            mr_signer,
            isv_prod_id: 1,
            isv_svn: 1,
        };
        
        state.attestation_reports.insert(enclave_id.to_string(), report.clone());
        
        enclave.status = EnclaveStatus::Attested;
        enclave.attestation_report = Some(quote);
        
        info!(enclave_id = %enclave_id, "Enclave attested successfully");
        
        Ok(report)
    }

    fn generate_measurement(&self, enclave_id: &str, prefix: &str) -> Vec<u8> {
        let mut data = Vec::new();
        data.extend_from_slice(prefix.as_bytes());
        data.extend_from_slice(enclave_id.as_bytes());
        data.extend_from_slice(&current_timestamp().to_rfc3339().as_bytes());
        
        let hash = hash_bytes(&data);
        let mut result = Vec::with_capacity(32);
        result.extend_from_slice(hash.as_bytes());
        result.truncate(32);
        result
    }

    fn generate_quote(&self, enclave_id: &str, mr_enclave: &[u8], mr_signer: &[u8]) -> Vec<u8> {
        let mut quote = Vec::new();
        quote.extend_from_slice(enclave_id.as_bytes());
        quote.extend_from_slice(b"|");
        quote.extend_from_slice(mr_enclave);
        quote.extend_from_slice(b"|");
        quote.extend_from_slice(mr_signer);
        quote.extend_from_slice(b"|");
        quote.extend_from_slice(current_timestamp().to_rfc3339().as_bytes());
        
        quote
    }

    pub async fn verify_attestation(
        &self,
        enclave_id: &str,
        expected_mr_enclave: Option<&[u8]>,
        expected_mr_signer: Option<&[u8]>,
    ) -> Result<VerificationResult, PlatformError> {
        let state = self.state.read();
        
        let report = state.attestation_reports.get(enclave_id)
            .ok_or_else(|| PlatformError::NotFound(format!("No attestation report for enclave {}", enclave_id)))?;
        
        let mut trust_level = TrustLevel::Untrusted;
        let mut details = Vec::new();
        
        let signature_valid = verify_hmac_sha256(
            &state.root_key,
            &report.quote,
            &report.signature,
        );
        
        if signature_valid {
            trust_level = TrustLevel::Partial;
            details.push("Signature verified".to_string());
            
            if let Some(expected) = expected_mr_enclave {
                if report.mr_enclave == expected {
                    trust_level = TrustLevel::Full;
                    details.push("MRENCLAVE matches expected value".to_string());
                } else {
                    details.push("MRENCLAVE mismatch".to_string());
                    return Ok(VerificationResult {
                        is_valid: false,
                        enclave_id: enclave_id.to_string(),
                        timestamp: current_timestamp(),
                        details: Some("MRENCLAVE verification failed".to_string()),
                        trust_level: TrustLevel::Untrusted,
                    });
                }
            }
            
            if let Some(expected) = expected_mr_signer {
                if report.mr_signer == expected {
                    trust_level = TrustLevel::Verified;
                    details.push("MRSIGNER matches expected value".to_string());
                } else {
                    details.push("MRSIGNER mismatch".to_string());
                }
            }
        } else {
            details.push("Invalid signature".to_string());
        }
        
        let result = VerificationResult {
            is_valid: signature_valid && trust_level >= TrustLevel::Partial,
            enclave_id: enclave_id.to_string(),
            timestamp: current_timestamp(),
            details: Some(details.join("; ")),
            trust_level,
        };
        
        info!(
            enclave_id = %enclave_id,
            is_valid = result.is_valid,
            trust_level = ?result.trust_level,
            "Attestation verification completed"
        );
        
        Ok(result)
    }

    pub async fn start_enclave(&self, enclave_id: &str) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        
        let enclave = state.enclaves.get_mut(enclave_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Enclave {} not found", enclave_id)))?;
        
        if enclave.status != EnclaveStatus::Attested {
            return Err(PlatformError::Validation(format!(
                "Enclave must be attested before starting (current status: {:?})",
                enclave.status
            )));
        }
        
        enclave.status = EnclaveStatus::Running;
        
        info!(enclave_id = %enclave_id, "Enclave started");
        
        Ok(())
    }

    pub async fn execute_secure_operation(
        &self,
        enclave_id: &str,
        operation: &str,
        input: &[u8],
    ) -> Result<Vec<u8>, PlatformError> {
        let (config, policy) = {
            let state = self.state.read();
            
            let enclave = state.enclaves.get(enclave_id)
                .ok_or_else(|| PlatformError::NotFound(format!("Enclave {} not found", enclave_id)))?;
            
            if enclave.status != EnclaveStatus::Running {
                return Err(PlatformError::Validation(format!(
                    "Enclave is not running (status: {:?})",
                    enclave.status
                )));
            }
            
            let config = state.configurations.get(enclave_id)
                .cloned()
                .ok_or_else(|| PlatformError::NotFound(format!("Configuration for enclave {} not found", enclave_id)))?;
            
            (config.clone(), config.policy.clone().unwrap_or_default())
        };
        
        if !policy.allowed_operations.contains(&operation.to_string()) {
            return Err(PlatformError::Authorization(format!(
                "Operation '{}' is not allowed in this enclave",
                operation
            )));
        }
        
        debug!(
            enclave_id = %enclave_id,
            operation = %operation,
            input_size = input.len(),
            "Executing secure operation"
        );
        
        let output = self.perform_enclave_operation(operation, input);
        
        info!(
            enclave_id = %enclave_id,
            operation = %operation,
            output_size = output.len(),
            "Secure operation completed"
        );
        
        Ok(output)
    }

    fn perform_enclave_operation(&self, operation: &str, input: &[u8]) -> Vec<u8> {
        match operation {
            "encrypt" => {
                let state = self.state.read();
                let mut result = Vec::new();
                for (i, byte) in input.iter().enumerate() {
                    let key_byte = state.root_key[i % state.root_key.len()];
                    result.push(byte ^ key_byte);
                }
                result
            }
            "decrypt" => {
                let state = self.state.read();
                let mut result = Vec::new();
                for (i, byte) in input.iter().enumerate() {
                    let key_byte = state.root_key[i % state.root_key.len()];
                    result.push(byte ^ key_byte);
                }
                result
            }
            "hash" => {
                let hash = hash_bytes(input);
                hash.into_bytes()
            }
            "sign" => {
                let state = self.state.read();
                compute_hmac_sha256(&state.root_key, input).unwrap_or_default()
            }
            _ => input.to_vec(),
        }
    }

    pub async fn terminate_enclave(&self, enclave_id: &str) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        
        let enclave = state.enclaves.get_mut(enclave_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Enclave {} not found", enclave_id)))?;
        
        enclave.status = EnclaveStatus::Terminated;
        
        state.attestation_reports.remove(enclave_id);
        
        warn!(enclave_id = %enclave_id, "Enclave terminated");
        
        Ok(())
    }

    pub fn get_enclave_status(&self, enclave_id: &str) -> Result<EnclaveStatus, PlatformError> {
        let state = self.state.read();
        
        state.enclaves.get(enclave_id)
            .map(|e| e.status)
            .ok_or_else(|| PlatformError::NotFound(format!("Enclave {} not found", enclave_id)))
    }

    pub fn get_enclave(&self, enclave_id: &str) -> Option<EnclaveInstance> {
        let state = self.state.read();
        state.enclaves.get(enclave_id).cloned()
    }

    pub fn list_enclaves(&self, status: Option<EnclaveStatus>) -> Vec<EnclaveInstance> {
        let state = self.state.read();
        
        state.enclaves.values()
            .filter(|e| status.map(|s| e.status == s).unwrap_or(true))
            .cloned()
            .collect()
    }

    pub fn get_attestation_report(&self, enclave_id: &str) -> Option<AttestationReport> {
        let state = self.state.read();
        state.attestation_reports.get(enclave_id).cloned()
    }

    pub fn get_statistics(&self) -> HashMap<String, u64> {
        let state = self.state.read();
        
        let mut stats = HashMap::new();
        stats.insert("total_enclaves".to_string(), state.enclave_count);
        stats.insert("active_enclaves".to_string(), state.enclaves.len() as u64);
        stats.insert("attested_enclaves".to_string(), state.attestation_reports.len() as u64);
        
        let running_count = state.enclaves.values()
            .filter(|e| e.status == EnclaveStatus::Running)
            .count() as u64;
        stats.insert("running_enclaves".to_string(), running_count);
        
        stats
    }

    pub async fn rotate_root_key(&self, new_key: &[u8]) -> Result<(), PlatformError> {
        if new_key.len() < 16 {
            return Err(PlatformError::Validation("Root key must be at least 16 bytes".to_string()));
        }
        
        let mut state = self.state.write();
        state.root_key = new_key.to_vec();
        
        info!("TEE root key rotated successfully");
        
        Ok(())
    }

    pub fn verify_remote_attestation(
        &self,
        remote_report: &AttestationReport,
        expected_mr_enclave: Option<&[u8]>,
        expected_mr_signer: Option<&[u8]>,
    ) -> Result<VerificationResult, PlatformError> {
        let state = self.state.read();
        
        let signature_valid = verify_hmac_sha256(
            &state.root_key,
            &remote_report.quote,
            &remote_report.signature,
        );
        
        let mut trust_level = if signature_valid {
            TrustLevel::Partial
        } else {
            TrustLevel::Untrusted
        };
        
        let mut details = Vec::new();
        
        if signature_valid {
            details.push("Signature verified".to_string());
            
            if let Some(expected) = expected_mr_enclave {
                if remote_report.mr_enclave == expected {
                    trust_level = TrustLevel::Full;
                    details.push("MRENCLAVE verified".to_string());
                } else {
                    return Ok(VerificationResult {
                        is_valid: false,
                        enclave_id: remote_report.enclave_id.clone(),
                        timestamp: current_timestamp(),
                        details: Some("MRENCLAVE mismatch".to_string()),
                        trust_level: TrustLevel::Untrusted,
                    });
                }
            }
            
            if let Some(expected) = expected_mr_signer {
                if remote_report.mr_signer == expected {
                    trust_level = TrustLevel::Verified;
                    details.push("MRSIGNER verified".to_string());
                }
            }
        } else {
            details.push("Invalid signature".to_string());
        }
        
        Ok(VerificationResult {
            is_valid: signature_valid,
            enclave_id: remote_report.enclave_id.clone(),
            timestamp: current_timestamp(),
            details: Some(details.join("; ")),
            trust_level,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_enclave_lifecycle() {
        let root_key = b"test_root_key_12345678";
        let tee = TrustedExecutionEnvironment::new(root_key);
        
        let config = EnclaveConfiguration {
            enclave_type: EnclaveType::Simulated,
            memory_size_mb: 128,
            thread_count: 2,
            enable_debug: false,
            policy: Some(EnclavePolicy::default()),
        };
        
        let enclave = tee.create_enclave(config).await.unwrap();
        assert_eq!(enclave.status, EnclaveStatus::Created);
        
        let report = tee.attest_enclave(&enclave.enclave_id).await.unwrap();
        assert_eq!(report.enclave_id, enclave.enclave_id);
        
        tee.start_enclave(&enclave.enclave_id).await.unwrap();
        let status = tee.get_enclave_status(&enclave.enclave_id).unwrap();
        assert_eq!(status, EnclaveStatus::Running);
    }

    #[tokio::test]
    async fn test_secure_operations() {
        let root_key = b"test_root_key_12345678";
        let tee = TrustedExecutionEnvironment::new(root_key);
        
        let config = EnclaveConfiguration {
            enclave_type: EnclaveType::Simulated,
            memory_size_mb: 128,
            thread_count: 1,
            enable_debug: false,
            policy: Some(EnclavePolicy::default()),
        };
        
        let enclave = tee.create_enclave(config).await.unwrap();
        tee.attest_enclave(&enclave.enclave_id).await.unwrap();
        tee.start_enclave(&enclave.enclave_id).await.unwrap();
        
        let plaintext = b"Hello, secure world!";
        let encrypted = tee.execute_secure_operation(
            &enclave.enclave_id,
            "encrypt",
            plaintext,
        ).await.unwrap();
        
        assert_ne!(encrypted, plaintext.to_vec());
        
        let decrypted = tee.execute_secure_operation(
            &enclave.enclave_id,
            "decrypt",
            &encrypted,
        ).await.unwrap();
        
        assert_eq!(decrypted, plaintext.to_vec());
    }

    #[tokio::test]
    async fn test_attestation_verification() {
        let root_key = b"test_root_key_12345678";
        let tee = TrustedExecutionEnvironment::new(root_key);
        
        let config = EnclaveConfiguration {
            enclave_type: EnclaveType::Simulated,
            memory_size_mb: 64,
            thread_count: 1,
            enable_debug: false,
            policy: None,
        };
        
        let enclave = tee.create_enclave(config).await.unwrap();
        let report = tee.attest_enclave(&enclave.enclave_id).await.unwrap();
        
        let result = tee.verify_attestation(
            &enclave.enclave_id,
            Some(&report.mr_enclave),
            Some(&report.mr_signer),
        ).await.unwrap();
        
        assert!(result.is_valid);
        assert_eq!(result.trust_level, TrustLevel::Verified);
    }
}
