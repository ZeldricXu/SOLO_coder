use crate::error::PlatformError;
use crate::types::{FederatedTrainingTask, GradientUpdate, TrainingStatus};
use crate::utils::{current_timestamp, hash_bytes, verify_hmac_sha256};
use async_trait::async_trait;
use chrono::Utc;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;
use tokio::sync::mpsc;
use tracing::{info, warn, error, debug};
use uuid::Uuid;

#[async_trait]
pub trait ParticipantCommunicator: Send + Sync {
    async fn send_model(&self, participant_id: &str, model_bytes: &[u8]) -> Result<(), PlatformError>;
    async fn receive_gradient(&self, participant_id: &str) -> Result<GradientUpdate, PlatformError>;
    fn name(&self) -> &str;
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GlobalModel {
    pub model_id: String,
    pub version: u32,
    pub weights: Vec<f64>,
    pub last_updated: chrono::DateTime<chrono::Utc>,
    pub metadata: HashMap<String, serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrainingConfiguration {
    pub task_id: String,
    pub model_id: String,
    pub total_rounds: u32,
    pub min_participants: usize,
    pub max_participants: usize,
    pub aggregation_strategy: AggregationStrategy,
    pub timeout_seconds: u64,
    pub encryption_enabled: bool,
    pub differential_privacy: DifferentialPrivacyConfig,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AggregationStrategy {
    FedAvg,
    FedProx,
    Scaffold,
    FedAdam,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DifferentialPrivacyConfig {
    pub enabled: bool,
    pub epsilon: f64,
    pub delta: f64,
    pub noise_scale: f64,
}

impl Default for DifferentialPrivacyConfig {
    fn default() -> Self {
        DifferentialPrivacyConfig {
            enabled: false,
            epsilon: 1.0,
            delta: 1e-5,
            noise_scale: 0.1,
        }
    }
}

struct TrainingRoundState {
    received_gradients: HashMap<String, GradientUpdate>,
    expected_participants: Vec<String>,
    started_at: Instant,
    round_number: u32,
}

struct CoordinatorState {
    tasks: HashMap<String, FederatedTrainingTask>,
    global_models: HashMap<String, GlobalModel>,
    configurations: HashMap<String, TrainingConfiguration>,
    round_states: HashMap<String, TrainingRoundState>,
    communicators: HashMap<String, Arc<dyn ParticipantCommunicator>>,
    verification_keys: HashMap<String, Vec<u8>>,
    task_count: u64,
}

pub struct FederatedLearningCoordinator {
    state: Arc<RwLock<CoordinatorState>>,
    task_tx: Option<mpsc::UnboundedSender<String>>,
}

impl FederatedLearningCoordinator {
    pub fn new() -> Self {
        FederatedLearningCoordinator {
            state: Arc::new(RwLock::new(CoordinatorState {
                tasks: HashMap::new(),
                global_models: HashMap::new(),
                configurations: HashMap::new(),
                round_states: HashMap::new(),
                communicators: HashMap::new(),
                verification_keys: HashMap::new(),
                task_count: 0,
            })),
            task_tx: None,
        }
    }

    pub fn register_communicator(&self, name: &str, communicator: Arc<dyn ParticipantCommunicator>) {
        let mut state = self.state.write();
        state.communicators.insert(name.to_string(), communicator);
        info!(name = %name, "Participant communicator registered");
    }

    pub fn register_verification_key(&self, participant_id: &str, key: &[u8]) {
        let mut state = self.state.write();
        state.verification_keys.insert(participant_id.to_string(), key.to_vec());
    }

    pub async fn create_training_task(
        &self,
        model_id: &str,
        participants: Vec<String>,
        config: TrainingConfiguration,
    ) -> Result<FederatedTrainingTask, PlatformError> {
        if participants.is_empty() {
            return Err(PlatformError::Validation("At least one participant required".to_string()));
        }
        
        if participants.len() < config.min_participants {
            return Err(PlatformError::Validation(format!(
                "Not enough participants: need {}, have {}",
                config.min_participants,
                participants.len()
            )));
        }
        
        if participants.len() > config.max_participants {
            return Err(PlatformError::Validation(format!(
                "Too many participants: max {}, have {}",
                config.max_participants,
                participants.len()
            )));
        }
        
        let task_id = format!("fl_task_{}", Uuid::new_v4().simple());
        
        let task = FederatedTrainingTask {
            task_id: task_id.clone(),
            model_id: model_id.to_string(),
            round: 0,
            status: TrainingStatus::Pending,
            participants: participants.clone(),
            created_at: current_timestamp(),
        };
        
        {
            let mut state = self.state.write();
            
            state.configurations.insert(task_id.clone(), config.clone());
            state.tasks.insert(task_id.clone(), task.clone());
            state.task_count += 1;
            
            if !state.global_models.contains_key(model_id) {
                let initial_model = GlobalModel {
                    model_id: model_id.to_string(),
                    version: 0,
                    weights: Vec::new(),
                    last_updated: current_timestamp(),
                    metadata: HashMap::new(),
                };
                state.global_models.insert(model_id.to_string(), initial_model);
            }
        }
        
        info!(
            task_id = %task_id,
            model_id = %model_id,
            participant_count = participants.len(),
            "Federated training task created"
        );
        
        Ok(task)
    }

    pub async fn start_training(&self, task_id: &str) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        
        let task = state.tasks.get_mut(task_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Task {} not found", task_id)))?;
        
        if task.status != TrainingStatus::Pending {
            return Err(PlatformError::Validation(format!(
                "Cannot start task in status {:?}",
                task.status
            )));
        }
        
        task.status = TrainingStatus::Distributing;
        
        let config = state.configurations.get(task_id).cloned()
            .ok_or_else(|| PlatformError::NotFound(format!("Configuration for task {} not found", task_id)))?;
        
        let model = state.global_models.get(&task.model_id).cloned()
            .ok_or_else(|| PlatformError::NotFound(format!("Model {} not found", task.model_id)))?;
        
        let round_state = TrainingRoundState {
            received_gradients: HashMap::new(),
            expected_participants: task.participants.clone(),
            started_at: Instant::now(),
            round_number: task.round + 1,
        };
        
        state.round_states.insert(task_id.to_string(), round_state);
        
        drop(state);
        
        let model_bytes = serde_json::to_vec(&model)
            .map_err(|e| PlatformError::Internal(format!("Failed to serialize model: {}", e)))?;
        
        for participant_id in &config.min_participants {
            let communicator = self.get_default_communicator().await;
            if let Some(comm) = communicator {
                if let Err(e) = comm.send_model(&participant_id.to_string(), &model_bytes).await {
                    warn!(
                        participant_id = participant_id,
                        error = %e,
                        "Failed to distribute model to participant"
                    );
                }
            }
        }
        
        let mut state = self.state.write();
        if let Some(task) = state.tasks.get_mut(task_id) {
            task.status = TrainingStatus::Training;
            task.round += 1;
        }
        
        info!(task_id = %task_id, round = 1, "Training round started");
        
        Ok(())
    }

    async fn get_default_communicator(&self) -> Option<Arc<dyn ParticipantCommunicator>> {
        let state = self.state.read();
        state.communicators.values().next().cloned()
    }

    pub async fn submit_gradient(&self, update: GradientUpdate) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        
        let task = state.tasks.get(&update.task_id).cloned()
            .ok_or_else(|| PlatformError::NotFound(format!("Task {} not found", update.task_id)))?;
        
        if task.status != TrainingStatus::Training {
            return Err(PlatformError::Validation(format!(
                "Cannot submit gradient to task in status {:?}",
                task.status
            )));
        }
        
        let round_state = state.round_states.get_mut(&update.task_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Round state for task {} not found", update.task_id)))?;
        
        if !round_state.expected_participants.contains(&update.participant_id) {
            return Err(PlatformError::Validation(format!(
                "Participant {} not expected for this round",
                update.participant_id
            )));
        }
        
        if update.round != round_state.round_number {
            return Err(PlatformError::Validation(format!(
                "Gradient round mismatch: expected {}, got {}",
                round_state.round_number,
                update.round
            )));
        }
        
        let is_valid = {
            let state = self.state.read();
            if let Some(key) = state.verification_keys.get(&update.participant_id) {
                let mut data_to_verify = Vec::new();
                data_to_verify.extend_from_slice(update.participant_id.as_bytes());
                data_to_verify.extend_from_slice(update.task_id.as_bytes());
                data_to_verify.extend_from_slice(&update.round.to_be_bytes());
                data_to_verify.extend_from_slice(&update.encrypted_gradient);
                
                verify_hmac_sha256(key, &data_to_verify, &update.signature)
            } else {
                true
            }
        };
        
        if !is_valid {
            return Err(PlatformError::Authentication(
                "Gradient signature verification failed".to_string()
            ));
        }
        
        if round_state.received_gradients.contains_key(&update.participant_id) {
            return Err(PlatformError::Conflict(format!(
                "Gradient already submitted by participant {}",
                update.participant_id
            )));
        }
        
        round_state.received_gradients.insert(
            update.participant_id.clone(),
            update.clone(),
        );
        
        info!(
            task_id = %update.task_id,
            participant_id = %update.participant_id,
            round = update.round,
            "Gradient received"
        );
        
        Ok(())
    }

    pub async fn aggregate_gradients(&self, task_id: &str) -> Result<GlobalModel, PlatformError> {
        let mut state = self.state.write();
        
        let config = state.configurations.get(task_id).cloned()
            .ok_or_else(|| PlatformError::NotFound(format!("Configuration for task {} not found", task_id)))?;
        
        let task = state.tasks.get_mut(task_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Task {} not found", task_id)))?;
        
        let round_state = state.round_states.get(task_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Round state for task {} not found", task_id)))?;
        
        let received_count = round_state.received_gradients.len();
        if received_count < config.min_participants {
            return Err(PlatformError::Validation(format!(
                "Not enough gradients received: need {}, have {}",
                config.min_participants,
                received_count
            )));
        }
        
        task.status = TrainingStatus::Aggregating;
        
        drop(state);
        
        let aggregated_weights = self.perform_aggregation(task_id, &config.aggregation_strategy).await?;
        
        let mut state = self.state.write();
        
        let model = state.global_models.get_mut(&task.model_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Model {} not found", task.model_id)))?;
        
        model.weights = aggregated_weights;
        model.version += 1;
        model.last_updated = current_timestamp();
        
        let updated_model = model.clone();
        
        if let Some(task) = state.tasks.get_mut(task_id) {
            if task.round >= config.total_rounds {
                task.status = TrainingStatus::Completed;
            } else {
                task.status = TrainingStatus::Training;
            }
        }
        
        state.round_states.remove(task_id);
        
        info!(
            task_id = %task_id,
            model_version = updated_model.version,
            "Gradient aggregation completed"
        );
        
        Ok(updated_model)
    }

    async fn perform_aggregation(
        &self,
        task_id: &str,
        strategy: &AggregationStrategy,
    ) -> Result<Vec<f64>, PlatformError> {
        let state = self.state.read();
        
        let round_state = state.round_states.get(task_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Round state for task {} not found", task_id)))?;
        
        let gradients: Vec<Vec<f64>> = round_state.received_gradients.values()
            .map(|update| {
                serde_json::from_slice::<Vec<f64>>(&update.encrypted_gradient)
                    .unwrap_or_default()
            })
            .collect();
        
        if gradients.is_empty() {
            return Ok(Vec::new());
        }
        
        let weight_count = gradients[0].len();
        let participant_count = gradients.len() as f64;
        
        let aggregated = match strategy {
            AggregationStrategy::FedAvg => {
                let mut result = vec![0.0; weight_count];
                for grad in &gradients {
                    for i in 0..weight_count {
                        result[i] += grad[i];
                    }
                }
                for i in 0..weight_count {
                    result[i] /= participant_count;
                }
                result
            }
            AggregationStrategy::FedProx => {
                let mut result = vec![0.0; weight_count];
                for grad in &gradients {
                    for i in 0..weight_count {
                        result[i] += grad[i];
                    }
                }
                for i in 0..weight_count {
                    result[i] /= participant_count;
                }
                result
            }
            AggregationStrategy::Scaffold => {
                let mut result = vec![0.0; weight_count];
                for grad in &gradients {
                    for i in 0..weight_count {
                        result[i] += grad[i];
                    }
                }
                for i in 0..weight_count {
                    result[i] /= participant_count;
                }
                result
            }
            AggregationStrategy::FedAdam => {
                let mut result = vec![0.0; weight_count];
                for grad in &gradients {
                    for i in 0..weight_count {
                        result[i] += grad[i];
                    }
                }
                for i in 0..weight_count {
                    result[i] /= participant_count;
                }
                result
            }
        };
        
        Ok(aggregated)
    }

    pub fn get_task_status(&self, task_id: &str) -> Result<FederatedTrainingTask, PlatformError> {
        let state = self.state.read();
        
        state.tasks.get(task_id).cloned()
            .ok_or_else(|| PlatformError::NotFound(format!("Task {} not found", task_id)))
    }

    pub fn get_global_model(&self, model_id: &str) -> Result<GlobalModel, PlatformError> {
        let state = self.state.read();
        
        state.global_models.get(model_id).cloned()
            .ok_or_else(|| PlatformError::NotFound(format!("Model {} not found", model_id)))
    }

    pub fn list_tasks(&self, status: Option<TrainingStatus>) -> Vec<FederatedTrainingTask> {
        let state = self.state.read();
        
        state.tasks.values()
            .filter(|t| status.map(|s| t.status == s).unwrap_or(true))
            .cloned()
            .collect()
    }

    pub fn get_training_progress(&self, task_id: &str) -> Result<(u32, u32, usize, usize), PlatformError> {
        let state = self.state.read();
        
        let task = state.tasks.get(task_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Task {} not found", task_id)))?;
        
        let config = state.configurations.get(task_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Configuration for task {} not found", task_id)))?;
        
        let round_state = state.round_states.get(task_id);
        
        let received = round_state.map(|r| r.received_gradients.len()).unwrap_or(0);
        let expected = round_state.map(|r| r.expected_participants.len()).unwrap_or(0);
        
        Ok((task.round, config.total_rounds, received, expected))
    }

    pub async fn cancel_training(&self, task_id: &str) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        
        let task = state.tasks.get_mut(task_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Task {} not found", task_id)))?;
        
        if task.status == TrainingStatus::Completed || task.status == TrainingStatus::Failed {
            return Err(PlatformError::Validation(
                "Cannot cancel a completed or failed task".to_string()
            ));
        }
        
        task.status = TrainingStatus::Failed;
        state.round_states.remove(task_id);
        
        warn!(task_id = %task_id, "Federated training task cancelled");
        
        Ok(())
    }

    pub fn set_global_model(&self, model: GlobalModel) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        state.global_models.insert(model.model_id.clone(), model);
        Ok(())
    }

    pub fn get_participant_count(&self, task_id: &str) -> Result<usize, PlatformError> {
        let state = self.state.read();
        
        let task = state.tasks.get(task_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Task {} not found", task_id)))?;
        
        Ok(task.participants.len())
    }

    pub fn get_statistics(&self) -> HashMap<String, u64> {
        let state = self.state.read();
        
        let mut stats = HashMap::new();
        stats.insert("total_tasks".to_string(), state.task_count);
        stats.insert("active_tasks".to_string(), state.tasks.len() as u64);
        stats.insert("global_models".to_string(), state.global_models.len() as u64);
        stats.insert("active_rounds".to_string(), state.round_states.len() as u64);
        
        stats
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_create_task() {
        let coordinator = FederatedLearningCoordinator::new();
        
        let config = TrainingConfiguration {
            task_id: "".to_string(),
            model_id: "model_001".to_string(),
            total_rounds: 10,
            min_participants: 2,
            max_participants: 10,
            aggregation_strategy: AggregationStrategy::FedAvg,
            timeout_seconds: 300,
            encryption_enabled: false,
            differential_privacy: DifferentialPrivacyConfig::default(),
        };
        
        let participants = vec!["node_001".to_string(), "node_002".to_string()];
        
        let task = coordinator.create_training_task(
            "model_001",
            participants,
            config,
        ).await.unwrap();
        
        assert_eq!(task.model_id, "model_001");
        assert_eq!(task.status, TrainingStatus::Pending);
        assert_eq!(task.participants.len(), 2);
    }

    #[tokio::test]
    async fn test_insufficient_participants() {
        let coordinator = FederatedLearningCoordinator::new();
        
        let config = TrainingConfiguration {
            task_id: "".to_string(),
            model_id: "model_001".to_string(),
            total_rounds: 10,
            min_participants: 5,
            max_participants: 10,
            aggregation_strategy: AggregationStrategy::FedAvg,
            timeout_seconds: 300,
            encryption_enabled: false,
            differential_privacy: DifferentialPrivacyConfig::default(),
        };
        
        let participants = vec!["node_001".to_string()];
        
        let result = coordinator.create_training_task(
            "model_001",
            participants,
            config,
        ).await;
        
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_get_training_progress() {
        let coordinator = FederatedLearningCoordinator::new();
        
        let config = TrainingConfiguration {
            task_id: "".to_string(),
            model_id: "model_001".to_string(),
            total_rounds: 10,
            min_participants: 2,
            max_participants: 10,
            aggregation_strategy: AggregationStrategy::FedAvg,
            timeout_seconds: 300,
            encryption_enabled: false,
            differential_privacy: DifferentialPrivacyConfig::default(),
        };
        
        let participants = vec!["node_001".to_string(), "node_002".to_string()];
        
        let task = coordinator.create_training_task(
            "model_001",
            participants,
            config,
        ).await.unwrap();
        
        let (current_round, total_rounds, received, expected) = 
            coordinator.get_training_progress(&task.task_id).await.unwrap();
        
        assert_eq!(current_round, 0);
        assert_eq!(total_rounds, 10);
    }
}
