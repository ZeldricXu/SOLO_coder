use crate::config::DpConfig;
use crate::models::AppError;
use crate::utils::{current_datetime, create_rng};
use chrono::{DateTime, Utc};
use rand::Rng;
use rand_chacha::ChaCha20Rng;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum NoiseMechanism {
    Laplace,
    Gaussian,
    Exponential,
    Geometric,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrivacyBudget {
    pub total_epsilon: f64,
    pub total_delta: f64,
    pub used_epsilon: f64,
    pub used_delta: f64,
    pub remaining_epsilon: f64,
    pub remaining_delta: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueryContext {
    pub query_id: String,
    pub epsilon: f64,
    pub delta: f64,
    pub sensitivity: f64,
    pub mechanism: NoiseMechanism,
    pub timestamp: DateTime<Utc>,
    pub user_id: Option<String>,
    pub dataset_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DpResult {
    pub original_value: f64,
    pub noisy_value: f64,
    pub noise_added: f64,
    pub epsilon_used: f64,
    pub delta_used: f64,
    pub mechanism: NoiseMechanism,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BudgetAllocation {
    pub allocation_id: String,
    pub user_id: String,
    pub dataset_id: String,
    pub allocated_epsilon: f64,
    pub allocated_delta: f64,
    pub used_epsilon: f64,
    pub used_delta: f64,
    pub created_at: DateTime<Utc>,
    pub expires_at: Option<DateTime<Utc>>,
}

pub struct DifferentialPrivacyEngine {
    config: DpConfig,
    global_budget: Arc<Mutex<PrivacyBudget>>,
    user_budgets: Arc<Mutex<HashMap<String, BudgetAllocation>>>,
    query_history: Arc<Mutex<Vec<QueryContext>>>,
    rng: Arc<Mutex<ChaCha20Rng>>,
}

impl DifferentialPrivacyEngine {
    pub fn new(config: DpConfig) -> Self {
        let global_budget = PrivacyBudget {
            total_epsilon: config.global_budget,
            total_delta: config.default_delta,
            used_epsilon: 0.0,
            used_delta: 0.0,
            remaining_epsilon: config.global_budget,
            remaining_delta: config.default_delta,
        };

        Self {
            config,
            global_budget: Arc::new(Mutex::new(global_budget)),
            user_budgets: Arc::new(Mutex::new(HashMap::new())),
            query_history: Arc::new(Mutex::new(Vec::new())),
            rng: Arc::new(Mutex::new(create_rng(None))),
        }
    }

    pub fn check_budget(&self) -> PrivacyBudget {
        let budget = self.global_budget.lock().unwrap();
        budget.clone()
    }

    pub fn allocate_user_budget(
        &self,
        user_id: &str,
        dataset_id: &str,
        epsilon: f64,
        delta: Option<f64>,
        expires_in_secs: Option<u64>,
    ) -> Result<BudgetAllocation, AppError> {
        if epsilon <= 0.0 {
            return Err(AppError::Validation("Epsilon must be positive".to_string()));
        }

        if epsilon > self.config.max_budget_per_query {
            return Err(AppError::Validation(format!(
                "Epsilon exceeds maximum per-query budget of {}",
                self.config.max_budget_per_query
            )));
        }

        {
            let mut global = self.global_budget.lock().unwrap();
            if global.remaining_epsilon < epsilon {
                return Err(AppError::Validation(
                    "Insufficient global privacy budget".to_string()));
            }
        }

        let delta = delta.unwrap_or(self.config.default_delta);
        let now = current_datetime();
        let expires_at = expires_in_secs.map(|s| now + chrono::Duration::seconds(s as i64));

        let allocation = BudgetAllocation {
            allocation_id: crate::utils::generate_id("alloc"),
            user_id: user_id.to_string(),
            dataset_id: dataset_id.to_string(),
            allocated_epsilon: epsilon,
            allocated_delta: delta,
            used_epsilon: 0.0,
            used_delta: 0.0,
            created_at: now,
            expires_at,
        };

        {
            let mut user_budgets = self.user_budgets.lock().unwrap();
            user_budgets.insert(allocation.allocation_id.clone(), allocation.clone());
        }

        {
            let mut global = self.global_budget.lock().unwrap();
            global.used_epsilon += epsilon;
            global.remaining_epsilon -= epsilon;
        }

        Ok(allocation)
    }

    pub fn check_user_budget(&self, allocation_id: &str) -> Option<BudgetAllocation> {
        let budgets = self.user_budgets.lock().unwrap();
        budgets.get(allocation_id).cloned()
    }

    pub fn release_budget(&self, allocation_id: &str) -> Result<(), AppError> {
        let mut budgets = self.user_budgets.lock().unwrap();
        if let Some(allocation) = budgets.remove(allocation_id) {
            let mut global = self.global_budget.lock().unwrap();
            global.used_epsilon -= allocation.used_epsilon;
            global.remaining_epsilon += allocation.allocated_epsilon - allocation.used_epsilon;
            Ok(())
        } else {
            Err(AppError::NotFound(format!("Allocation not found: {}", allocation_id)))
        }
    }

    pub fn add_laplace_noise(
        &self,
        value: f64,
        epsilon: f64,
        sensitivity: f64,
    ) -> DpResult {
        let scale = sensitivity / epsilon;
        let noise = self.sample_laplace(scale);
        let noisy = value + noise;

        DpResult {
            original_value: value,
            noisy_value: noisy,
            noise_added: noise,
            epsilon_used: epsilon,
            delta_used: 0.0,
            mechanism: NoiseMechanism::Laplace,
        }
    }

    pub fn add_gaussian_noise(
        &self,
        value: f64,
        epsilon: f64,
        delta: f64,
        sensitivity: f64,
    ) -> DpResult {
        let sigma = sensitivity * (2.0 * (1.25 / delta).ln()).sqrt() / epsilon;
        let noise = self.sample_gaussian(sigma);
        let noisy = value + noise;

        DpResult {
            original_value: value,
            noisy_value: noisy,
            noise_added: noise,
            epsilon_used: epsilon,
            delta_used: delta,
            mechanism: NoiseMechanism::Gaussian,
        }
    }

    pub fn add_noise(
        &self,
        value: f64,
        context: &QueryContext,
        allocation_id: Option<&str>,
    ) -> Result<DpResult, AppError> {
        if context.epsilon <= 0.0 {
            return Err(AppError::Validation("Epsilon must be positive".to_string()));
        }

        if context.epsilon > self.config.max_budget_per_query {
            return Err(AppError::Validation(format!(
                "Epsilon exceeds maximum per-query budget of {}",
                self.config.max_budget_per_query
            )));
        }

        if let Some(alloc_id) = allocation_id {
            let mut budgets = self.user_budgets.lock().unwrap();
            if let Some(allocation) = budgets.get_mut(alloc_id) {
                let remaining = allocation.allocated_epsilon - allocation.used_epsilon;
                if remaining < context.epsilon {
                    return Err(AppError::Validation("Insufficient user budget".to_string()));
                }
                allocation.used_epsilon += context.epsilon;
            } else {
                return Err(AppError::NotFound(format!("Allocation not found: {}", alloc_id)));
            }
        }

        {
            let mut global = self.global_budget.lock().unwrap();
            if global.remaining_epsilon < context.epsilon {
                return Err(AppError::Validation("Insufficient global privacy budget".to_string()));
            }
            global.used_epsilon += context.epsilon;
            global.remaining_epsilon -= context.epsilon;
        }

        {
            let mut history = self.query_history.lock().unwrap();
            history.push(context.clone());
        }

        let result = match context.mechanism {
            NoiseMechanism::Laplace => {
                self.add_laplace_noise(value, context.epsilon, context.sensitivity)
            }
            NoiseMechanism::Gaussian => self.add_gaussian_noise(
                value,
                context.epsilon,
                context.delta,
                context.sensitivity,
            ),
            NoiseMechanism::Exponential => {
                let laplace =
                    self.add_laplace_noise(value, context.epsilon, context.sensitivity);
                DpResult {
                    mechanism: NoiseMechanism::Exponential,
                    ..laplace
                }
            }
            NoiseMechanism::Geometric => {
                let laplace =
                    self.add_laplace_noise(value, context.epsilon, context.sensitivity);
                DpResult {
                    mechanism: NoiseMechanism::Geometric,
                    ..laplace
                }
            }
        };

        Ok(result)
    }

    pub fn add_noise_to_count(
        &self,
        count: i64,
        epsilon: f64,
        allocation_id: Option<&str>,
    ) -> Result<DpResult, AppError> {
        let context = QueryContext {
            query_id: crate::utils::generate_id("qry"),
            epsilon,
            delta: self.config.default_delta,
            sensitivity: 1.0,
            mechanism: NoiseMechanism::Laplace,
            timestamp: current_datetime(),
            user_id: None,
            dataset_id: None,
        };

        self.add_noise(count as f64, &context, allocation_id)
    }

    pub fn add_noise_to_sum(
        &self,
        sum: f64,
        epsilon: f64,
        lower_bound: f64,
        upper_bound: f64,
        allocation_id: Option<&str>,
    ) -> Result<DpResult, AppError> {
        let sensitivity = (upper_bound - lower_bound).abs();
        let context = QueryContext {
            query_id: crate::utils::generate_id("qry"),
            epsilon,
            delta: self.config.default_delta,
            sensitivity,
            mechanism: NoiseMechanism::Laplace,
            timestamp: current_datetime(),
            user_id: None,
            dataset_id: None,
        };

        self.add_noise(sum, &context, allocation_id)
    }

    pub fn add_noise_to_average(
        &self,
        average: f64,
        epsilon: f64,
        count: usize,
        lower_bound: f64,
        upper_bound: f64,
        allocation_id: Option<&str>,
    ) -> Result<DpResult, AppError> {
        if count == 0 {
            return Err(AppError::Validation("Count cannot be zero".to_string()));
        }

        let sensitivity = (upper_bound - lower_bound).abs() / count as f64;
        let context = QueryContext {
            query_id: crate::utils::generate_id("qry"),
            epsilon,
            delta: self.config.default_delta,
            sensitivity,
            mechanism: NoiseMechanism::Gaussian,
            timestamp: current_datetime(),
            user_id: None,
            dataset_id: None,
        };

        self.add_noise(average, &context, allocation_id)
    }

    pub fn add_noise_to_vector(
        &self,
        values: &[f64],
        epsilon: f64,
        sensitivity: f64,
        mechanism: NoiseMechanism,
        allocation_id: Option<&str>,
    ) -> Result<Vec<DpResult>, AppError> {
        let per_epsilon = epsilon / values.len() as f64;
        let mut results = Vec::with_capacity(values.len());

        for value in values {
            let context = QueryContext {
                query_id: crate::utils::generate_id("qry"),
                epsilon: per_epsilon,
                delta: self.config.default_delta,
                sensitivity,
                mechanism,
                timestamp: current_datetime(),
                user_id: None,
                dataset_id: None,
            };

            let result = self.add_noise(*value, &context, allocation_id)?;
            results.push(result);
        }

        Ok(results)
    }

    fn sample_laplace(&self, scale: f64) -> f64 {
        let mut rng = self.rng.lock().unwrap();
        let u: f64 = rng.gen_range(0.0..1.0);
        let sign = if u < 0.5 { 1.0 } else { -1.0 };
        -scale * sign * (1.0 - 2.0 * (u - 0.5).abs()).ln()
    }

    fn sample_gaussian(&self, sigma: f64) -> f64 {
        let mut rng = self.rng.lock().unwrap();
        let u1: f64 = rng.gen_range(0.0..1.0);
        let u2: f64 = rng.gen_range(0.0..1.0);
        let z = (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos();
        z * sigma
    }

    pub fn reset_global_budget(&self) {
        let mut budget = self.global_budget.lock().unwrap();
        budget.used_epsilon = 0.0;
        budget.used_delta = 0.0;
        budget.remaining_epsilon = budget.total_epsilon;
        budget.remaining_delta = budget.total_delta;
    }

    pub fn get_query_history(&self) -> Vec<QueryContext> {
        let history = self.query_history.lock().unwrap();
        history.clone()
    }

    pub fn get_all_user_budgets(&self) -> Vec<BudgetAllocation> {
        let budgets = self.user_budgets.lock().unwrap();
        budgets.values().cloned().collect()
    }

    pub fn epsilon_delta_composition(&self, epsilons: &[f64]) -> f64 {
        epsilons.iter().sum()
    }

    pub fn advanced_composition(
        &self,
        epsilons: &[f64],
        deltas: &[f64],
        k: usize,
    ) -> (f64, f64) {
        let epsilon_sum: f64 = epsilons.iter().sum();
        let delta_sum: f64 = deltas.iter().sum();
        
        let k_f64 = k as f64;
        let composed_epsilon = epsilon_sum * (2.0 * k_f64 * (1.0 / delta_sum.max(f64::EPSILON)).ln()).sqrt()
            + epsilon_sum;
        let composed_delta = delta_sum + (k_f64 * delta_sum).exp() * delta_sum;

        (composed_epsilon, composed_delta)
    }

    pub fn calculate_privacy_loss(
        &self,
        original: f64,
        noisy: f64,
        epsilon: f64,
    ) -> f64 {
        let diff = (noisy - original).abs();
        let loss = diff / epsilon;
        loss.min(1.0)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DpEvent {
    pub event_type: String,
    pub query_id: Option<String>,
    pub timestamp: DateTime<Utc>,
    pub details: serde_json::Value,
}

impl DpEvent {
    pub fn new(
        event_type: &str,
        query_id: Option<String>,
        details: serde_json::Value,
    ) -> Self {
        Self {
            event_type: event_type.to_string(),
            query_id,
            timestamp: current_datetime(),
            details,
        }
    }
}
