use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use async_trait::async_trait;
use common::error::AppError;
use common::types::{InferenceRequest, RouteTarget, RoutingStrategy as StrategyType};
use rand::Rng;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use tracing::debug;
use uuid::Uuid;

#[async_trait]
pub trait RoutingStrategy: Send + Sync {
    async fn route(
        &self,
        request: &InferenceRequest,
        targets: &[RouteTarget],
        rules: Option<&Value>,
    ) -> Result<RouteTarget, AppError>;
}

fn select_by_weight(targets: &[RouteTarget], bucket: u32) -> Result<RouteTarget, AppError> {
    if targets.is_empty() {
        return Err(AppError::RoutingError("No routing targets available".to_string()));
    }

    let total_weight: u32 = targets.iter().map(|t| t.weight).sum();
    if total_weight == 0 {
        return Err(AppError::RoutingError("Total weight is zero".to_string()));
    }

    let scaled_bucket = (bucket % 100) as u64;
    let mut cumulative: u64 = 0;

    for target in targets {
        let scaled_weight = (target.weight as u64) * 100 / total_weight as u64;
        cumulative += scaled_weight;
        if scaled_bucket < cumulative {
            return Ok(target.clone());
        }
    }

    Ok(targets.last().unwrap().clone())
}

#[derive(Debug, Clone, Default)]
pub struct UserHashStrategy;

#[async_trait]
impl RoutingStrategy for UserHashStrategy {
    async fn route(
        &self,
        request: &InferenceRequest,
        targets: &[RouteTarget],
        _rules: Option<&Value>,
    ) -> Result<RouteTarget, AppError> {
        let user_id = request
            .user_id
            .as_deref()
            .unwrap_or(&request.request_id);

        let mut hasher = Sha256::new();
        hasher.update(user_id.as_bytes());
        let result = hasher.finalize();

        let mut bytes = [0u8; 4];
        bytes.copy_from_slice(&result[..4]);
        let hash_value = u32::from_be_bytes(bytes);
        let bucket = hash_value % 100;

        debug!(
            "UserHashStrategy: user_id={}, hash_bucket={}, targets={}",
            user_id,
            bucket,
            targets.len()
        );

        select_by_weight(targets, bucket)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct RegionRule {
    pub region_routes: HashMap<String, Uuid>,
    pub default_version_id: Option<Uuid>,
}

#[derive(Debug, Clone, Default)]
pub struct RegionStrategy;

#[async_trait]
impl RoutingStrategy for RegionStrategy {
    async fn route(
        &self,
        request: &InferenceRequest,
        targets: &[RouteTarget],
        rules: Option<&Value>,
    ) -> Result<RouteTarget, AppError> {
        if targets.is_empty() {
            return Err(AppError::RoutingError("No routing targets available".to_string()));
        }

        let region = request
            .parameters
            .as_ref()
            .and_then(|p| p.get("region"))
            .and_then(|r| r.as_str())
            .unwrap_or("default")
            .to_string();

        let region_rule: Option<RegionRule> = rules
            .and_then(|r| serde_json::from_value(r.clone()).ok());

        if let Some(rule) = region_rule {
            if let Some(&version_id) = rule.region_routes.get(&region) {
                if let Some(target) = targets.iter().find(|t| t.model_version_id == version_id) {
                    debug!("RegionStrategy: region={}, matched version={}", region, version_id);
                    return Ok(target.clone());
                }
            }
            if let Some(default_id) = rule.default_version_id {
                if let Some(target) = targets.iter().find(|t| t.model_version_id == default_id) {
                    debug!("RegionStrategy: region={}, using default version={}", region, default_id);
                    return Ok(target.clone());
                }
            }
        }

        let primary = targets.iter().find(|t| t.is_primary).cloned();
        if let Some(p) = primary {
            debug!("RegionStrategy: region={}, using primary version={}", region, p.model_version_id);
            return Ok(p);
        }

        Ok(targets.first().unwrap().clone())
    }
}

#[derive(Debug, Clone, Default)]
pub struct RandomStrategy;

#[async_trait]
impl RoutingStrategy for RandomStrategy {
    async fn route(
        &self,
        _request: &InferenceRequest,
        targets: &[RouteTarget],
        _rules: Option<&Value>,
    ) -> Result<RouteTarget, AppError> {
        if targets.is_empty() {
            return Err(AppError::RoutingError("No routing targets available".to_string()));
        }

        let total_weight: u32 = targets.iter().map(|t| t.weight).sum();
        if total_weight == 0 {
            return Err(AppError::RoutingError("Total weight is zero".to_string()));
        }

        let mut rng = rand::thread_rng();
        let pick = rng.gen_range(0..total_weight);
        let mut cumulative: u32 = 0;

        for target in targets {
            cumulative += target.weight;
            if pick < cumulative {
                debug!(
                    "RandomStrategy: pick={}, selected version={}, weight={}",
                    pick, target.model_version_id, target.weight
                );
                return Ok(target.clone());
            }
        }

        Ok(targets.last().unwrap().clone())
    }
}

#[derive(Debug, Default)]
pub struct RoundRobinStrategy {
    counter: AtomicU64,
}

impl RoundRobinStrategy {
    pub fn new() -> Self {
        Self {
            counter: AtomicU64::new(0),
        }
    }
}

#[async_trait]
impl RoutingStrategy for RoundRobinStrategy {
    async fn route(
        &self,
        _request: &InferenceRequest,
        targets: &[RouteTarget],
        _rules: Option<&Value>,
    ) -> Result<RouteTarget, AppError> {
        if targets.is_empty() {
            return Err(AppError::RoutingError("No routing targets available".to_string()));
        }

        let total_weight: u32 = targets.iter().map(|t| t.weight).sum();
        if total_weight == 0 {
            return Err(AppError::RoutingError("Total weight is zero".to_string()));
        }

        let idx = self.counter.fetch_add(1, Ordering::Relaxed) % total_weight as u64;
        let mut cumulative: u64 = 0;

        for target in targets {
            cumulative += target.weight as u64;
            if idx < cumulative {
                debug!(
                    "RoundRobinStrategy: counter_idx={}, selected version={}",
                    idx, target.model_version_id
                );
                return Ok(target.clone());
            }
        }

        Ok(targets.last().unwrap().clone())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExperimentGroupRule {
    pub group_name: String,
    pub model_version_id: Uuid,
    pub traffic_percent: u8,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExperimentRule {
    pub experiment_id: Uuid,
    pub groups: Vec<ExperimentGroupRule>,
    pub sticky: bool,
}

#[derive(Clone)]
pub struct ExperimentStrategy {
    user_assignment_getter: Arc<dyn Fn(&str, &str) -> Option<String> + Send + Sync>,
    user_assignment_setter: Arc<dyn Fn(&str, &str, &str, u64) + Send + Sync>,
}

impl std::fmt::Debug for ExperimentStrategy {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ExperimentStrategy")
            .field("user_assignment_getter", &"<closure>")
            .field("user_assignment_setter", &"<closure>")
            .finish()
    }
}

impl ExperimentStrategy {
    pub fn new<G, S>(getter: G, setter: S) -> Self
    where
        G: Fn(&str, &str) -> Option<String> + Send + Sync + 'static,
        S: Fn(&str, &str, &str, u64) + Send + Sync + 'static,
    {
        Self {
            user_assignment_getter: Arc::new(getter),
            user_assignment_setter: Arc::new(setter),
        }
    }

    fn hash_user_to_percent(user_id: &str, experiment_id: &Uuid) -> u8 {
        let mut hasher = Sha256::new();
        hasher.update(experiment_id.as_bytes());
        hasher.update(user_id.as_bytes());
        let result = hasher.finalize();
        let mut bytes = [0u8; 4];
        bytes.copy_from_slice(&result[..4]);
        let val = u32::from_be_bytes(bytes);
        (val % 100) as u8
    }

    fn select_group(percent: u8, groups: &[ExperimentGroupRule]) -> Option<&ExperimentGroupRule> {
        let mut cumulative: u8 = 0;
        for group in groups {
            cumulative = cumulative.saturating_add(group.traffic_percent);
            if percent < cumulative {
                return Some(group);
            }
        }
        groups.first()
    }
}

impl Default for ExperimentStrategy {
    fn default() -> Self {
        Self::new(|_, _| None, |_, _, _, _| {})
    }
}

#[async_trait]
impl RoutingStrategy for ExperimentStrategy {
    async fn route(
        &self,
        request: &InferenceRequest,
        targets: &[RouteTarget],
        rules: Option<&Value>,
    ) -> Result<RouteTarget, AppError> {
        if targets.is_empty() {
            return Err(AppError::RoutingError("No routing targets available".to_string()));
        }

        let user_id = request
            .user_id
            .as_deref()
            .unwrap_or(&request.request_id);

        let experiment_rule: ExperimentRule = rules
            .and_then(|r| serde_json::from_value(r.clone()).ok())
            .ok_or_else(|| AppError::RoutingError("Invalid experiment rule configuration".to_string()))?;

        let exp_id_str = experiment_rule.experiment_id.to_string();
        let mut assigned_version_str: Option<String> = None;

        if experiment_rule.sticky {
            assigned_version_str = (self.user_assignment_getter)(&exp_id_str, user_id);
        }

        let selected_version_id = if let Some(ver_str) = assigned_version_str {
            debug!("ExperimentStrategy: sticky assignment found for user={}, version={}", user_id, ver_str);
            Uuid::parse_str(&ver_str)
                .map_err(|_| AppError::RoutingError("Invalid cached version id".to_string()))?
        } else {
            let percent = Self::hash_user_to_percent(user_id, &experiment_rule.experiment_id);
            let group = Self::select_group(percent, &experiment_rule.groups)
                .ok_or_else(|| AppError::RoutingError("No experiment group available".to_string()))?;

            debug!(
                "ExperimentStrategy: user={}, percent={}, group={}, version={}",
                user_id, percent, group.group_name, group.model_version_id
            );

            if experiment_rule.sticky {
                let ttl_secs = 7 * 24 * 60 * 60;
                (self.user_assignment_setter)(
                    &exp_id_str,
                    user_id,
                    &group.model_version_id.to_string(),
                    ttl_secs,
                );
            }

            group.model_version_id
        };

        if let Some(target) = targets.iter().find(|t| t.model_version_id == selected_version_id) {
            return Ok(target.clone());
        }

        let primary = targets.iter().find(|t| t.is_primary).cloned();
        primary.ok_or_else(|| {
            AppError::RoutingError(format!(
                "Experiment target version {} not found in routing targets",
                selected_version_id
            ))
        })
    }
}

#[derive(Debug, Clone)]
pub struct WeightedSelector<T: Clone> {
    items: Vec<(T, u32)>,
    total_weight: u64,
}

impl<T: Clone> WeightedSelector<T> {
    pub fn new(items: Vec<(T, u32)>) -> Result<Self, AppError> {
        if items.is_empty() {
            return Err(AppError::RoutingError("Empty weighted selector items".to_string()));
        }
        let total_weight: u64 = items.iter().map(|(_, w)| *w as u64).sum();
        if total_weight == 0 {
            return Err(AppError::RoutingError("Total weight is zero in weighted selector".to_string()));
        }
        Ok(Self { items, total_weight })
    }

    pub fn from_targets(targets: &[RouteTarget]) -> Result<WeightedSelector<RouteTarget>, AppError> {
        let items: Vec<(RouteTarget, u32)> = targets
            .iter()
            .map(|t| (t.clone(), t.weight))
            .collect();
        WeightedSelector::new(items)
    }

    pub fn select_random(&self) -> T {
        let mut rng = rand::thread_rng();
        let pick = rng.gen_range(0..self.total_weight);
        self.select_by_cumulative(pick)
    }

    pub fn select_by_bucket(&self, bucket: u64) -> T {
        let pick = bucket % self.total_weight;
        self.select_by_cumulative(pick)
    }

    fn select_by_cumulative(&self, pick: u64) -> T {
        let mut cumulative: u64 = 0;
        for (item, weight) in &self.items {
            cumulative += *weight as u64;
            if pick < cumulative {
                return item.clone();
            }
        }
        self.items.last().unwrap().0.clone()
    }

    pub fn total_weight(&self) -> u64 {
        self.total_weight
    }

    pub fn len(&self) -> usize {
        self.items.len()
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    pub fn items(&self) -> &[(T, u32)] {
        &self.items
    }
}

pub struct StrategyFactory {
    user_assignment_getter: Option<Arc<dyn Fn(&str, &str) -> Option<String> + Send + Sync>>,
    user_assignment_setter: Option<Arc<dyn Fn(&str, &str, &str, u64) + Send + Sync>>,
}

impl StrategyFactory {
    pub fn new() -> Self {
        Self {
            user_assignment_getter: None,
            user_assignment_setter: None,
        }
    }

    pub fn with_user_assignment<G, S>(getter: G, setter: S) -> Self
    where
        G: Fn(&str, &str) -> Option<String> + Send + Sync + 'static,
        S: Fn(&str, &str, &str, u64) + Send + Sync + 'static,
    {
        Self {
            user_assignment_getter: Some(Arc::new(getter)),
            user_assignment_setter: Some(Arc::new(setter)),
        }
    }

    pub fn with_arcs(
        getter: Option<Arc<dyn Fn(&str, &str) -> Option<String> + Send + Sync>>,
        setter: Option<Arc<dyn Fn(&str, &str, &str, u64) + Send + Sync>>,
    ) -> Self {
        Self {
            user_assignment_getter: getter,
            user_assignment_setter: setter,
        }
    }

    pub fn create(&self, strategy_type: StrategyType) -> Box<dyn RoutingStrategy> {
        match strategy_type {
            StrategyType::UserHash => Box::new(UserHashStrategy),
            StrategyType::Region => Box::new(RegionStrategy),
            StrategyType::Random => Box::new(RandomStrategy),
            StrategyType::RoundRobin => Box::new(RoundRobinStrategy::new()),
            StrategyType::Experiment => {
                let getter = self
                    .user_assignment_getter
                    .clone()
                    .unwrap_or_else(|| Arc::new(|_, _| None));
                let setter = self
                    .user_assignment_setter
                    .clone()
                    .unwrap_or_else(|| Arc::new(|_, _, _, _| {}));
                Box::new(ExperimentStrategy {
                    user_assignment_getter: getter,
                    user_assignment_setter: setter,
                })
            }
        }
    }

    pub fn from_str(
        &self,
        strategy_str: &str,
    ) -> Result<Box<dyn RoutingStrategy>, AppError> {
        let strategy_type = match strategy_str.to_lowercase().as_str() {
            "user_hash" | "userhash" => StrategyType::UserHash,
            "region" => StrategyType::Region,
            "random" => StrategyType::Random,
            "round_robin" | "roundrobin" => StrategyType::RoundRobin,
            "experiment" | "ab_test" | "abtest" => StrategyType::Experiment,
            other => {
                return Err(AppError::Config(format!(
                    "Unknown routing strategy: {}",
                    other
                )))
            }
        };
        Ok(self.create(strategy_type))
    }
}

impl Default for StrategyFactory {
    fn default() -> Self {
        Self::new()
    }
}

pub fn create_strategy(
    strategy_type: StrategyType,
    user_assignment_getter: Option<Arc<dyn Fn(&str, &str) -> Option<String> + Send + Sync>>,
    user_assignment_setter: Option<Arc<dyn Fn(&str, &str, &str, u64) + Send + Sync>>,
) -> Box<dyn RoutingStrategy> {
    StrategyFactory::with_arcs(user_assignment_getter, user_assignment_setter)
        .create(strategy_type)
}
