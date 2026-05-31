use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use uuid::Uuid;

use crate::domain::entity::{BinaryResponse, SignedRequest};
use crate::domain::run_instance::RunInstance;
use crate::infra::cache::Cache;
use crate::infra::config::TEEConfig;
use crate::infra::crypto::CryptoService;
use crate::infra::error::{AppError, AppResult};
use crate::infra::multi_level_cache::{CacheConfig, CacheStats, MultiLevelCache};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Enclave {
    pub enclave_id: String,
    pub enclave_type: String,
    pub status: EnclaveStatus,
    pub memory_mb: u32,
    pub attestation_status: AttestationStatus,
    pub attestation_report: Option<AttestationReport>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub last_heartbeat: Option<chrono::DateTime<chrono::Utc>>,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum EnclaveStatus {
    Creating,
    Running,
    Paused,
    Terminating,
    Terminated,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AttestationStatus {
    Pending,
    Verified,
    Failed,
    Expired,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AttestationReport {
    pub report_id: String,
    pub enclave_id: String,
    pub quote: Vec<u8>,
    pub signature: String,
    pub timestamp: chrono::DateTime<chrono::Utc>,
    pub mr_enclave: String,
    pub mr_signer: String,
    pub isv_prod_id: u16,
    pub isv_svn: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateEnclaveRequest {
    pub enclave_type: String,
    pub memory_mb: u32,
    #[serde(default)]
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AttestationRequest {
    pub enclave_id: String,
    pub nonce: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AttestationResponse {
    pub report: AttestationReport,
    pub verified: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheInvalidationRequest {
    pub pattern: Option<String>,
    pub keys: Option<Vec<String>>,
    pub invalidate_all: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheWarmUpRequest {
    pub enclave_ids: Vec<String>,
    pub ttl_seconds: Option<u64>,
}

#[derive(Debug, Clone, Serialize)]
pub struct CacheStatusResponse {
    pub l1_max_size: usize,
    pub l1_current_size: usize,
    pub l2_enabled: bool,
    pub stats: CacheStats,
}

pub struct TEEService {
    config: TEEConfig,
    enclaves: std::sync::Arc<parking_lot::Mutex<HashMap<String, Enclave>>>,
    enclave_cache: MultiLevelCache<Enclave>,
    attestation_cache: MultiLevelCache<AttestationReport>,
}

impl TEEService {
    pub fn new(config: TEEConfig) -> Self {
        let enclave_cache_config = CacheConfig {
            l1_max_size: 1000,
            l1_ttl_seconds: 300,
            l2_ttl_seconds: 1800,
            l2_enabled: false,
            cache_name: "tee_enclaves".to_string(),
        };
        
        let attestation_cache_config = CacheConfig {
            l1_max_size: 500,
            l1_ttl_seconds: 60,
            l2_ttl_seconds: 300,
            l2_enabled: false,
            cache_name: "tee_attestations".to_string(),
        };

        Self {
            config,
            enclaves: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            enclave_cache: MultiLevelCache::new(enclave_cache_config),
            attestation_cache: MultiLevelCache::new(attestation_cache_config),
        }
    }

    pub fn with_cache(config: TEEConfig, cache: Arc<Cache>) -> Self {
        let enclave_cache_config = CacheConfig {
            l1_max_size: 1000,
            l1_ttl_seconds: 300,
            l2_ttl_seconds: 1800,
            l2_enabled: true,
            cache_name: "tee_enclaves".to_string(),
        };
        
        let attestation_cache_config = CacheConfig {
            l1_max_size: 500,
            l1_ttl_seconds: 60,
            l2_ttl_seconds: 300,
            l2_enabled: true,
            cache_name: "tee_attestations".to_string(),
        };

        Self {
            config,
            enclaves: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            enclave_cache: MultiLevelCache::with_l2(enclave_cache_config, cache.clone()),
            attestation_cache: MultiLevelCache::with_l2(attestation_cache_config, cache),
        }
    }

    pub async fn create_enclave(&self, request: CreateEnclaveRequest) -> AppResult<Enclave> {
        let enclaves = self.enclaves.lock();
        if enclaves.len() as u32 >= self.config.max_enclaves {
            return Err(AppError::ResourceExhausted(
                "Maximum number of enclaves reached".into(),
            ));
        }
        drop(enclaves);

        if request.memory_mb > self.config.enclave_memory_mb {
            return Err(AppError::ValidationError(format!(
                "Requested memory {}MB exceeds maximum {}MB",
                request.memory_mb, self.config.enclave_memory_mb
            )));
        }

        let enclave = Enclave {
            enclave_id: format!("enc_{}", Uuid::new_v4().simple()),
            enclave_type: request.enclave_type,
            status: EnclaveStatus::Creating,
            memory_mb: request.memory_mb,
            attestation_status: AttestationStatus::Pending,
            attestation_report: None,
            created_at: chrono::Utc::now(),
            last_heartbeat: None,
            labels: request.labels,
        };

        self.enclaves
            .lock()
            .insert(enclave.enclave_id.clone(), enclave.clone());

        Ok(enclave)
    }

    pub async fn start_enclave(&self, enclave_id: &str) -> AppResult<Enclave> {
        let mut enclaves = self.enclaves.lock();
        let enclave = enclaves
            .get_mut(enclave_id)
            .ok_or_else(|| AppError::NotFound(format!("Enclave {} not found", enclave_id)))?;

        if enclave.status == EnclaveStatus::Terminated || enclave.status == EnclaveStatus::Error {
            return Err(AppError::ValidationError(format!(
                "Cannot start enclave in {:?} state",
                enclave.status
            )));
        }

        enclave.status = EnclaveStatus::Running;
        enclave.last_heartbeat = Some(chrono::Utc::now());
        let result = enclave.clone();
        drop(enclaves);

        self.enclave_cache.delete(enclave_id).await?;

        Ok(result)
    }

    pub async fn stop_enclave(&self, enclave_id: &str) -> AppResult<Enclave> {
        let mut enclaves = self.enclaves.lock();
        let enclave = enclaves
            .get_mut(enclave_id)
            .ok_or_else(|| AppError::NotFound(format!("Enclave {} not found", enclave_id)))?;

        enclave.status = EnclaveStatus::Paused;
        let result = enclave.clone();
        drop(enclaves);

        self.enclave_cache.delete(enclave_id).await?;

        Ok(result)
    }

    pub async fn terminate_enclave(&self, enclave_id: &str) -> AppResult<()> {
        let mut enclaves = self.enclaves.lock();
        let enclave = enclaves
            .get_mut(enclave_id)
            .ok_or_else(|| AppError::NotFound(format!("Enclave {} not found", enclave_id)))?;

        enclave.status = EnclaveStatus::Terminating;

        enclaves.remove(enclave_id);
        drop(enclaves);

        self.enclave_cache.delete(enclave_id).await?;
        self.attestation_cache.delete(enclave_id).await?;

        Ok(())
    }

    pub async fn get_enclave(&self, enclave_id: &str) -> AppResult<Enclave> {
        if let Some(cached) = self.enclave_cache.get(enclave_id).await? {
            return Ok(cached);
        }

        let enclaves = self.enclaves.lock();
        let enclave = enclaves
            .get(enclave_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Enclave {} not found", enclave_id)))?;
        
        self.enclave_cache.put(enclave_id, enclave.clone(), None).await?;
        
        Ok(enclave)
    }

    pub async fn list_enclaves(&self) -> AppResult<Vec<Enclave>> {
        let enclaves = self.enclaves.lock();
        Ok(enclaves.values().cloned().collect())
    }

    pub async fn generate_attestation_report(
        &self,
        request: AttestationRequest,
    ) -> AppResult<AttestationReport> {
        let cache_key = format!("{}:{}", request.enclave_id, request.nonce);
        if let Some(cached) = self.attestation_cache.get(&cache_key).await? {
            return Ok(cached);
        }

        let enclaves = self.enclaves.lock();
        let enclave = enclaves
            .get(&request.enclave_id)
            .ok_or_else(|| AppError::NotFound(format!("Enclave {} not found", request.enclave_id)))?;

        if enclave.status != EnclaveStatus::Running {
            return Err(AppError::ValidationError(format!(
                "Enclave must be running for attestation, current state: {:?}",
                enclave.status
            )));
        }

        let mut quote_data = Vec::new();
        quote_data.extend_from_slice(request.nonce.as_bytes());
        quote_data.extend_from_slice(enclave.enclave_id.as_bytes());
        quote_data.extend_from_slice(&enclave.memory_mb.to_le_bytes());

        let quote = CryptoService::sha256_hash(&quote_data);
        let signature = CryptoService::sha256_hex(&quote);

        let report = AttestationReport {
            report_id: format!("rpt_{}", Uuid::new_v4().simple()),
            enclave_id: request.enclave_id.clone(),
            quote,
            signature,
            timestamp: chrono::Utc::now(),
            mr_enclave: CryptoService::sha256_hex(enclave.enclave_id.as_bytes()),
            mr_signer: CryptoService::sha256_hex(b"trusted_signer"),
            isv_prod_id: 1,
            isv_svn: 1,
        };

        self.attestation_cache.put(&cache_key, report.clone(), None).await?;

        Ok(report)
    }

    pub async fn verify_attestation(&self, report: &AttestationReport) -> AppResult<bool> {
        let enclaves = self.enclaves.lock();
        let enclave = enclaves
            .get(&report.enclave_id)
            .ok_or_else(|| AppError::NotFound(format!("Enclave {} not found", report.enclave_id)))?;

        let expected_mr_enclave = CryptoService::sha256_hex(enclave.enclave_id.as_bytes());
        let signature_valid = CryptoService::sha256_hex(&report.quote) == report.signature;

        Ok(signature_valid && report.mr_enclave == expected_mr_enclave)
    }

    pub async fn execute_secure_function(
        &self,
        enclave_id: &str,
        signed_request: SignedRequest,
    ) -> AppResult<BinaryResponse> {
        if !signed_request.verify_timestamp(self.config.attestation_timeout_ms as i64 / 1000) {
            return Err(AppError::ValidationError("Request timestamp expired".into()));
        }

        let enclaves = self.enclaves.lock();
        let enclave = enclaves
            .get(enclave_id)
            .ok_or_else(|| AppError::NotFound(format!("Enclave {} not found", enclave_id)))?;

        if enclave.status != EnclaveStatus::Running {
            return Err(AppError::ValidationError(format!(
                "Enclave must be running, current state: {:?}",
                enclave.status
            )));
        }

        if enclave.attestation_status != AttestationStatus::Verified {
            return Err(AppError::ValidationError(
                "Enclave not attested, cannot execute secure function".into(),
            ));
        }

        let result = self.process_enclave_request(enclave_id, &signed_request.payload).await?;

        Ok(BinaryResponse::new(result))
    }

    async fn process_enclave_request(&self, enclave_id: &str, payload: &[u8]) -> AppResult<Vec<u8>> {
        let mut result = Vec::new();
        result.extend_from_slice(enclave_id.as_bytes());
        result.extend_from_slice(b":processed:");
        result.extend_from_slice(payload);

        Ok(CryptoService::sha256_hash(&result))
    }

    pub async fn heartbeat(&self, enclave_id: &str) -> AppResult<()> {
        let mut enclaves = self.enclaves.lock();
        let enclave = enclaves
            .get_mut(enclave_id)
            .ok_or_else(|| AppError::NotFound(format!("Enclave {} not found", enclave_id)))?;

        enclave.last_heartbeat = Some(chrono::Utc::now());

        Ok(())
    }

    pub fn create_run_instance(&self, enclave_id: &str) -> RunInstance {
        let mut instance = RunInstance::new(enclave_id.to_string());
        instance.set_metadata("module", "tee");
        instance
    }

    pub async fn invalidate_cache(&self, request: CacheInvalidationRequest) -> AppResult<usize> {
        let mut invalidated = 0;

        if request.invalidate_all {
            self.enclave_cache.clear().await?;
            self.attestation_cache.clear().await?;
            return Ok(self.enclave_cache.stats().l1_misses as usize + 
                     self.attestation_cache.stats().l1_misses as usize);
        }

        if let Some(keys) = request.keys {
            for key in &keys {
                self.enclave_cache.delete(key).await?;
                self.attestation_cache.delete(key).await?;
                invalidated += 1;
            }
        }

        if let Some(pattern) = request.pattern {
            self.enclave_cache.invalidate_pattern(&pattern).await?;
            invalidated += 1;
        }

        Ok(invalidated)
    }

    pub async fn warm_up_cache(&self, request: CacheWarmUpRequest) -> AppResult<usize> {
        let mut entries = Vec::new();
        
        for enclave_id in &request.enclave_ids {
            if let Ok(enclave) = self.get_enclave(enclave_id).await {
                entries.push((enclave_id.clone(), enclave));
            }
        }

        let count = self.enclave_cache.warm_up(entries, request.ttl_seconds).await?;
        Ok(count)
    }

    pub fn get_cache_status(&self) -> CacheStatusResponse {
        let enclave_stats = self.enclave_cache.stats();
        
        CacheStatusResponse {
            l1_max_size: 1000,
            l1_current_size: enclave_stats.l1_hits as usize + enclave_stats.l1_misses as usize,
            l2_enabled: true,
            stats: enclave_stats,
        }
    }

    pub fn reset_cache_stats(&self) {
        self.enclave_cache.reset_stats();
        self.attestation_cache.reset_stats();
    }
}
