use crate::config::FederatedConfig;
use crate::models::AppError;
use crate::utils::{current_datetime, generate_id, sha256_hex};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TrainingStatus {
    Created,
    WaitingForClients,
    Distributing,
    Training,
    Aggregating,
    UpdatingModel,
    Completed,
    Failed,
    Timeout,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum AggregationStrategy {
    FedAvg,
    FedProx,
    Scaffold,
    FedAdam,
    FedAdagrad,
    SecureAggregation,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ClientStatus {
    Idle,
    Selected,
    Training,
    Done,
    Failed,
    Dropped,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrainingTask {
    pub task_id: String,
    pub name: String,
    pub model_id: String,
    pub hyperparameters: serde_json::Value,
    pub status: TrainingStatus,
    pub current_round: u32,
    pub total_rounds: u32,
    pub min_clients: usize,
    pub max_clients: usize,
    pub aggregation_strategy: AggregationStrategy,
    pub created_at: DateTime<Utc>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub global_model_version: u64,
    pub metadata: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FederatedClient {
    pub client_id: String,
    pub name: String,
    pub status: ClientStatus,
    pub public_key: Option<Vec<u8>>,
    pub data_size: u64,
    pub computational_power: f64,
    pub last_seen: DateTime<Utc>,
    pub joined_tasks: HashSet<String>,
    pub location: Option<String>,
    pub capabilities: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelUpdate {
    pub update_id: String,
    pub task_id: String,
    pub client_id: String,
    pub round: u32,
    pub encrypted_gradients: Vec<u8>,
    pub encrypted_weights: Vec<u8>,
    pub sample_count: u64,
    pub loss: f64,
    pub accuracy: Option<f64>,
    pub timestamp: DateTime<Utc>,
    pub signature: Option<String>,
    pub nonce: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GlobalModel {
    pub model_id: String,
    pub version: u64,
    pub weights: Vec<f64>,
    pub gradients: Vec<f64>,
    pub last_updated: DateTime<Utc>,
    pub created_at: DateTime<Utc>,
    pub metadata: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskCreateRequest {
    pub name: String,
    pub model_id: String,
    pub hyperparameters: serde_json::Value,
    pub total_rounds: u32,
    pub min_clients: Option<usize>,
    pub max_clients: Option<usize>,
    pub aggregation_strategy: AggregationStrategy,
    pub metadata: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClientRegisterRequest {
    pub client_id: String,
    pub name: String,
    pub public_key: Option<Vec<u8>>,
    pub data_size: u64,
    pub computational_power: f64,
    pub location: Option<String>,
    pub capabilities: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GradientSubmission {
    pub task_id: String,
    pub client_id: String,
    pub round: u32,
    pub encrypted_gradients: Vec<u8>,
    pub encrypted_weights: Vec<u8>,
    pub sample_count: u64,
    pub loss: f64,
    pub accuracy: Option<f64>,
    pub signature: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregationResult {
    pub task_id: String,
    pub round: u32,
    pub aggregated_weights: Vec<f64>,
    pub aggregated_gradients: Vec<f64>,
    pub clients_used: Vec<String>,
    pub total_samples: u64,
    pub average_loss: f64,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrainingRoundSummary {
    pub task_id: String,
    pub round: u32,
    pub clients_participated: usize,
    pub clients_failed: usize,
    pub samples_total: u64,
    pub average_loss: f64,
    pub model_version: u64,
    pub completed_at: DateTime<Utc>,
}

pub struct FederatedLearningCoordinator {
    config: FederatedConfig,
    tasks: Arc<DashMap<String, TrainingTask>>,
    clients: Arc<DashMap<String, FederatedClient>>,
    models: Arc<DashMap<String, GlobalModel>>,
    updates: Arc<DashMap<String, Vec<ModelUpdate>>>,
    round_summaries: Arc<DashMap<String, Vec<TrainingRoundSummary>>>,
    secret_shares: Arc<DashMap<String, HashMap<String, Vec<u8>>>>,
    task_lock: Arc<Mutex<()>>,
}

impl FederatedLearningCoordinator {
    pub fn new(config: FederatedConfig) -> Self {
        Self {
            config,
            tasks: Arc::new(DashMap::new()),
            clients: Arc::new(DashMap::new()),
            models: Arc::new(DashMap::new()),
            updates: Arc::new(DashMap::new()),
            round_summaries: Arc::new(DashMap::new()),
            secret_shares: Arc::new(DashMap::new()),
            task_lock: Arc::new(Mutex::new(())),
        }
    }

    pub fn create_task(
        &self,
        request: TaskCreateRequest,
    ) -> Result<TrainingTask, AppError> {
        if request.total_rounds == 0 {
            return Err(AppError::Validation(
                "Total rounds must be greater than zero".to_string(),
            ));
        }

        if request.total_rounds > self.config.aggregation_rounds * 10 {
            return Err(AppError::Validation(format!(
                "Total rounds exceeds maximum of {}",
                self.config.aggregation_rounds * 10
            )));
        }

        let min_clients = request
            .min_clients
            .unwrap_or(self.config.min_clients_per_round);
        let max_clients = request
            .max_clients
            .unwrap_or(self.config.max_clients);

        if min_clients < 1 {
            return Err(AppError::Validation(
                "Minimum clients must be at least 1".to_string(),
            ));
        }

        if min_clients > max_clients {
            return Err(AppError::Validation(
                "Minimum clients cannot exceed maximum".to_string(),
            ));
        }

        let task = TrainingTask {
            task_id: generate_id("flt"),
            name: request.name,
            model_id: request.model_id,
            hyperparameters: request.hyperparameters,
            status: TrainingStatus::Created,
            current_round: 0,
            total_rounds: request.total_rounds,
            min_clients,
            max_clients,
            aggregation_strategy: request.aggregation_strategy,
            created_at: current_datetime(),
            started_at: None,
            completed_at: None,
            global_model_version: 0,
            metadata: request.metadata,
        };

        self.tasks.insert(task.task_id.clone(), task.clone());
        self.updates.insert(task.task_id.clone(), Vec::new());
        self.round_summaries.insert(task.task_id.clone(), Vec::new());

        Ok(task)
    }

    pub fn register_client(
        &self,
        request: ClientRegisterRequest,
    ) -> Result<FederatedClient, AppError> {
        if self.clients.len() >= self.config.max_clients {
            return Err(AppError::Validation(
                "Maximum client limit reached".to_string(),
            ));
        }

        if self.clients.contains_key(&request.client_id) {
            return Err(AppError::Validation(format!(
                "Client already registered: {}",
                request.client_id
            )));
        }

        let client = FederatedClient {
            client_id: request.client_id.clone(),
            name: request.name,
            status: ClientStatus::Idle,
            public_key: request.public_key,
            data_size: request.data_size,
            computational_power: request.computational_power,
            last_seen: current_datetime(),
            joined_tasks: HashSet::new(),
            location: request.location,
            capabilities: request.capabilities,
        };

        self.clients.insert(request.client_id, client.clone());
        Ok(client)
    }

    pub fn get_client(&self, client_id: &str) -> Option<FederatedClient> {
        self.clients.get(client_id).map(|c| c.clone())
    }

    pub fn get_task(&self, task_id: &str) -> Option<TrainingTask> {
        self.tasks.get(task_id).map(|t| t.clone())
    }

    pub fn list_clients(&self) -> Vec<FederatedClient> {
        self.clients.iter().map(|c| c.clone()).collect()
    }

    pub fn list_tasks(&self) -> Vec<TrainingTask> {
        self.tasks.iter().map(|t| t.clone()).collect()
    }

    pub fn select_clients_for_task(&self, task_id: &str) -> Result<Vec<String>, AppError> {
        let mut task = self
            .tasks
            .get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("Task not found: {}", task_id)))?;

        if task.status != TrainingStatus::Created
            && task.status != TrainingStatus::WaitingForClients
        {
            return Err(AppError::Validation(format!(
                "Task is not in client selection phase: {:?}",
                task.status
            )));
        }

        let idle_clients: Vec<FederatedClient> = self
            .clients
            .iter()
            .filter(|c| c.status == ClientStatus::Idle)
            .map(|c| c.clone())
            .collect();

        if idle_clients.len() < task.min_clients {
            return Err(AppError::Validation(format!(
                "Not enough idle clients. Need {}, have {}",
                task.min_clients,
                idle_clients.len()
            )));
        }

        let selected_count = task.max_clients.min(idle_clients.len());
        let selected_clients: Vec<String> = idle_clients
            .into_iter()
            .take(selected_count)
            .map(|c| c.client_id)
            .collect();

        for client_id in &selected_clients {
            if let Some(mut client) = self.clients.get_mut(client_id) {
                client.status = ClientStatus::Selected;
                client.joined_tasks.insert(task_id.to_string());
            }
        }

        task.status = TrainingStatus::WaitingForClients;
        task.started_at = Some(current_datetime());

        Ok(selected_clients)
    }

    pub fn submit_gradient(
        &self,
        submission: GradientSubmission,
    ) -> Result<ModelUpdate, AppError> {
        let task = self
            .tasks
            .get(&submission.task_id)
            .ok_or_else(|| {
                AppError::NotFound(format!("Task not found: {}", submission.task_id))
            })?;

        if task.status != TrainingStatus::Training {
            return Err(AppError::Validation(format!(
                "Task is not in training phase: {:?}",
                task.status
            )));
        }

        if submission.round != task.current_round {
            return Err(AppError::Validation(format!(
                "Round mismatch. Expected {}, got {}",
                task.current_round, submission.round
            )));
        }

        let client = self
            .clients
            .get(&submission.client_id)
            .ok_or_else(|| {
                AppError::NotFound(format!(
                    "Client not found: {}",
                    submission.client_id
                ))
            })?;

        if client.status != ClientStatus::Selected
            && client.status != ClientStatus::Training
        {
            return Err(AppError::Validation(format!(
                "Client is not in valid state for submission: {:?}",
                client.status
            )));
        }

        let update = ModelUpdate {
            update_id: generate_id("upd"),
            task_id: submission.task_id.clone(),
            client_id: submission.client_id.clone(),
            round: submission.round,
            encrypted_gradients: submission.encrypted_gradients,
            encrypted_weights: submission.encrypted_weights,
            sample_count: submission.sample_count,
            loss: submission.loss,
            accuracy: submission.accuracy,
            timestamp: current_datetime(),
            signature: submission.signature,
            nonce: generate_id("nonce"),
        };

        {
            let mut updates = self.updates.get_mut(&submission.task_id).unwrap();
            updates.push(update.clone());
        }

        if let Some(mut client) = self.clients.get_mut(&submission.client_id) {
            client.status = ClientStatus::Done;
        }

        Ok(update)
    }

    pub fn aggregate_gradients(&self, task_id: &str) -> Result<AggregationResult, AppError> {
        let _lock = self.task_lock.lock().unwrap();

        let mut task = self
            .tasks
            .get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("Task not found: {}", task_id)))?;

        if task.status != TrainingStatus::Training {
            return Err(AppError::Validation(format!(
                "Task is not in training phase: {:?}",
                task.status
            )));
        }

        let updates = self
            .updates
            .get(task_id)
            .ok_or_else(|| AppError::Internal("Updates not found for task".to_string()))?;

        let round_updates: Vec<ModelUpdate> = updates
            .iter()
            .filter(|u| u.round == task.current_round)
            .cloned()
            .collect();

        if round_updates.len() < task.min_clients {
            return Err(AppError::Validation(format!(
                "Not enough updates for aggregation. Need {}, have {}",
                task.min_clients,
                round_updates.len()
            )));
        }

        let decrypted_gradients: Vec<Vec<f64>> = round_updates
            .iter()
            .map(|u| self.decrypt_gradients(&u.encrypted_gradients))
            .collect();

        let decrypted_weights: Vec<Vec<f64>> = round_updates
            .iter()
            .map(|u| self.decrypt_weights(&u.encrypted_weights))
            .collect();

        let total_samples: u64 = round_updates.iter().map(|u| u.sample_count).sum();
        let average_loss: f64 =
            round_updates.iter().map(|u| u.loss).sum::<f64>() / round_updates.len() as f64;

        let (aggregated_weights, aggregated_gradients) = match task.aggregation_strategy {
            AggregationStrategy::FedAvg => self.fedavg_aggregation(
                &decrypted_weights,
                &decrypted_gradients,
                &round_updates,
                total_samples,
            ),
            AggregationStrategy::FedProx => self.fedprox_aggregation(
                &decrypted_weights,
                &decrypted_gradients,
                &round_updates,
            ),
            AggregationStrategy::SecureAggregation => self.secure_aggregation(
                &decrypted_weights,
                &decrypted_gradients,
                &round_updates,
                total_samples,
            ),
            _ => self.fedavg_aggregation(
                &decrypted_weights,
                &decrypted_gradients,
                &round_updates,
                total_samples,
            ),
        };

        let clients_used: Vec<String> = round_updates.iter().map(|u| u.client_id.clone()).collect();

        task.status = TrainingStatus::Aggregating;

        Ok(AggregationResult {
            task_id: task_id.to_string(),
            round: task.current_round,
            aggregated_weights,
            aggregated_gradients,
            clients_used,
            total_samples,
            average_loss,
            timestamp: current_datetime(),
        })
    }

    fn fedavg_aggregation(
        &self,
        weights: &[Vec<f64>],
        gradients: &[Vec<f64>],
        updates: &[ModelUpdate],
        total_samples: u64,
    ) -> (Vec<f64>, Vec<f64>) {
        if weights.is_empty() {
            return (Vec::new(), Vec::new());
        }

        let len = weights[0].len();
        let mut agg_weights = vec![0.0; len];
        let mut agg_gradients = vec![0.0; len];

        for (i, w_vec) in weights.iter().enumerate() {
            let sample_weight = updates[i].sample_count as f64 / total_samples.max(1) as f64;
            
            for (j, w) in w_vec.iter().enumerate() {
                agg_weights[j] += w * sample_weight;
            }

            if let Some(g_vec) = gradients.get(i) {
                for (j, g) in g_vec.iter().enumerate() {
                    if j < agg_gradients.len() {
                        agg_gradients[j] += g * sample_weight;
                    }
                }
            }
        }

        (agg_weights, agg_gradients)
    }

    fn fedprox_aggregation(
        &self,
        weights: &[Vec<f64>],
        gradients: &[Vec<f64>],
        updates: &[ModelUpdate],
    ) -> (Vec<f64>, Vec<f64>) {
        let mu = 0.1;
        
        if weights.is_empty() {
            return (Vec::new(), Vec::new());
        }

        let len = weights[0].len();
        let total_samples: u64 = updates.iter().map(|u| u.sample_count).sum();
        let mut agg_weights = vec![0.0; len];
        let mut agg_gradients = vec![0.0; len];

        for (i, w_vec) in weights.iter().enumerate() {
            let sample_weight = updates[i].sample_count as f64 / total_samples.max(1) as f64;
            
            for (j, w) in w_vec.iter().enumerate() {
                let proximal_term = mu * w;
                agg_weights[j] += (w + proximal_term) * sample_weight;
            }

            if let Some(g_vec) = gradients.get(i) {
                for (j, g) in g_vec.iter().enumerate() {
                    if j < agg_gradients.len() {
                        agg_gradients[j] += g * sample_weight;
                    }
                }
            }
        }

        (agg_weights, agg_gradients)
    }

    fn secure_aggregation(
        &self,
        weights: &[Vec<f64>],
        gradients: &[Vec<f64>],
        updates: &[ModelUpdate],
        total_samples: u64,
    ) -> (Vec<f64>, Vec<f64>) {
        self.fedavg_aggregation(weights, gradients, updates, total_samples)
    }

    pub fn update_global_model(
        &self,
        task_id: &str,
        aggregation: &AggregationResult,
    ) -> Result<GlobalModel, AppError> {
        let mut task = self
            .tasks
            .get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("Task not found: {}", task_id)))?;

        let model_id = task.model_id.clone();
        let mut model = self
            .models
            .get(&model_id)
            .map(|m| m.clone())
            .unwrap_or_else(|| GlobalModel {
                model_id: model_id.clone(),
                version: 0,
                weights: Vec::new(),
                gradients: Vec::new(),
                last_updated: current_datetime(),
                created_at: current_datetime(),
                metadata: serde_json::Value::Null,
            });

        let learning_rate: f64 = task
            .hyperparameters
            .get("learning_rate")
            .and_then(|v| v.as_f64())
            .unwrap_or(0.01);

        if model.weights.is_empty() {
            model.weights = aggregation.aggregated_weights.clone();
        } else {
            for (i, w) in model.weights.iter_mut().enumerate() {
                if let Some(g) = aggregation.aggregated_gradients.get(i) {
                    *w -= learning_rate * g;
                }
            }
        }

        model.gradients = aggregation.aggregated_gradients.clone();
        model.version += 1;
        model.last_updated = current_datetime();

        self.models.insert(model_id.clone(), model.clone());

        task.global_model_version = model.version;
        task.status = TrainingStatus::UpdatingModel;

        let summary = TrainingRoundSummary {
            task_id: task_id.to_string(),
            round: task.current_round,
            clients_participated: aggregation.clients_used.len(),
            clients_failed: 0,
            samples_total: aggregation.total_samples,
            average_loss: aggregation.average_loss,
            model_version: model.version,
            completed_at: current_datetime(),
        };

        {
            let mut summaries = self.round_summaries.get_mut(task_id).unwrap();
            summaries.push(summary);
        }

        task.current_round += 1;

        if task.current_round >= task.total_rounds {
            task.status = TrainingStatus::Completed;
            task.completed_at = Some(current_datetime());
        } else {
            task.status = TrainingStatus::WaitingForClients;
        }

        Ok(model)
    }

    pub fn start_round(&self, task_id: &str) -> Result<TrainingTask, AppError> {
        let mut task = self
            .tasks
            .get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("Task not found: {}", task_id)))?;

        if task.current_round >= task.total_rounds {
            return Err(AppError::Validation("Task already completed".to_string()));
        }

        task.status = TrainingStatus::Training;
        Ok(task.clone())
    }

    pub fn get_global_model(&self, model_id: &str) -> Option<GlobalModel> {
        self.models.get(model_id).map(|m| m.clone())
    }

    pub fn get_round_summaries(&self, task_id: &str) -> Vec<TrainingRoundSummary> {
        self.round_summaries
            .get(task_id)
            .map(|s| s.to_vec())
            .unwrap_or_default()
    }

    fn decrypt_gradients(&self, encrypted: &[u8]) -> Vec<f64> {
        if encrypted.len() < 8 {
            return Vec::new();
        }

        let num_floats = encrypted.len() / 8;
        let mut result = Vec::with_capacity(num_floats);

        for i in 0..num_floats {
            let start = i * 8;
            let end = start + 8;
            if end <= encrypted.len() {
                let bytes: [u8; 8] = encrypted[start..end].try_into().unwrap_or([0; 8]);
                result.push(f64::from_ne_bytes(bytes));
            }
        }

        result
    }

    fn decrypt_weights(&self, encrypted: &[u8]) -> Vec<f64> {
        self.decrypt_gradients(encrypted)
    }

    pub fn tasks_count(&self) -> usize {
        self.tasks.len()
    }

    pub fn clients_count(&self) -> usize {
        self.clients.len()
    }

    pub fn models_count(&self) -> usize {
        self.models.len()
    }

    pub fn heartbeat(&self, client_id: &str) -> Result<(), AppError> {
        let mut client = self
            .clients
            .get_mut(client_id)
            .ok_or_else(|| AppError::NotFound(format!("Client not found: {}", client_id)))?;

        client.last_seen = current_datetime();
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FederatedEvent {
    pub event_type: String,
    pub task_id: Option<String>,
    pub client_id: Option<String>,
    pub timestamp: DateTime<Utc>,
    pub details: serde_json::Value,
}

impl FederatedEvent {
    pub fn new(
        event_type: &str,
        task_id: Option<String>,
        client_id: Option<String>,
        details: serde_json::Value,
    ) -> Self {
        Self {
            event_type: event_type.to_string(),
            task_id,
            client_id,
            timestamp: current_datetime(),
            details,
        }
    }
}
