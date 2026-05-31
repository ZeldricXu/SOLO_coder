use crate::config::{ConfigurationListener, DynamicConfigManager, TeeConfig};
use crate::models::AppError;
use crate::utils::{
    generate_id, sha256_hex, hmac_sha256_hex, validate_timestamp, verify_signature,
    BinaryResponse, current_datetime,
};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::sync::{Arc, RwLock};
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnclaveStatus {
    Created,
    Initializing,
    Running,
    Attested,
    Paused,
    Stopped,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum TeeTechnology {
    SGX,
    SEV,
    TrustZone,
    Generic,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Enclave {
    pub id: String,
    pub technology: TeeTechnology,
    pub status: EnclaveStatus,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub metadata: serde_json::Value,
    pub measurement: Option<String>,
    pub attestation_token: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnclaveCreateRequest {
    pub technology: TeeTechnology,
    pub metadata: serde_json::Value,
    pub signature: String,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AttestationRequest {
    pub enclave_id: String,
    pub challenge: String,
    pub signature: String,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AttestationResponse {
    pub enclave_id: String,
    pub measurement: String,
    pub quote: String,
    pub signature: String,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SecureRequest<T> {
    pub payload: T,
    pub signature: String,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnclaveExecuteRequest {
    pub enclave_id: String,
    pub command: String,
    pub arguments: serde_json::Value,
    pub signature: String,
    pub timestamp: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnclaveExecuteResult {
    pub enclave_id: String,
    pub result: serde_json::Value,
    pub execution_time_ms: u64,
}

pub struct TeeManager {
    config_manager: Arc<DynamicConfigManager>,
    current_config: RwLock<TeeConfig>,
    enclaves: DashMap<String, Enclave>,
    secret_key: Arc<Vec<u8>>,
}

pub struct TeeConfigChangeListener {
    manager_config: RwLock<Arc<TeeConfig>>,
}

impl ConfigurationListener for TeeConfigChangeListener {
    fn on_config_changed(&self, module: &str, old_version: u32, new_version: u32) {
        if module == "tee" {
            tracing::info!(
                "TEE configuration changed: v{} -> v{}",
                old_version,
                new_version
            );
        }
    }

    fn on_config_rolled_back(&self, module: &str, from_version: u32, to_version: u32) {
        if module == "tee" {
            tracing::warn!(
                "TEE configuration rolled back: v{} -> v{}",
                from_version,
                to_version
            );
        }
    }
}

impl TeeManager {
    pub fn new(config_manager: Arc<DynamicConfigManager>) -> Result<Self, AppError> {
        let initial_config = config_manager.get_tee_config()?;
        let secret_key = Arc::new(Uuid::new_v4().as_bytes().to_vec());

        let manager = Self {
            config_manager: config_manager.clone(),
            current_config: RwLock::new(initial_config),
            enclaves: DashMap::new(),
            secret_key,
        };

        let listener = Arc::new(TeeConfigChangeListener {
            manager_config: RwLock::new(Arc::new(manager.get_config_snapshot())),
        });
        config_manager.add_listener("tee", listener);

        Ok(manager)
    }

    pub fn with_secret_key(
        config_manager: Arc<DynamicConfigManager>,
        secret_key: Vec<u8>,
    ) -> Result<Self, AppError> {
        let initial_config = config_manager.get_tee_config()?;

        let manager = Self {
            config_manager: config_manager.clone(),
            current_config: RwLock::new(initial_config),
            enclaves: DashMap::new(),
            secret_key: Arc::new(secret_key),
        };

        let listener = Arc::new(TeeConfigChangeListener {
            manager_config: RwLock::new(Arc::new(manager.get_config_snapshot())),
        });
        config_manager.add_listener("tee", listener);

        Ok(manager)
    }

    pub fn refresh_config(&self) -> Result<(), AppError> {
        let new_config = self.config_manager.get_tee_config()?;
        *self.current_config.write().unwrap() = new_config;
        Ok(())
    }

    fn get_config_snapshot(&self) -> TeeConfig {
        self.current_config.read().unwrap().clone()
    }

    fn with_config<F, R>(&self, f: F) -> R
    where
        F: FnOnce(&TeeConfig) -> R,
    {
        let config = self.current_config.read().unwrap();
        f(&config)
    }

    pub fn validate_request<T: Serialize>(
        &self,
        request: &SecureRequest<T>,
    ) -> Result<(), AppError> {
        if !validate_timestamp(request.timestamp, 300) {
            return Err(AppError::Validation("Timestamp expired or invalid".to_string()));
        }

        let payload_bytes = serde_json::to_vec(&request.payload)
            .map_err(|e| AppError::Validation(format!("Failed to serialize payload: {}", e)))?;

        let mut data_to_sign = payload_bytes.clone();
        data_to_sign.extend_from_slice(&request.timestamp.to_le_bytes());

        let signature_bytes = hex::decode(&request.signature)
            .map_err(|_| AppError::Validation("Invalid signature format".to_string()))?;

        if !verify_signature(&data_to_sign, &signature_bytes, &self.secret_key) {
            return Err(AppError::Validation("Invalid signature".to_string()));
        }

        Ok(())
    }

    pub fn create_enclave(&self, request: EnclaveCreateRequest) -> Result<Enclave, AppError> {
        self.with_config(|config| {
            if self.enclaves.len() >= config.max_enclaves {
                return Err(AppError::Internal("Maximum enclave limit reached".to_string()));
            }

            let tech_str = match request.technology {
                TeeTechnology::SGX => "SGX",
                TeeTechnology::SEV => "SEV",
                TeeTechnology::TrustZone => "TrustZone",
                TeeTechnology::Generic => "Generic",
            };

            if !config.supported_techs.iter().any(|t| t == tech_str) {
                return Err(AppError::Validation(format!(
                    "Unsupported TEE technology: {:?}",
                    request.technology
                )));
            }

            let enclave_id = generate_id("enc");
            let now = current_datetime();
            let measurement = Some(self.generate_measurement(&enclave_id, &request.technology));

            let enclave = Enclave {
                id: enclave_id,
                technology: request.technology,
                status: EnclaveStatus::Created,
                created_at: now,
                updated_at: now,
                metadata: request.metadata,
                measurement,
                attestation_token: None,
            };

            self.enclaves.insert(enclave.id.clone(), enclave.clone());
            Ok(enclave)
        })
    }

    pub fn get_enclave(&self, enclave_id: &str) -> Option<Enclave> {
        self.enclaves.get(enclave_id).map(|e| e.clone())
    }

    pub fn list_enclaves(&self) -> Vec<Enclave> {
        self.enclaves.iter().map(|e| e.clone()).collect()
    }

    pub fn update_enclave_status(
        &self,
        enclave_id: &str,
        new_status: EnclaveStatus,
    ) -> Result<Enclave, AppError> {
        let mut enclave = self
            .enclaves
            .get_mut(enclave_id)
            .ok_or_else(|| AppError::NotFound(format!("Enclave not found: {}", enclave_id)))?;

        enclave.status = new_status;
        enclave.updated_at = current_datetime();
        Ok(enclave.clone())
    }

    pub fn perform_remote_attestation(
        &self,
        request: AttestationRequest,
    ) -> Result<AttestationResponse, AppError> {
        let enclave = self
            .enclaves
            .get(&request.enclave_id)
            .ok_or_else(|| {
                AppError::NotFound(format!("Enclave not found: {}", request.enclave_id))
            })?;

        if enclave.status != EnclaveStatus::Running && enclave.status != EnclaveStatus::Created {
            return Err(AppError::Validation(format!(
                "Enclave is not in valid state for attestation: {:?}",
                enclave.status
            )));
        }

        let measurement = enclave
            .measurement
            .clone()
            .ok_or_else(|| AppError::Internal("Enclave measurement not available".to_string()))?;

        let quote = self.generate_quote(&request.enclave_id, &request.challenge);
        let signature = self.sign_attestation(&quote, &measurement, request.timestamp);

        let response = AttestationResponse {
            enclave_id: request.enclave_id.clone(),
            measurement,
            quote,
            signature,
            timestamp: request.timestamp,
        };

        let attestation_token = sha256_hex(
            format!("{}:{}", request.enclave_id, current_datetime().timestamp()).as_bytes(),
        );

        drop(enclave);
        let mut enclave_mut = self.enclaves.get_mut(&request.enclave_id).unwrap();
        enclave_mut.status = EnclaveStatus::Attested;
        enclave_mut.attestation_token = Some(attestation_token);
        enclave_mut.updated_at = current_datetime();

        Ok(response)
    }

    pub fn verify_attestation(&self, response: &AttestationResponse) -> Result<bool, AppError> {
        let enclave = self
            .enclaves
            .get(&response.enclave_id)
            .ok_or_else(|| {
                AppError::NotFound(format!("Enclave not found: {}", response.enclave_id))
            })?;

        let expected_signature =
            self.sign_attestation(&response.quote, &response.measurement, response.timestamp);

        if expected_signature != response.signature {
            return Ok(false);
        }

        let timeout_valid = self.with_config(|config| {
            validate_timestamp(response.timestamp, config.attestation_timeout_ms / 1000)
        });

        if !timeout_valid {
            return Ok(false);
        }

        Ok(true)
    }

    pub fn execute_in_enclave(
        &self,
        request: EnclaveExecuteRequest,
    ) -> Result<BinaryResponse, AppError> {
        let enclave = self
            .enclaves
            .get(&request.enclave_id)
            .ok_or_else(|| {
                AppError::NotFound(format!("Enclave not found: {}", request.enclave_id))
            })?;

        if enclave.status != EnclaveStatus::Running && enclave.status != EnclaveStatus::Attested {
            return Err(AppError::Validation(format!(
                "Enclave is not in valid state for execution: {:?}",
                enclave.status
            )));
        }

        let start = std::time::Instant::now();

        let result_value = serde_json::json!({
            "command": request.command,
            "status": "executed",
            "enclave_id": request.enclave_id,
            "output": format!("executed_{}", sha256_hex(request.command.as_bytes())),
        });

        let result = EnclaveExecuteResult {
            enclave_id: request.enclave_id,
            result: result_value,
            execution_time_ms: start.elapsed().as_millis() as u64,
        };

        let result_bytes = serde_json::to_vec(&result)
            .map_err(|e| AppError::Internal(format!("Failed to serialize result: {}", e)))?;

        Ok(BinaryResponse::new(result_bytes))
    }

    pub fn destroy_enclave(&self, enclave_id: &str) -> Result<(), AppError> {
        if self.enclaves.remove(enclave_id).is_none() {
            return Err(AppError::NotFound(format!("Enclave not found: {}", enclave_id)));
        }
        Ok(())
    }

    fn generate_measurement(&self, enclave_id: &str, tech: &TeeTechnology) -> String {
        let tech_str = match tech {
            TeeTechnology::SGX => "SGX",
            TeeTechnology::SEV => "SEV",
            TeeTechnology::TrustZone => "TZ",
            TeeTechnology::Generic => "GEN",
        };
        sha256_hex(format!("{}:{}:{}", enclave_id, tech_str, current_datetime().timestamp()).as_bytes())
    }

    fn generate_quote(&self, enclave_id: &str, challenge: &str) -> String {
        sha256_hex(
            format!("quote:{}:{}:{}", enclave_id, challenge, current_datetime().timestamp()).as_bytes(),
        )
    }

    fn sign_attestation(&self, quote: &str, measurement: &str, timestamp: i64) -> String {
        let data = format!("{}:{}:{}", quote, measurement, timestamp);
        hmac_sha256_hex(&self.secret_key, data.as_bytes())
    }

    pub fn enclaves_count(&self) -> usize {
        self.enclaves.len()
    }

    pub fn get_current_config(&self) -> TeeConfig {
        self.get_config_snapshot()
    }

    pub fn get_config_manager(&self) -> Arc<DynamicConfigManager> {
        self.config_manager.clone()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TeeEvent {
    pub event_type: String,
    pub enclave_id: String,
    pub timestamp: DateTime<Utc>,
    pub details: serde_json::Value,
}

impl TeeEvent {
    pub fn new(event_type: &str, enclave_id: &str, details: serde_json::Value) -> Self {
        Self {
            event_type: event_type.to_string(),
            enclave_id: enclave_id.to_string(),
            timestamp: current_datetime(),
            details,
        }
    }
}
