use crate::config::{ConfigurationListener, DynamicConfigManager, MpcConfig};
use crate::models::AppError;
use crate::utils::{generate_id, sha256_hex, current_datetime, random_bytes};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use num_bigint::BigUint;
use num_traits::Zero;
use rand::Rng;
use rand_chacha::ChaCha20Rng;
use rand::SeedableRng;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, RwLock};

pub mod strategies;
pub use strategies::{
    MpcComputationStrategy, StrategyInfo, StrategyBox, StrategyRegistry,
    DefaultMpcStrategy, SecureMpcStrategy, FastMpcStrategy,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MpcProtocol {
    Shamir,
    GarbledCircuit,
    ObliviousTransfer,
    SPDZ,
    ABY3,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MpcSessionStatus {
    Created,
    WaitingForParticipants,
    InputsCollected,
    Computing,
    Completed,
    Failed,
    Timeout,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MpcOperation {
    Add,
    Multiply,
    Compare,
    Custom,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpcParticipant {
    pub id: String,
    pub index: usize,
    pub public_key: Vec<u8>,
    pub is_ready: bool,
    pub joined_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EncryptedInput {
    pub participant_id: String,
    pub encrypted_value: Vec<u8>,
    pub commitment: String,
    pub nonce: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpcSession {
    pub id: String,
    pub protocol: MpcProtocol,
    pub operation: MpcOperation,
    pub status: MpcSessionStatus,
    pub min_participants: usize,
    pub max_participants: usize,
    pub participants: HashMap<String, MpcParticipant>,
    pub encrypted_inputs: HashMap<String, EncryptedInput>,
    pub result: Option<Vec<u8>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub timeout_at: DateTime<Utc>,
    pub metadata: serde_json::Value,
    pub strategy_used: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpcSessionCreateRequest {
    pub protocol: MpcProtocol,
    pub operation: MpcOperation,
    pub min_participants: Option<usize>,
    pub max_participants: Option<usize>,
    pub timeout_secs: Option<u64>,
    pub metadata: serde_json::Value,
    pub preferred_strategy: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpcJoinRequest {
    pub session_id: String,
    pub participant_id: String,
    pub public_key: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpcSubmitInputRequest {
    pub session_id: String,
    pub participant_id: String,
    pub encrypted_value: Vec<u8>,
    pub commitment: String,
    pub nonce: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpcDecryptRequest {
    pub session_id: String,
    pub participant_id: String,
    pub result_share: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpcResult {
    pub session_id: String,
    pub decrypted_value: Vec<u8>,
    pub participants_used: Vec<String>,
    pub computation_time_ms: u64,
    pub strategy_used: String,
}

pub struct MpcManager {
    config: MpcConfig,
    config_manager: Option<Arc<DynamicConfigManager>>,
    sessions: DashMap<String, MpcSession>,
    secrets: Arc<DashMap<String, Vec<u8>>>,
    strategies: RwLock<StrategyRegistry>,
}

pub struct MpcStrategyChangeListener;

impl ConfigurationListener for MpcStrategyChangeListener {
    fn on_config_changed(&self, module: &str, _old_version: u32, _new_version: u32) {
        if module == "mpc" {
            tracing::info!("MPC strategy configuration changed");
        }
    }

    fn on_config_rolled_back(&self, module: &str, _from_version: u32, _to_version: u32) {
        if module == "mpc" {
            tracing::warn!("MPC strategy configuration rolled back");
        }
    }
}

impl MpcManager {
    pub fn new(config: MpcConfig) -> Self {
        Self {
            config,
            config_manager: None,
            sessions: DashMap::new(),
            secrets: Arc::new(DashMap::new()),
            strategies: RwLock::new(StrategyRegistry::new()),
        }
    }

    pub fn with_config_manager(
        config: MpcConfig,
        config_manager: Arc<DynamicConfigManager>,
    ) -> Self {
        let manager = Self {
            config,
            config_manager: Some(config_manager.clone()),
            sessions: DashMap::new(),
            secrets: Arc::new(DashMap::new()),
            strategies: RwLock::new(StrategyRegistry::new()),
        };

        let listener = Arc::new(MpcStrategyChangeListener);
        config_manager.add_listener("mpc", listener);

        manager
    }

    pub fn register_strategy(&self, strategy: StrategyBox) {
        let mut registry = self.strategies.write().unwrap();
        registry.register(strategy);
    }

    pub fn set_active_strategy(&self, strategy_name: &str) -> Result<(), AppError> {
        let mut registry = self.strategies.write().unwrap();
        registry.set_active(strategy_name)
    }

    pub fn get_active_strategy_name(&self) -> String {
        self.strategies.read().unwrap().active_strategy_name().to_string()
    }

    pub fn list_available_strategies(&self) -> Vec<StrategyInfo> {
        self.strategies.read().unwrap().list_strategies()
    }

    pub fn create_session(
        &self,
        request: MpcSessionCreateRequest,
    ) -> Result<MpcSession, AppError> {
        let min = request.min_participants.unwrap_or(self.config.min_participants);
        let max = request.max_participants.unwrap_or(self.config.max_participants);

        if min < self.config.min_participants {
            return Err(AppError::Validation(format!(
                "Minimum participants must be at least {}",
                self.config.min_participants
            )));
        }

        if max > self.config.max_participants {
            return Err(AppError::Validation(format!(
                "Maximum participants must be at most {}",
                self.config.max_participants
            )));
        }

        if min > max {
            return Err(AppError::Validation(
                "Minimum participants cannot exceed maximum".to_string(),
            ));
        }

        let session_id = generate_id("mpc");
        let now = current_datetime();
        let timeout_secs = request.timeout_secs.unwrap_or(self.config.protocol_timeout_secs);
        let timeout_at = now + chrono::Duration::seconds(timeout_secs as i64);

        let strategy_used = if let Some(ref preferred) = request.preferred_strategy {
            let registry = self.strategies.read().unwrap();
            if registry.get(preferred).is_some() {
                Some(preferred.clone())
            } else {
                Some(registry.active_strategy_name().to_string())
            }
        } else {
            None
        };

        let session = MpcSession {
            id: session_id,
            protocol: request.protocol,
            operation: request.operation,
            status: MpcSessionStatus::Created,
            min_participants: min,
            max_participants: max,
            participants: HashMap::new(),
            encrypted_inputs: HashMap::new(),
            result: None,
            created_at: now,
            updated_at: now,
            timeout_at,
            metadata: request.metadata,
            strategy_used,
        };

        self.sessions.insert(session.id.clone(), session.clone());
        Ok(session)
    }

    pub fn get_session(&self, session_id: &str) -> Option<MpcSession> {
        self.sessions.get(session_id).map(|s| s.clone())
    }

    pub fn list_sessions(&self) -> Vec<MpcSession> {
        self.sessions.iter().map(|s| s.clone()).collect()
    }

    pub fn join_session(&self, request: MpcJoinRequest) -> Result<MpcParticipant, AppError> {
        let mut session = self
            .sessions
            .get_mut(&request.session_id)
            .ok_or_else(|| AppError::NotFound(format!("Session not found: {}", request.session_id)))?;

        if session.status != MpcSessionStatus::Created
            && session.status != MpcSessionStatus::WaitingForParticipants
        {
            return Err(AppError::Validation(format!(
                "Session is not accepting new participants: {:?}",
                session.status
            )));
        }

        if session.participants.len() >= session.max_participants {
            return Err(AppError::Validation(
                "Maximum participants reached for this session".to_string(),
            ));
        }

        if session.participants.contains_key(&request.participant_id) {
            return Err(AppError::Validation(
                "Participant already joined this session".to_string(),
            ));
        }

        let participant = MpcParticipant {
            id: request.participant_id.clone(),
            index: session.participants.len(),
            public_key: request.public_key,
            is_ready: false,
            joined_at: current_datetime(),
        };

        session
            .participants
            .insert(request.participant_id, participant.clone());

        if session.participants.len() >= session.min_participants {
            session.status = MpcSessionStatus::WaitingForParticipants;
        }
        session.updated_at = current_datetime();

        Ok(participant)
    }

    pub fn submit_encrypted_input(
        &self,
        request: MpcSubmitInputRequest,
    ) -> Result<(), AppError> {
        let mut session = self
            .sessions
            .get_mut(&request.session_id)
            .ok_or_else(|| AppError::NotFound(format!("Session not found: {}", request.session_id)))?;

        if session.status != MpcSessionStatus::WaitingForParticipants {
            return Err(AppError::Validation(format!(
                "Session is not in input collection phase: {:?}",
                session.status
            )));
        }

        let participant = session
            .participants
            .get_mut(&request.participant_id)
            .ok_or_else(|| {
                AppError::NotFound(format!(
                    "Participant not found in session: {}",
                    request.participant_id
                ))
            })?;

        if participant.is_ready {
            return Err(AppError::Validation(
                "Participant has already submitted input".to_string(),
            ));
        }

        let encrypted_input = EncryptedInput {
            participant_id: request.participant_id.clone(),
            encrypted_value: request.encrypted_value,
            commitment: request.commitment,
            nonce: request.nonce,
        };

        session
            .encrypted_inputs
            .insert(request.participant_id.clone(), encrypted_input);
        participant.is_ready = true;

        let all_ready = session.participants.values().all(|p| p.is_ready);
        if all_ready {
            session.status = MpcSessionStatus::InputsCollected;
        }
        session.updated_at = current_datetime();

        Ok(())
    }

    pub fn start_computation(&self, session_id: &str) -> Result<MpcSession, AppError> {
        let mut session = self
            .sessions
            .get_mut(session_id)
            .ok_or_else(|| AppError::NotFound(format!("Session not found: {}", session_id)))?;

        if session.status != MpcSessionStatus::InputsCollected {
            return Err(AppError::Validation(format!(
                "Session is not ready for computation: {:?}",
                session.status
            )));
        }

        if session.encrypted_inputs.len() < session.min_participants {
            return Err(AppError::Validation(
                "Not enough participants have submitted inputs".to_string(),
            ));
        }

        session.status = MpcSessionStatus::Computing;
        session.updated_at = current_datetime();
        Ok(session.clone())
    }

    pub fn execute_computation(&self, session_id: &str) -> Result<MpcResult, AppError> {
        let mut session = self
            .sessions
            .get_mut(session_id)
            .ok_or_else(|| AppError::NotFound(format!("Session not found: {}", session_id)))?;

        if session.status != MpcSessionStatus::Computing {
            return Err(AppError::Validation(format!(
                "Session is not in computing phase: {:?}",
                session.status
            )));
        }

        let start = std::time::Instant::now();

        let inputs: Vec<Vec<u8>> = session
            .encrypted_inputs
            .values()
            .map(|e| e.encrypted_value.clone())
            .collect();

        let registry = self.strategies.read().unwrap();
        let strategy_name = session
            .strategy_used
            .as_ref()
            .map(|s| s.as_str())
            .unwrap_or_else(|| registry.active_strategy_name());

        let strategy = registry
            .get(strategy_name)
            .ok_or_else(|| AppError::Internal(format!("Strategy not found: {}", strategy_name)))?;

        let result = strategy.compute(&session, &inputs)?;

        session.result = Some(result.clone());
        session.status = MpcSessionStatus::Completed;
        session.updated_at = current_datetime();
        session.strategy_used = Some(strategy_name.to_string());

        let participants_used: Vec<String> = session.participants.keys().cloned().collect();

        Ok(MpcResult {
            session_id: session_id.to_string(),
            decrypted_value: result,
            participants_used,
            computation_time_ms: start.elapsed().as_millis() as u64,
            strategy_used: strategy_name.to_string(),
        })
    }

    pub fn decrypt_result_share(
        &self,
        request: MpcDecryptRequest,
    ) -> Result<Vec<u8>, AppError> {
        let session = self
            .sessions
            .get(&request.session_id)
            .ok_or_else(|| AppError::NotFound(format!("Session not found: {}", request.session_id)))?;

        if session.status != MpcSessionStatus::Completed {
            return Err(AppError::Validation(format!(
                "Session is not completed: {:?}",
                session.status
            )));
        }

        if !session.participants.contains_key(&request.participant_id) {
            return Err(AppError::NotFound(format!(
                "Participant not found in session: {}",
                request.participant_id
            )));
        }

        let result = session
            .result
            .clone()
            .ok_or_else(|| AppError::Internal("Session result not available".to_string()))?;

        let decrypted = self.xor_decrypt(&result, &request.result_share);
        Ok(decrypted)
    }

    fn xor_decrypt(&self, data: &[u8], key: &[u8]) -> Vec<u8> {
        data.iter()
            .zip(key.iter().cycle())
            .map(|(d, k)| d ^ k)
            .collect()
    }

    pub fn encrypt_input(&self, plaintext: &[u8]) -> Result<EncryptedInput, AppError> {
        let participant_id = generate_id("part");
        let nonce = random_bytes(12);
        let key = random_bytes(32);

        let encrypted: Vec<u8> = plaintext
            .iter()
            .zip(key.iter().cycle())
            .map(|(p, k)| p ^ k)
            .collect();

        let commitment = sha256_hex(&[&encrypted[..], &nonce[..]].concat());

        self.secrets.insert(participant_id.clone(), key);

        Ok(EncryptedInput {
            participant_id,
            encrypted_value: encrypted,
            commitment,
            nonce,
        })
    }

    pub fn sessions_count(&self) -> usize {
        self.sessions.len()
    }

    pub fn rollback_session(&self, session_id: &str) -> Result<MpcSession, AppError> {
        let mut session = self
            .sessions
            .get_mut(session_id)
            .ok_or_else(|| AppError::NotFound(format!("Session not found: {}", session_id)))?;

        if session.status == MpcSessionStatus::Computing {
            session.status = MpcSessionStatus::InputsCollected;
            session.result = None;
        } else if session.status == MpcSessionStatus::InputsCollected {
            session.status = MpcSessionStatus::WaitingForParticipants;
            session.encrypted_inputs.clear();
            for participant in session.participants.values_mut() {
                participant.is_ready = false;
            }
        } else if session.status == MpcSessionStatus::WaitingForParticipants {
            if session.participants.len() < session.min_participants {
                session.status = MpcSessionStatus::Created;
            }
        }

        session.updated_at = current_datetime();
        Ok(session.clone())
    }

    pub fn get_config(&self) -> MpcConfig {
        self.config.clone()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpcEvent {
    pub event_type: String,
    pub session_id: String,
    pub timestamp: DateTime<Utc>,
    pub details: serde_json::Value,
}

impl MpcEvent {
    pub fn new(event_type: &str, session_id: &str, details: serde_json::Value) -> Self {
        Self {
            event_type: event_type.to_string(),
            session_id: session_id.to_string(),
            timestamp: current_datetime(),
            details,
        }
    }
}
