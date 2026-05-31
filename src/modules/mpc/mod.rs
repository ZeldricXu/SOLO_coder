use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

use crate::domain::run_instance::RunInstance;
use crate::infra::config::MPCConfig;
use crate::infra::crypto::CryptoService;
use crate::infra::error::{AppError, AppResult};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MPCSession {
    pub session_id: String,
    pub protocol: String,
    pub status: MPCCessionStatus,
    pub participants: Vec<String>,
    pub threshold: u32,
    pub inputs: HashMap<String, EncryptedInput>,
    pub result: Option<Vec<u8>>,
    pub computation_type: String,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub deadline: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum MPCCessionStatus {
    Created,
    WaitingForInputs,
    Computing,
    Reconstructing,
    Completed,
    Failed,
    TimedOut,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EncryptedInput {
    pub participant_id: String,
    pub ciphertext: Vec<u8>,
    pub commitment: String,
    pub nonce: String,
    pub timestamp: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateMPCSessionRequest {
    pub protocol: String,
    pub participants: Vec<String>,
    pub threshold: u32,
    pub computation_type: String,
    pub timeout_seconds: u64,
    pub parameters: HashMap<String, serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InputSubmission {
    pub session_id: String,
    pub participant_id: String,
    pub encrypted_input: Vec<u8>,
    pub commitment: String,
    pub nonce: String,
    pub proof: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MPCResult {
    pub session_id: String,
    pub result: Vec<u8>,
    pub decryption_shares: HashMap<String, Vec<u8>>,
    pub checksum: String,
    pub completed_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GarbledCircuit {
    pub circuit_id: String,
    pub gate_count: u32,
    pub input_count: u32,
    pub output_count: u32,
    pub encrypted_gates: Vec<u8>,
    pub labels: HashMap<String, HashMap<String, Vec<u8>>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ObliviousTransfer {
    pub transfer_id: String,
    pub sender: String,
    pub receiver: String,
    pub choices: Vec<bool>,
    pub messages: Vec<(Vec<u8>, Vec<u8>)>,
    pub status: OTStatus,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum OTStatus {
    Initiated,
    Sent,
    Received,
    Completed,
    Failed,
}

pub struct MPCService {
    config: MPCConfig,
    sessions: std::sync::Arc<parking_lot::Mutex<HashMap<String, MPCSession>>>,
    circuits: std::sync::Arc<parking_lot::Mutex<HashMap<String, GarbledCircuit>>>,
    ots: std::sync::Arc<parking_lot::Mutex<HashMap<String, ObliviousTransfer>>>,
    shared_keys: std::sync::Arc<parking_lot::Mutex<HashMap<String, Vec<u8>>>>,
}

impl MPCService {
    pub fn new(config: MPCConfig) -> Self {
        Self {
            config,
            sessions: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            circuits: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            ots: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            shared_keys: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
        }
    }

    pub async fn create_session(&self, request: CreateMPCSessionRequest) -> AppResult<MPCSession> {
        if request.threshold > request.participants.len() as u32 {
            return Err(AppError::ValidationError(
                "Threshold cannot exceed number of participants".into(),
            ));
        }

        if request.threshold < self.config.threshold {
            return Err(AppError::ValidationError(format!(
                "Threshold {} is less than minimum configured threshold {}",
                request.threshold, self.config.threshold
            )));
        }

        let now = chrono::Utc::now();
        let session = MPCSession {
            session_id: format!("mpc_sess_{}", Uuid::new_v4().simple()),
            protocol: request.protocol,
            status: MPCCessionStatus::Created,
            participants: request.participants,
            threshold: request.threshold,
            inputs: HashMap::new(),
            result: None,
            computation_type: request.computation_type,
            created_at: now,
            deadline: now + chrono::Duration::seconds(request.timeout_seconds as i64),
        };

        for participant in &session.participants {
            let key = CryptoService::generate_aes_key();
            self.shared_keys.lock().insert(participant.clone(), key);
        }

        self.sessions.lock().insert(session.session_id.clone(), session.clone());

        Ok(session)
    }

    pub async fn submit_input(&self, submission: InputSubmission) -> AppResult<MPCSession> {
        let mut sessions = self.sessions.lock();
        let session = sessions
            .get_mut(&submission.session_id)
            .ok_or_else(|| AppError::NotFound(format!("Session {} not found", submission.session_id)))?;

        if session.status != MPCCessionStatus::Created && session.status != MPCCessionStatus::WaitingForInputs {
            return Err(AppError::ValidationError(format!(
                "Cannot submit input in session status {:?}",
                session.status
            )));
        }

        if !session.participants.contains(&submission.participant_id) {
            return Err(AppError::PermissionDenied(
                "Participant not part of this session".into(),
            ));
        }

        if session.deadline < chrono::Utc::now() {
            session.status = MPCCessionStatus::TimedOut;
            return Err(AppError::TimeoutError("MPC session timed out".into()));
        }

        self.verify_input_commitment(&submission)?;

        let input = EncryptedInput {
            participant_id: submission.participant_id.clone(),
            ciphertext: submission.encrypted_input,
            commitment: submission.commitment,
            nonce: submission.nonce,
            timestamp: chrono::Utc::now(),
        };

        session.inputs.insert(submission.participant_id, input);

        if session.inputs.len() as u32 >= session.threshold {
            session.status = MPCCessionStatus::Computing;
        } else {
            session.status = MPCCessionStatus::WaitingForInputs;
        }

        Ok(session.clone())
    }

    pub async fn execute_computation(&self, session_id: &str) -> AppResult<MPCResult> {
        let mut sessions = self.sessions.lock();
        let session = sessions
            .get_mut(session_id)
            .ok_or_else(|| AppError::NotFound(format!("Session {} not found", session_id)))?;

        if session.status != MPCCessionStatus::Computing {
            return Err(AppError::ValidationError(format!(
                "Cannot compute in session status {:?}",
                session.status
            )));
        }

        let inputs: Vec<&EncryptedInput> = session.inputs.values().collect();
        let shared_keys = self.shared_keys.lock();

        let decrypted_inputs: Result<Vec<Vec<u8>>, _> = inputs
            .iter()
            .map(|input| {
                let key = shared_keys
                    .get(&input.participant_id)
                    .ok_or_else(|| AppError::InternalError("Shared key not found".into()))?;
                CryptoService::aes_decrypt(key, &input.ciphertext)
            })
            .collect();

        let decrypted_inputs = decrypted_inputs?;

        let result = match session.computation_type.as_str() {
            "sum" => self.compute_sum(&decrypted_inputs),
            "average" => self.compute_average(&decrypted_inputs),
            "max" => self.compute_max(&decrypted_inputs),
            "min" => self.compute_min(&decrypted_inputs),
            "count" => self.compute_count(&decrypted_inputs),
            _ => self.compute_arbitrary(&decrypted_inputs, &session.computation_type),
        }?;

        let encrypted_result = self.encrypt_result_for_participants(&result, session)?;

        let checksum = CryptoService::sha256_hex(&result);

        let mpc_result = MPCResult {
            session_id: session_id.to_string(),
            result: encrypted_result.clone(),
            decryption_shares: HashMap::new(),
            checksum,
            completed_at: chrono::Utc::now(),
        };

        session.result = Some(result);
        session.status = MPCCessionStatus::Completed;

        Ok(mpc_result)
    }

    fn compute_sum(&self, inputs: &[Vec<u8>]) -> AppResult<Vec<u8>> {
        let mut sum: u64 = 0;
        for input in inputs {
            let value = self.bytes_to_u64(input);
            sum = sum.wrapping_add(value);
        }
        Ok(sum.to_le_bytes().to_vec())
    }

    fn compute_average(&self, inputs: &[Vec<u8>]) -> AppResult<Vec<u8>> {
        if inputs.is_empty() {
            return Err(AppError::ValidationError("No inputs to average".into()));
        }
        let mut sum: u64 = 0;
        for input in inputs {
            sum = sum.wrapping_add(self.bytes_to_u64(input));
        }
        let avg = sum / inputs.len() as u64;
        Ok(avg.to_le_bytes().to_vec())
    }

    fn compute_max(&self, inputs: &[Vec<u8>]) -> AppResult<Vec<u8>> {
        let mut max: u64 = 0;
        for input in inputs {
            let val = self.bytes_to_u64(input);
            if val > max {
                max = val;
            }
        }
        Ok(max.to_le_bytes().to_vec())
    }

    fn compute_min(&self, inputs: &[Vec<u8>]) -> AppResult<Vec<u8>> {
        if inputs.is_empty() {
            return Ok(vec![0u8; 8]);
        }
        let mut min: u64 = u64::MAX;
        for input in inputs {
            let val = self.bytes_to_u64(input);
            if val < min {
                min = val;
            }
        }
        Ok(min.to_le_bytes().to_vec())
    }

    fn compute_count(&self, inputs: &[Vec<u8>]) -> AppResult<Vec<u8>> {
        Ok((inputs.len() as u64).to_le_bytes().to_vec())
    }

    fn compute_arbitrary(&self, inputs: &[Vec<u8>], _computation: &str) -> AppResult<Vec<u8>> {
        let mut result = Vec::new();
        for input in inputs {
            result.extend_from_slice(input);
        }
        Ok(CryptoService::sha256_hash(&result))
    }

    fn bytes_to_u64(&self, bytes: &[u8]) -> u64 {
        let mut arr = [0u8; 8];
        for (i, &b) in bytes.iter().take(8).enumerate() {
            arr[i] = b;
        }
        u64::from_le_bytes(arr)
    }

    fn encrypt_result_for_participants(
        &self,
        result: &[u8],
        session: &MPCSession,
    ) -> AppResult<Vec<u8>> {
        let shared_keys = self.shared_keys.lock();
        let mut combined = result.to_vec();

        for participant in &session.participants {
            if let Some(key) = shared_keys.get(participant) {
                let encrypted = CryptoService::aes_encrypt(key, result)?;
                combined.extend_from_slice(&encrypted);
            }
        }

        Ok(combined)
    }

    fn verify_input_commitment(&self, submission: &InputSubmission) -> AppResult<()> {
        let mut data = Vec::new();
        data.extend_from_slice(&submission.encrypted_input);
        data.extend_from_slice(submission.nonce.as_bytes());
        data.extend_from_slice(submission.session_id.as_bytes());
        data.extend_from_slice(submission.participant_id.as_bytes());

        let expected_commitment = CryptoService::sha256_hex(&data);

        if expected_commitment != submission.commitment {
            return Err(AppError::ValidationError(
                "Input commitment verification failed".into(),
            ));
        }

        Ok(())
    }

    pub async fn create_garbled_circuit(
        &self,
        gate_count: u32,
        input_count: u32,
        output_count: u32,
    ) -> AppResult<GarbledCircuit> {
        let circuit_id = format!("gc_{}", Uuid::new_v4().simple());
        let encrypted_gates = CryptoService::random_bytes((gate_count * 32) as usize);

        let mut labels = HashMap::new();
        for i in 0..input_count {
            let mut input_labels = HashMap::new();
            input_labels.insert("0".to_string(), CryptoService::random_bytes(16));
            input_labels.insert("1".to_string(), CryptoService::random_bytes(16));
            labels.insert(format!("input_{}", i), input_labels);
        }

        let circuit = GarbledCircuit {
            circuit_id: circuit_id.clone(),
            gate_count,
            input_count,
            output_count,
            encrypted_gates,
            labels,
        };

        self.circuits.lock().insert(circuit_id, circuit.clone());

        Ok(circuit)
    }

    pub async fn create_oblivious_transfer(
        &self,
        sender: String,
        receiver: String,
        messages: Vec<(Vec<u8>, Vec<u8>)>,
    ) -> AppResult<ObliviousTransfer> {
        let ot = ObliviousTransfer {
            transfer_id: format!("ot_{}", Uuid::new_v4().simple()),
            sender,
            receiver,
            choices: Vec::new(),
            messages,
            status: OTStatus::Initiated,
        };

        self.ots.lock().insert(ot.transfer_id.clone(), ot.clone());

        Ok(ot)
    }

    pub async fn get_session(&self, session_id: &str) -> AppResult<MPCSession> {
        let sessions = self.sessions.lock();
        sessions
            .get(session_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Session {} not found", session_id)))
    }

    pub async fn list_sessions(&self) -> AppResult<Vec<MPCSession>> {
        let sessions = self.sessions.lock();
        Ok(sessions.values().cloned().collect())
    }

    pub fn create_run_instance(&self, session_id: &str) -> RunInstance {
        let mut instance = RunInstance::new(session_id.to_string());
        instance.set_metadata("module", "mpc");
        instance
    }
}
