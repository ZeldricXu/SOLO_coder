use rand::Rng;
use rand_chacha::ChaCha8Rng;
use rand::SeedableRng;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

use crate::domain::run_instance::RunInstance;
use crate::infra::config::DPConfig;
use crate::infra::crypto::CryptoService;
use crate::infra::error::{AppError, AppResult};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum NoiseDistribution {
    Laplace,
    Gaussian,
    Exponential,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrivacyBudget {
    pub budget_id: String,
    pub user_id: String,
    pub total_epsilon: f64,
    pub used_epsilon: f64,
    pub total_delta: f64,
    pub used_delta: f64,
    pub hourly_limit: f64,
    pub hourly_used: f64,
    pub last_reset: chrono::DateTime<chrono::Utc>,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

impl PrivacyBudget {
    pub fn new(user_id: String, total_epsilon: f64, total_delta: f64, hourly_limit: f64) -> Self {
        let now = chrono::Utc::now();
        Self {
            budget_id: format!("budget_{}", Uuid::new_v4().simple()),
            user_id,
            total_epsilon,
            used_epsilon: 0.0,
            total_delta,
            used_delta: 0.0,
            hourly_limit,
            hourly_used: 0.0,
            last_reset: now,
            created_at: now,
        }
    }

    pub fn remaining_epsilon(&self) -> f64 {
        self.total_epsilon - self.used_epsilon
    }

    pub fn can_consume(&self, epsilon: f64, delta: f64) -> bool {
        epsilon > 0.0
            && delta >= 0.0
            && self.used_epsilon + epsilon <= self.total_epsilon
            && self.used_delta + delta <= self.total_delta
            && self.hourly_used + epsilon <= self.hourly_limit
    }

    pub fn consume(&mut self, epsilon: f64, delta: f64) -> bool {
        if self.can_consume(epsilon, delta) {
            self.used_epsilon += epsilon;
            self.used_delta += delta;
            self.hourly_used += epsilon;
            true
        } else {
            false
        }
    }

    pub fn check_and_reset_hourly(&mut self) {
        let now = chrono::Utc::now();
        if (now - self.last_reset).num_hours() >= 1 {
            self.hourly_used = 0.0;
            self.last_reset = now;
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DPPrivacyParams {
    pub epsilon: f64,
    pub delta: f64,
    pub distribution: NoiseDistribution,
    pub sensitivity: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DPQueryRequest {
    pub query_id: Option<String>,
    pub data: serde_json::Value,
    pub params: DPPrivacyParams,
    pub user_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DPQueryResponse {
    pub query_id: String,
    pub original: serde_json::Value,
    pub noised: serde_json::Value,
    pub epsilon_used: f64,
    pub delta_used: f64,
    pub distribution: NoiseDistribution,
    pub noise_seed: String,
    pub remaining_budget: f64,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BudgetConsumption {
    pub consumption_id: String,
    pub budget_id: String,
    pub epsilon: f64,
    pub delta: f64,
    pub query_id: String,
    pub timestamp: chrono::DateTime<chrono::Utc>,
}

pub struct DifferentialPrivacyService {
    config: DPConfig,
    budgets: std::sync::Arc<parking_lot::Mutex<HashMap<String, PrivacyBudget>>>,
    consumption_history: std::sync::Arc<parking_lot::Mutex<Vec<BudgetConsumption>>>,
    query_history: std::sync::Arc<parking_lot::Mutex<HashMap<String, DPQueryResponse>>>,
    rng: std::sync::Arc<parking_lot::Mutex<ChaCha8Rng>>,
}

impl DifferentialPrivacyService {
    pub fn new(config: DPConfig) -> Self {
        let seed = CryptoService::random_bytes(32);
        let mut seed_arr = [0u8; 32];
        for (i, &b) in seed.iter().take(32).enumerate() {
            seed_arr[i] = b;
        }
        let rng = ChaCha8Rng::from_seed(seed_arr);

        Self {
            config,
            budgets: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            consumption_history: std::sync::Arc::new(parking_lot::Mutex::new(Vec::new())),
            query_history: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            rng: std::sync::Arc::new(parking_lot::Mutex::new(rng)),
        }
    }

    pub fn create_budget(
        &self,
        user_id: String,
        total_epsilon: Option<f64>,
        total_delta: Option<f64>,
        hourly_limit: Option<f64>,
    ) -> PrivacyBudget {
        let budget = PrivacyBudget::new(
            user_id,
            total_epsilon.unwrap_or(self.config.default_epsilon * 10.0),
            total_delta.unwrap_or(self.config.default_delta),
            hourly_limit.unwrap_or(self.config.max_budget_per_hour),
        );

        self.budgets
            .lock()
            .insert(budget.user_id.clone(), budget.clone());

        budget
    }

    pub fn get_budget(&self, user_id: &str) -> AppResult<PrivacyBudget> {
        let mut budgets = self.budgets.lock();
        let budget = budgets
            .get_mut(user_id)
            .ok_or_else(|| AppError::NotFound(format!("Budget for user {} not found", user_id)))?;

        budget.check_and_reset_hourly();
        Ok(budget.clone())
    }

    pub async fn apply_dp(
        &self,
        request: DPQueryRequest,
    ) -> AppResult<DPQueryResponse> {
        let mut budgets = self.budgets.lock();
        let budget = budgets
            .get_mut(&request.user_id)
            .ok_or_else(|| {
                AppError::NotFound(format!("Budget for user {} not found", request.user_id))
            })?;

        budget.check_and_reset_hourly();

        if !budget.can_consume(request.params.epsilon, request.params.delta) {
            return Err(AppError::PrivacyBudgetExhausted(format!(
                "Privacy budget exhausted. Remaining: {:.4}, Requested: {:.4}",
                budget.remaining_epsilon(),
                request.params.epsilon
            )));
        }

        let seed = CryptoService::sha256_hex(
            format!("{}{}", Uuid::new_v4(), chrono::Utc::now().timestamp_nanos()).as_bytes(),
        );

        let noised = self.add_noise(&request.data, &request.params, &seed)?;

        budget.consume(request.params.epsilon, request.params.delta);

        let query_id = request
            .query_id
            .unwrap_or_else(|| format!("dp_query_{}", Uuid::new_v4().simple()));

        let response = DPQueryResponse {
            query_id: query_id.clone(),
            original: request.data,
            noised,
            epsilon_used: request.params.epsilon,
            delta_used: request.params.delta,
            distribution: request.params.distribution,
            noise_seed: seed,
            remaining_budget: budget.remaining_epsilon(),
            created_at: chrono::Utc::now(),
        };

        let consumption = BudgetConsumption {
            consumption_id: format!("cons_{}", Uuid::new_v4().simple()),
            budget_id: budget.budget_id.clone(),
            epsilon: request.params.epsilon,
            delta: request.params.delta,
            query_id: query_id.clone(),
            timestamp: chrono::Utc::now(),
        };

        self.consumption_history.lock().push(consumption);
        self.query_history.lock().insert(query_id, response.clone());

        Ok(response)
    }

    fn add_noise(
        &self,
        value: &serde_json::Value,
        params: &DPPrivacyParams,
        seed: &str,
    ) -> AppResult<serde_json::Value> {
        match value {
            serde_json::Value::Number(n) => {
                if let Some(f) = n.as_f64() {
                    let noise = self.generate_noise(params, seed)?;
                    let noised = f + noise;
                    Ok(serde_json::Value::Number(
                        serde_json::Number::from_f64(noised)
                            .unwrap_or_else(|| serde_json::Number::from(0)),
                    ))
                } else if let Some(i) = n.as_i64() {
                    let noise = self.generate_noise(params, seed)?;
                    let noised = (i as f64 + noise).round() as i64;
                    Ok(serde_json::Value::Number(serde_json::Number::from(noised)))
                } else if let Some(u) = n.as_u64() {
                    let noise = self.generate_noise(params, seed)?;
                    let noised = (u as f64 + noise).round().max(0.0) as u64;
                    Ok(serde_json::Value::Number(serde_json::Number::from(noised)))
                } else {
                    Ok(value.clone())
                }
            }
            serde_json::Value::Array(arr) => {
                let noised_arr: Result<Vec<_>, _> = arr
                    .iter()
                    .enumerate()
                    .map(|(i, v)| {
                        let element_seed = format!("{}_{}", seed, i);
                        self.add_noise(v, params, &element_seed)
                    })
                    .collect();
                Ok(serde_json::Value::Array(noised_arr?))
            }
            serde_json::Value::Object(obj) => {
                let mut noised_obj = serde_json::Map::new();
                for (key, val) in obj {
                    let field_seed = format!("{}_{}", seed, key);
                    noised_obj.insert(key.clone(), self.add_noise(val, params, &field_seed)?);
                }
                Ok(serde_json::Value::Object(noised_obj))
            }
            _ => Ok(value.clone()),
        }
    }

    fn generate_noise(&self, params: &DPPrivacyParams, seed: &str) -> AppResult<f64> {
        let mut seed_bytes = [0u8; 32];
        let seed_hash = CryptoService::sha256_hash(seed.as_bytes());
        for (i, &b) in seed_hash.iter().take(32).enumerate() {
            seed_bytes[i] = b;
        }
        let mut rng = ChaCha8Rng::from_seed(seed_bytes);

        match params.distribution {
            NoiseDistribution::Laplace => {
                let lambda = params.sensitivity / params.epsilon;
                let u: f64 = rng.gen::<f64>() - 0.5;
                Ok(-lambda * u.signum() * (1.0 - 2.0 * u.abs()).ln())
            }
            NoiseDistribution::Gaussian => {
                if params.delta <= 0.0 {
                    return Err(AppError::ValidationError(
                        "Gaussian mechanism requires delta > 0".into(),
                    ));
                }
                let sigma = params.sensitivity * (2.0 * (1.25 / params.delta).ln()).sqrt()
                    / params.epsilon;
                let u1: f64 = rng.gen::<f64>();
                let u2: f64 = rng.gen::<f64>();
                let z = (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos();
                Ok(sigma * z)
            }
            NoiseDistribution::Exponential => {
                let lambda = params.epsilon / (2.0 * params.sensitivity);
                let u: f64 = rng.gen::<f64>();
                Ok(-(1.0 - u).ln() / lambda)
            }
        }
    }

    pub fn calculate_epsilon_for_accuracy(
        &self,
        desired_accuracy: f64,
        sensitivity: f64,
        confidence: f64,
    ) -> f64 {
        let z = self.inverse_normal_cdf(1.0 - (1.0 - confidence) / 2.0);
        (sensitivity * z) / desired_accuracy
    }

    fn inverse_normal_cdf(&self, p: f64) -> f64 {
        if p <= 0.0 {
            return f64::NEG_INFINITY;
        }
        if p >= 1.0 {
            return f64::INFINITY;
        }

        let a = [
            2.50662823884,
            -18.61500062529,
            41.39119773534,
            -25.44106049637,
        ];
        let b = [
            -8.47351093090,
            23.08336743743,
            -21.06224101826,
            3.13082909833,
        ];
        let c = [
            0.3374754822726147,
            0.9761690190917186,
            0.1607979714918209,
            0.0276438810333863,
            0.0038405729373609,
            0.0003951896511919,
            0.0000321767881768,
            0.0000002888167364,
            0.0000003960315187,
        ];

        let q = p - 0.5;
        if q.abs() < 0.42 {
            let r = q * q;
            q * (((a[3] * r + a[2]) * r + a[1]) * r + a[0])
                / ((((b[3] * r + b[2]) * r + b[1]) * r + b[0]) * r + 1.0)
        } else {
            let r = if q < 0.0 { p } else { 1.0 - p };
            let r = (-r.ln()).sqrt();
            let mut x = (((((((c[8] * r + c[7]) * r + c[6]) * r + c[5]) * r + c[4]) * r + c[3])
                * r
                + c[2])
                * r
                + c[1])
                * r
                + c[0];
            if q < 0.0 {
                x = -x;
            }
            x
        }
    }

    pub fn get_query_history(&self, user_id: Option<&str>) -> Vec<DPQueryResponse> {
        let history = self.query_history.lock();
        match user_id {
            Some(_uid) => history.values().cloned().collect(),
            None => history.values().cloned().collect(),
        }
    }

    pub fn get_consumption_history(&self, user_id: &str) -> Vec<BudgetConsumption> {
        let budgets = self.budgets.lock();
        let budget_id = budgets.get(user_id).map(|b| b.budget_id.clone());
        drop(budgets);

        let history = self.consumption_history.lock();
        match budget_id {
            Some(bid) => history
                .iter()
                .filter(|c| c.budget_id == bid)
                .cloned()
                .collect(),
            None => Vec::new(),
        }
    }

    pub fn create_run_instance(&self, query_id: &str) -> RunInstance {
        let mut instance = RunInstance::new(query_id.to_string());
        instance.set_metadata("module", "differential_privacy");
        instance
    }
}
