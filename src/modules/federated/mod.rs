use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;
use uuid::Uuid;

use crate::domain::run_instance::RunInstance;
use crate::infra::config::FederatedConfig;
use crate::infra::crypto::CryptoService;
use crate::infra::error::{AppError, AppResult};
use crate::infra::metrics::MetricsRegistry;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrainingTask {
    pub task_id: String,
    pub model_id: String,
    pub status: TaskStatus,
    pub participants: Vec<String>,
    pub required_participants: u32,
    pub hyperparameters: HashMap<String, serde_json::Value>,
    pub global_model: Option<Vec<u8>>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub deadline: chrono::DateTime<chrono::Utc>,
    pub round: u32,
    pub max_rounds: u32,
    pub gradients: HashMap<String, EncryptedGradient>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TaskStatus {
    Created,
    WaitingForParticipants,
    Training,
    Aggregating,
    Completed,
    Failed,
    TimedOut,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EncryptedGradient {
    pub participant_id: String,
    pub encrypted_data: Vec<u8>,
    pub signature: String,
    pub timestamp: chrono::DateTime<chrono::Utc>,
    pub encryption_key_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateTaskRequest {
    pub model_id: String,
    pub min_participants: u32,
    pub max_rounds: u32,
    pub hyperparameters: HashMap<String, serde_json::Value>,
    pub timeout_seconds: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParticipantRegistration {
    pub task_id: String,
    pub participant_id: String,
    pub public_key: String,
    pub endpoint: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GradientSubmission {
    pub task_id: String,
    pub participant_id: String,
    pub encrypted_gradient: Vec<u8>,
    pub signature: String,
    pub encryption_key_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelUpdate {
    pub task_id: String,
    pub model_version: u32,
    pub model_data: Vec<u8>,
    pub checksum: String,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct TaskPerformanceMetrics {
    pub task_id: String,
    pub total_training_time_ms: u64,
    pub registration_time_ms: u64,
    pub aggregation_time_ms: Vec<u64>,
    pub per_round_times_ms: Vec<u64>,
    pub participant_count: usize,
    pub gradient_submission_times_ms: Vec<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct FLMetricsSnapshot {
    pub total_tasks: u64,
    pub active_tasks: u64,
    pub completed_tasks: u64,
    pub failed_tasks: u64,
    pub total_participants: u64,
    pub total_gradients_submitted: u64,
    pub total_aggregations: u64,
    pub average_training_time_ms: f64,
    pub average_aggregation_time_ms: f64,
    pub average_participants_per_task: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LatencyBreakdown {
    pub registration_ms: u64,
    pub training_distribution_ms: u64,
    pub gradient_collection_ms: u64,
    pub aggregation_ms: u64,
    pub model_update_ms: u64,
    pub total_round_ms: u64,
}

pub struct FederatedLearningService {
    config: FederatedConfig,
    tasks: std::sync::Arc<parking_lot::Mutex<HashMap<String, TrainingTask>>>,
    participant_keys: std::sync::Arc<parking_lot::Mutex<HashMap<String, String>>>,
    aggregation_keys: std::sync::Arc<parking_lot::Mutex<Vec<u8>>>,
    metrics: std::sync::Arc<MetricsRegistry>,
    task_performance: std::sync::Arc<parking_lot::Mutex<HashMap<String, TaskPerformanceMetrics>>>,
    global_metrics: std::sync::Arc<parking_lot::Mutex<FLMetricsSnapshot>>,
}

impl FederatedLearningService {
    pub fn new(config: FederatedConfig) -> Self {
        let agg_key = CryptoService::generate_aes_key();
        let metrics = MetricsRegistry::new();
        
        metrics.register_counter(
            "fl_tasks_created_total",
            "Total number of federated learning tasks created",
            &["model_id"]
        );
        metrics.register_counter(
            "fl_gradients_submitted_total",
            "Total number of gradients submitted",
            &["task_id", "participant_id"]
        );
        metrics.register_counter(
            "fl_aggregations_total",
            "Total number of gradient aggregations performed",
            &["task_id"]
        );
        metrics.register_gauge(
            "fl_active_tasks",
            "Number of currently active federated learning tasks",
            &[]
        );
        metrics.register_gauge(
            "fl_registered_participants",
            "Number of registered participants per task",
            &["task_id"]
        );
        metrics.register_histogram(
            "fl_task_duration_seconds",
            "Duration of federated learning tasks",
            &["status"],
            Some(vec![1.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0])
        );
        metrics.register_histogram(
            "fl_aggregation_duration_seconds",
            "Duration of gradient aggregation operations",
            &["task_id"],
            Some(vec![0.1, 0.5, 1.0, 2.0, 5.0, 10.0])
        );
        metrics.register_histogram(
            "fl_gradient_submission_duration_seconds",
            "Duration of gradient submission operations",
            &["task_id", "participant_id"],
            Some(vec![0.01, 0.05, 0.1, 0.5, 1.0])
        );

        Self {
            config,
            tasks: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            participant_keys: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            aggregation_keys: std::sync::Arc::new(parking_lot::Mutex::new(agg_key)),
            metrics: std::sync::Arc::new(metrics),
            task_performance: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            global_metrics: std::sync::Arc::new(parking_lot::Mutex::new(FLMetricsSnapshot::default())),
        }
    }

    pub async fn create_task(&self, request: CreateTaskRequest) -> AppResult<TrainingTask> {
        let start = Instant::now();
        
        if request.min_participants < self.config.min_participants {
            return Err(AppError::ValidationError(format!(
                "Minimum participants {} is less than configured minimum {}",
                request.min_participants, self.config.min_participants
            )));
        }

        let now = chrono::Utc::now();
        let task = TrainingTask {
            task_id: format!("fl_task_{}", Uuid::new_v4().simple()),
            model_id: request.model_id,
            status: TaskStatus::Created,
            participants: Vec::new(),
            required_participants: request.min_participants,
            hyperparameters: request.hyperparameters,
            global_model: None,
            created_at: now,
            deadline: now + chrono::Duration::seconds(request.timeout_seconds as i64),
            round: 0,
            max_rounds: request.max_rounds,
            gradients: HashMap::new(),
        };

        self.tasks.lock().insert(task.task_id.clone(), task.clone());

        self.task_performance.lock().insert(
            task.task_id.clone(),
            TaskPerformanceMetrics {
                task_id: task.task_id.clone(),
                ..Default::default()
            },
        );

        self.metrics.increment_counter(
            "fl_tasks_created_total",
            &[&request.model_id]
        );
        self.metrics.set_gauge(
            "fl_active_tasks",
            &[],
            (self.tasks.lock().len() as f64)
        );
        
        let mut global = self.global_metrics.lock();
        global.total_tasks += 1;
        global.active_tasks += 1;

        let duration = start.elapsed().as_secs_f64();
        self.metrics.observe_histogram(
            "fl_task_duration_seconds",
            &["created"],
            duration
        );

        Ok(task)
    }

    pub async fn register_participant(&self, registration: ParticipantRegistration) -> AppResult<TrainingTask> {
        let start = Instant::now();
        
        let mut tasks = self.tasks.lock();
        let task = tasks
            .get_mut(&registration.task_id)
            .ok_or_else(|| AppError::NotFound(format!("Task {} not found", registration.task_id)))?;

        if task.status != TaskStatus::Created && task.status != TaskStatus::WaitingForParticipants {
            return Err(AppError::ValidationError(format!(
                "Cannot register participant in task status {:?}",
                task.status
            )));
        }

        if task.participants.len() as u32 >= self.config.max_participants {
            return Err(AppError::ResourceExhausted(
                "Maximum participants reached".into(),
            ));
        }

        if task.participants.contains(&registration.participant_id) {
            return Err(AppError::ValidationError(
                "Participant already registered".into(),
            ));
        }

        self.participant_keys
            .lock()
            .insert(registration.participant_id.clone(), registration.public_key.clone());

        task.participants.push(registration.participant_id);

        if task.participants.len() as u32 >= task.required_participants {
            task.status = TaskStatus::Training;
        }

        let participant_count = task.participants.len();
        let task_id = task.task_id.clone();
        let task_clone = task.clone();
        drop(tasks);

        self.metrics.set_gauge(
            "fl_registered_participants",
            &[&task_id],
            participant_count as f64
        );

        if let Some(perf) = self.task_performance.lock().get_mut(&task_id) {
            perf.participant_count = participant_count;
            perf.registration_time_ms = start.elapsed().as_millis() as u64;
        }

        let mut global = self.global_metrics.lock();
        global.total_participants += 1;

        Ok(task_clone)
    }

    pub async fn submit_gradient(&self, submission: GradientSubmission) -> AppResult<TrainingTask> {
        let start = Instant::now();
        
        let mut tasks = self.tasks.lock();
        let task = tasks
            .get_mut(&submission.task_id)
            .ok_or_else(|| AppError::NotFound(format!("Task {} not found", submission.task_id)))?;

        if task.status != TaskStatus::Training {
            return Err(AppError::ValidationError(format!(
                "Cannot submit gradient in task status {:?}",
                task.status
            )));
        }

        if !task.participants.contains(&submission.participant_id) {
            return Err(AppError::PermissionDenied(
                "Participant not registered for this task".into(),
            ));
        }

        if task.deadline < chrono::Utc::now() {
            task.status = TaskStatus::TimedOut;
            return Err(AppError::TimeoutError("Training task timed out".into()));
        }

        if self.config.encryption_enabled {
            self.verify_gradient_signature(&submission)?;
        }

        let gradient = EncryptedGradient {
            participant_id: submission.participant_id.clone(),
            encrypted_data: submission.encrypted_gradient,
            signature: submission.signature,
            timestamp: chrono::Utc::now(),
            encryption_key_id: submission.encryption_key_id,
        };

        task.gradients.insert(submission.participant_id, gradient);

        let received_gradients = task.gradients.len() as u32;
        let total_participants = task.participants.len() as u32;
        let task_id = task.task_id.clone();
        let participant_id = submission.participant_id.clone();
        let task_clone = task.clone();

        if received_gradients >= total_participants / 2 + 1 {
            task.status = TaskStatus::Aggregating;
        }
        drop(tasks);

        let duration = start.elapsed().as_secs_f64();
        self.metrics.increment_counter(
            "fl_gradients_submitted_total",
            &[&task_id, &participant_id]
        );
        self.metrics.observe_histogram(
            "fl_gradient_submission_duration_seconds",
            &[&task_id, &participant_id],
            duration
        );

        if let Some(perf) = self.task_performance.lock().get_mut(&task_id) {
            perf.gradient_submission_times_ms.push(duration as u64 * 1000);
        }

        let mut global = self.global_metrics.lock();
        global.total_gradients_submitted += 1;

        Ok(task_clone)
    }

    pub async fn aggregate_gradients(&self, task_id: &str) -> AppResult<ModelUpdate> {
        let start = Instant::now();
        
        let mut tasks = self.tasks.lock();
        let task = tasks
            .get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("Task {} not found", task_id)))?;

        if task.status != TaskStatus::Aggregating {
            return Err(AppError::ValidationError(format!(
                "Cannot aggregate in task status {:?}",
                task.status
            )));
        }

        let agg_key = self.aggregation_keys.lock().clone();
        let aggregated = self.perform_secure_aggregation(task, &agg_key)?;

        let checksum = CryptoService::sha256_hex(&aggregated);

        let model_update = ModelUpdate {
            task_id: task_id.to_string(),
            model_version: task.round + 1,
            model_data: aggregated.clone(),
            checksum,
            created_at: chrono::Utc::now(),
        };

        task.global_model = Some(aggregated);
        task.round += 1;

        let is_completed = task.round >= task.max_rounds;
        if is_completed {
            task.status = TaskStatus::Completed;
        } else {
            task.status = TaskStatus::Training;
            task.gradients.clear();
        }
        
        drop(tasks);

        let duration = start.elapsed().as_secs_f64();
        self.metrics.increment_counter(
            "fl_aggregations_total",
            &[task_id]
        );
        self.metrics.observe_histogram(
            "fl_aggregation_duration_seconds",
            &[task_id],
            duration
        );

        if let Some(perf) = self.task_performance.lock().get_mut(task_id) {
            perf.aggregation_time_ms.push(duration as u64 * 1000);
            perf.per_round_times_ms.push(duration as u64 * 1000);
            perf.total_training_time_ms += duration as u64 * 1000;
        }

        let mut global = self.global_metrics.lock();
        global.total_aggregations += 1;
        
        if is_completed {
            global.completed_tasks += 1;
            global.active_tasks = global.active_tasks.saturating_sub(1);
            self.metrics.set_gauge(
                "fl_active_tasks",
                &[],
                global.active_tasks as f64
            );
        }

        Ok(model_update)
    }

    fn perform_secure_aggregation(
        &self,
        task: &TrainingTask,
        agg_key: &[u8],
    ) -> AppResult<Vec<u8>> {
        let gradients: Vec<&EncryptedGradient> = task.gradients.values().collect();

        if gradients.is_empty() {
            return Err(AppError::ValidationError("No gradients to aggregate".into()));
        }

        let mut aggregated = Vec::new();
        for (i, grad) in gradients.iter().enumerate() {
            let decrypted = if self.config.encryption_enabled {
                CryptoService::aes_decrypt(agg_key, &grad.encrypted_data)?
            } else {
                grad.encrypted_data.clone()
            };

            if i == 0 {
                aggregated = decrypted;
            } else {
                aggregated = self.add_gradients(&aggregated, &decrypted);
            }
        }

        let len = gradients.len() as f64;
        aggregated = self.scale_gradient(&aggregated, 1.0 / len);

        if self.config.encryption_enabled {
            aggregated = CryptoService::aes_encrypt(agg_key, &aggregated)?;
        }

        Ok(aggregated)
    }

    fn add_gradients(&self, a: &[u8], b: &[u8]) -> Vec<u8> {
        let max_len = a.len().max(b.len());
        let mut result = vec![0u8; max_len];

        for i in 0..max_len {
            let av = if i < a.len() { a[i] } else { 0 };
            let bv = if i < b.len() { b[i] } else { 0 };
            result[i] = av.wrapping_add(bv);
        }

        result
    }

    fn scale_gradient(&self, grad: &[u8], factor: f64) -> Vec<u8> {
        if factor == 1.0 {
            return grad.to_vec();
        }

        grad.iter()
            .map(|&v| ((v as f64) * factor) as u8)
            .collect()
    }

    fn verify_gradient_signature(&self, submission: &GradientSubmission) -> AppResult<()> {
        let keys = self.participant_keys.lock();
        let public_key_pem = keys
            .get(&submission.participant_id)
            .ok_or_else(|| AppError::NotFound("Participant public key not found".into()))?;

        let public_key = CryptoService::rsa_public_key_from_pem(public_key_pem)?;

        let mut data = Vec::new();
        data.extend_from_slice(submission.task_id.as_bytes());
        data.extend_from_slice(submission.participant_id.as_bytes());
        data.extend_from_slice(&submission.encrypted_gradient);
        data.extend_from_slice(submission.encryption_key_id.as_bytes());

        let signature = CryptoService::base64_decode(&submission.signature)?;

        let verified = CryptoService::rsa_verify(&public_key, &data, &signature)?;

        if !verified {
            return Err(AppError::ValidationError("Invalid gradient signature".into()));
        }

        Ok(())
    }

    pub async fn get_task(&self, task_id: &str) -> AppResult<TrainingTask> {
        let tasks = self.tasks.lock();
        tasks
            .get(task_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Task {} not found", task_id)))
    }

    pub async fn list_tasks(&self) -> AppResult<Vec<TrainingTask>> {
        let tasks = self.tasks.lock();
        Ok(tasks.values().cloned().collect())
    }

    pub async fn distribute_task(&self, task_id: &str) -> AppResult<Vec<String>> {
        let tasks = self.tasks.lock();
        let task = tasks
            .get(task_id)
            .ok_or_else(|| AppError::NotFound(format!("Task {} not found", task_id)))?;

        Ok(task.participants.clone())
    }

    pub fn create_run_instance(&self, task_id: &str) -> RunInstance {
        let mut instance = RunInstance::new(task_id.to_string());
        instance.set_metadata("module", "federated_learning");
        instance
    }

    pub fn get_task_performance(&self, task_id: &str) -> Option<TaskPerformanceMetrics> {
        self.task_performance.lock().get(task_id).cloned()
    }

    pub fn get_global_metrics(&self) -> FLMetricsSnapshot {
        let snapshot = self.global_metrics.lock().clone();
        let perf = self.task_performance.lock();
        
        let mut snapshot = snapshot;
        if !perf.is_empty() {
            let total_training: u64 = perf.values()
                .map(|p| p.total_training_time_ms)
                .sum();
            let total_aggregation: u64 = perf.values()
                .map(|p| p.aggregation_time_ms.iter().sum::<u64>())
                .sum();
            let total_participants: usize = perf.values()
                .map(|p| p.participant_count)
                .sum();

            snapshot.average_training_time_ms = if perf.len() > 0 {
                total_training as f64 / perf.len() as f64
            } else { 0.0 };
            
            let total_agg_count: usize = perf.values()
                .map(|p| p.aggregation_time_ms.len())
                .sum();
            snapshot.average_aggregation_time_ms = if total_agg_count > 0 {
                total_aggregation as f64 / total_agg_count as f64
            } else { 0.0 };
            
            snapshot.average_participants_per_task = if perf.len() > 0 {
                total_participants as f64 / perf.len() as f64
            } else { 0.0 };
        }
        
        snapshot
    }

    pub fn get_latency_breakdown(&self, task_id: &str) -> Option<LatencyBreakdown> {
        let perf = self.task_performance.lock();
        let task = self.tasks.lock();
        
        let perf = perf.get(task_id)?;
        let task = task.get(task_id)?;
        
        let avg_aggregation = if !perf.aggregation_time_ms.is_empty() {
            perf.aggregation_time_ms.iter().sum::<u64>() / perf.aggregation_time_ms.len() as u64
        } else { 0 };

        Some(LatencyBreakdown {
            registration_ms: perf.registration_time_ms,
            training_distribution_ms: 0,
            gradient_collection_ms: if !perf.gradient_submission_times_ms.is_empty() {
                perf.gradient_submission_times_ms.iter().sum::<u64>() / perf.gradient_submission_times_ms.len() as u64
            } else { 0 },
            aggregation_ms: avg_aggregation,
            model_update_ms: 0,
            total_round_ms: if !perf.per_round_times_ms.is_empty() {
                perf.per_round_times_ms.iter().sum::<u64>() / perf.per_round_times_ms.len() as u64
            } else { 0 },
        })
    }

    pub fn export_prometheus_metrics(&self) -> String {
        self.metrics.gather()
    }

    pub fn reset_metrics(&self) {
        *self.global_metrics.lock() = FLMetricsSnapshot::default();
        self.task_performance.lock().clear();
    }
}
