use std::collections::HashMap;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};

use common::error::AppError;
use common::types::{InferenceRequest, RouteTarget, RoutingStrategy as StrategyType};
use dashmap::DashMap;
use db::DatabasePool;
use db::repository::routing_repo::RoutingRule;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::broadcast;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use crate::cache::RoutingCache;
use crate::strategy::{create_strategy, RoutingStrategy};
use crate::RoutingConfig;

const LOCAL_CAPACITY_HINT: usize = 128;
const BROADCAST_CHANNEL_CAPACITY: usize = 64;

#[derive(Debug, Clone)]
pub enum RoutingEvent {
    ConfigUpdated { model_name: String },
    ConfigRemoved { model_name: String },
    TargetAdded { model_name: String, version_id: Uuid },
    TargetRemoved { model_name: String, version_id: Uuid },
    WeightsAdjusted { model_name: String },
}

struct RouterInner {
    db_pool: DatabasePool,
    cache: RoutingCache,
    local_configs: RwLock<HashMap<String, CachedConfig>>,
    event_tx: broadcast::Sender<RoutingEvent>,
    _event_rx: broadcast::Receiver<RoutingEvent>,
}

#[derive(Clone)]
struct CachedConfig {
    config: RoutingConfig,
    strategy_instance: Arc<Box<dyn RoutingStrategy>>,
    rules: Option<Value>,
}

impl std::fmt::Debug for CachedConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CachedConfig")
            .field("config", &self.config)
            .field("strategy", &"<RoutingStrategy>")
            .field("rules", &self.rules)
            .finish()
    }
}

#[derive(Clone)]
pub struct TrafficRouter {
    inner: Arc<RouterInner>,
    default_strategy: StrategyType,
}

impl TrafficRouter {
    pub fn new(
        db_pool: DatabasePool,
        redis_client: db::RedisClient,
        default_strategy: StrategyType,
    ) -> Self {
        let cache = RoutingCache::new(redis_client);
        let (tx, rx) = broadcast::channel(BROADCAST_CHANNEL_CAPACITY);

        let inner = RouterInner {
            db_pool,
            cache,
            local_configs: RwLock::new(HashMap::with_capacity(LOCAL_CAPACITY_HINT)),
            event_tx: tx,
            _event_rx: rx,
        };

        Self {
            inner: Arc::new(inner),
            default_strategy,
        }
    }

    pub fn subscribe(&self) -> broadcast::Receiver<RoutingEvent> {
        self.inner.event_tx.subscribe()
    }

    fn emit_event(&self, event: RoutingEvent) {
        if let Err(e) = self.inner.event_tx.send(event.clone()) {
            warn!("Failed to broadcast routing event: {}", e);
        } else {
            debug!("Routing event broadcasted: {:?}", event);
        }
    }

    fn strategy_for_config(
        &self,
        config: &RoutingConfig,
    ) -> (Box<dyn RoutingStrategy>, Option<Value>) {
        let strategy_type = config.strategy;

        let cache_for_getter = self.inner.cache.clone();
        let cache_for_setter = self.inner.cache.clone();

        let getter = Arc::new(move |model_name: &str, user_id: &str| -> Option<String> {
            let cache = cache_for_getter.clone();
            let model = model_name.to_string();
            let user = user_id.to_string();
            let handle = tokio::runtime::Handle::try_current().ok()?;
            handle.block_on(async move {
                cache.get_user_assignment(&model, &user).await.ok().flatten()
            })
        });

        let setter = Arc::new(
            move |model_name: &str, user_id: &str, version_id: &str, ttl: u64| {
                let cache = cache_for_setter.clone();
                let model = model_name.to_string();
                let user = user_id.to_string();
                let version = version_id.to_string();
                if let Ok(_handle) = tokio::runtime::Handle::try_current() {
                    tokio::spawn(async move {
                        let _ = cache.set_user_assignment(&model, &user, &version, ttl).await;
                    });
                }
            },
        );

        let strategy = create_strategy(strategy_type, Some(getter), Some(setter));
        (strategy, None)
    }

    fn strategy_type_from_str(s: &str) -> Result<StrategyType, AppError> {
        match s {
            "user_hash" | "UserHash" => Ok(StrategyType::UserHash),
            "region" | "Region" => Ok(StrategyType::Region),
            "random" | "Random" => Ok(StrategyType::Random),
            "round_robin" | "RoundRobin" => Ok(StrategyType::RoundRobin),
            "experiment" | "Experiment" => Ok(StrategyType::Experiment),
            other => Err(AppError::Config(format!(
                "Unknown routing strategy: {}",
                other
            ))),
        }
    }

    fn build_config_from_rule(
        &self,
        rule: &RoutingRule,
    ) -> Result<(CachedConfig, Value), AppError> {
        let strategy_type = Self::strategy_type_from_str(&rule.strategy)
            .unwrap_or(self.default_strategy);

        let config_json = rule.config.clone().unwrap_or_else(|| {
            Value::Object(serde_json::Map::new())
        });

        let targets = config_json
            .get("targets")
            .and_then(|v| serde_json::from_value::<Vec<RouteTarget>>(v.clone()).ok())
            .unwrap_or_default();

        let experiment_id = config_json
            .get("experiment_id")
            .and_then(|v| v.as_str())
            .and_then(|s| Uuid::parse_str(s).ok());

        let routing_config = RoutingConfig {
            strategy: strategy_type,
            targets,
            experiment_id,
        };

        let (strategy_instance, _) = self.strategy_for_config(&routing_config);

        let rules_config = config_json
            .get("rules")
            .cloned()
            .unwrap_or(Value::Null);

        let cached = CachedConfig {
            config: routing_config,
            strategy_instance: Arc::new(strategy_instance),
            rules: if rules_config.is_null() {
                None
            } else {
                Some(rules_config.clone())
            },
        };

        Ok((cached, rules_config))
    }

    pub async fn load_all_configs(&self) -> Result<(), AppError> {
        info!("Loading all routing configurations from database");

        let db_pool = self.inner.db_pool.clone();

        let rules = sqlx::query_as::<_, RoutingRule>(
            "SELECT id, model_name, strategy, config, created_at, updated_at FROM routing_rules"
        )
        .fetch_all(db_pool.inner())
        .await
        .map_err(|e| AppError::Database(format!("Failed to load routing rules: {}", e)))?;

        let mut local = self.inner.local_configs.write();
        local.clear();

        for rule in &rules {
            match self.build_config_from_rule(rule) {
                Ok((cached, _)) => {
                    info!("Loaded routing config for model: {}", rule.model_name);
                    local.insert(rule.model_name.clone(), cached);
                }
                Err(e) => {
                    error!(
                        "Failed to build routing config for model {}: {}",
                        rule.model_name, e
                    );
                }
            }
        }

        let count = local.len();
        drop(local);
        info!("Loaded {} routing configurations", count);
        Ok(())
    }

    pub async fn get_config(&self, model_name: &str) -> Result<RoutingConfig, AppError> {
        debug!("Getting routing config for model: {}", model_name);

        {
            let local = self.inner.local_configs.read();
            if let Some(cached) = local.get(model_name) {
                return Ok(cached.config.clone());
            }
        }

        debug!("Local cache miss for model {}, checking Redis", model_name);
        if let Some(config) = self.inner.cache.get_routing_config(model_name).await? {
            {
                let mut local = self.inner.local_configs.write();
                if !local.contains_key(model_name) {
                    let (strategy_instance, rules) = self.strategy_for_config(&config);
                    let cached = CachedConfig {
                        config: config.clone(),
                        strategy_instance: Arc::new(strategy_instance),
                        rules,
                    };
                    local.insert(model_name.to_string(), cached);
                }
            }
            return Ok(config);
        }

        debug!("Redis cache miss for model {}, checking DB", model_name);
        let rule = sqlx::query_as::<_, RoutingRule>(
            "SELECT id, model_name, strategy, config, created_at, updated_at FROM routing_rules WHERE model_name = $1"
        )
        .bind(model_name)
        .fetch_optional(self.inner.db_pool.inner())
        .await
        .map_err(|e| AppError::Database(format!("Failed to fetch routing rule: {}", e)))?;

        let rule = rule.ok_or_else(|| {
            AppError::ModelNotFound(format!("No routing configuration found for model: {}", model_name))
        })?;

        let (cached, _rules_config) = self.build_config_from_rule(&rule)?;
        let config = cached.config.clone();

        {
            let mut local = self.inner.local_configs.write();
            local.insert(model_name.to_string(), cached);
        }

        let _ = self
            .inner
            .cache
            .set_routing_config(model_name, &config, None)
            .await;

        Ok(config)
    }

    pub async fn update_config(
        &self,
        model_name: &str,
        config: RoutingConfig,
    ) -> Result<(), AppError> {
        info!("Updating routing config for model: {}", model_name);

        let strategy_str = match config.strategy {
            StrategyType::UserHash => "user_hash",
            StrategyType::Region => "region",
            StrategyType::Random => "random",
            StrategyType::RoundRobin => "round_robin",
            StrategyType::Experiment => "experiment",
        };

        let mut config_obj = serde_json::Map::new();
        config_obj.insert(
            "targets".to_string(),
            serde_json::to_value(&config.targets)?,
        );
        if let Some(exp_id) = config.experiment_id {
            config_obj.insert(
                "experiment_id".to_string(),
                Value::String(exp_id.to_string()),
            );
        }
        let config_value = Value::Object(config_obj);

        let result = sqlx::query(
            "INSERT INTO routing_rules (id, model_name, strategy, config, created_at, updated_at)
             VALUES (gen_random_uuid(), $1, $2, $3, NOW(), NOW())
             ON CONFLICT (model_name) DO UPDATE SET
                 strategy = EXCLUDED.strategy,
                 config = EXCLUDED.config,
                 updated_at = NOW()"
        )
        .bind(model_name)
        .bind(strategy_str)
        .bind(&config_value)
        .execute(self.inner.db_pool.inner())
        .await
        .map_err(|e| AppError::Database(format!("Failed to upsert routing rule: {}", e)))?;

        debug!("DB upsert affected rows: {}", result.rows_affected());

        {
            let mut local = self.inner.local_configs.write();
            let (strategy_instance, rules) = self.strategy_for_config(&config);
            let cached = CachedConfig {
                config: config.clone(),
                strategy_instance: Arc::new(strategy_instance),
                rules,
            };
            local.insert(model_name.to_string(), cached);
        }

        self.inner
            .cache
            .invalidate_routing_config(model_name)
            .await?;

        let _ = self
            .inner
            .cache
            .set_routing_config(model_name, &config, None)
            .await;

        self.emit_event(RoutingEvent::ConfigUpdated {
            model_name: model_name.to_string(),
        });

        info!("Routing config updated successfully for model: {}", model_name);
        Ok(())
    }

    pub async fn route_request(
        &self,
        request: &InferenceRequest,
    ) -> Result<RouteTarget, AppError> {
        let model_name = &request.model_name;
        debug!(
            "Routing request {} for model {}",
            request.request_id, model_name
        );

        let cached = {
            let local = self.inner.local_configs.read();
            local.get(model_name).cloned()
        };

        let cached = match cached {
            Some(c) => c,
            None => {
                let _ = self.get_config(model_name).await?;
                let local = self.inner.local_configs.read();
                local
                    .get(model_name)
                    .cloned()
                    .ok_or_else(|| AppError::RoutingError("Failed to load routing config".into()))?
            }
        };

        if cached.config.targets.is_empty() {
            return Err(AppError::RoutingError(format!(
                "No routing targets configured for model: {}",
                model_name
            )));
        }

        let target = cached
            .strategy_instance
            .route(request, &cached.config.targets, cached.rules.as_ref())
            .await?;

        debug!(
            "Request {} routed to version {} (model {})",
            request.request_id, target.model_version_id, model_name
        );

        Ok(target)
    }

    pub async fn add_target(
        &self,
        model_name: &str,
        target: RouteTarget,
    ) -> Result<(), AppError> {
        info!(
            "Adding target {} to model {}",
            target.model_version_id, model_name
        );

        let mut config = self.get_config(model_name).await?;

        if let Some(existing) = config
            .targets
            .iter_mut()
            .find(|t| t.model_version_id == target.model_version_id)
        {
            existing.weight = target.weight;
            existing.is_primary = target.is_primary;
            info!(
                "Target {} already exists for model {}, updating weight and primary flag",
                target.model_version_id, model_name
            );
        } else {
            if target.is_primary {
                for t in config.targets.iter_mut() {
                    t.is_primary = false;
                }
            }
            config.targets.push(target.clone());
        }

        self.update_config(model_name, config).await?;

        self.emit_event(RoutingEvent::TargetAdded {
            model_name: model_name.to_string(),
            version_id: target.model_version_id,
        });

        Ok(())
    }

    pub async fn remove_target(
        &self,
        model_name: &str,
        version_id: Uuid,
    ) -> Result<(), AppError> {
        info!("Removing target {} from model {}", version_id, model_name);

        let mut config = self.get_config(model_name).await?;
        let original_len = config.targets.len();

        config
            .targets
            .retain(|t| t.model_version_id != version_id);

        if config.targets.len() == original_len {
            warn!(
                "Target {} not found in routing config for model {}",
                version_id, model_name
            );
            return Err(AppError::ModelVersionNotFound(format!(
                "Route target version {} not found for model {}",
                version_id, model_name
            )));
        }

        if config.targets.is_empty() {
            warn!(
                "All targets removed for model {}, keeping empty config",
                model_name
            );
        } else if !config.targets.iter().any(|t| t.is_primary) {
            config.targets[0].is_primary = true;
        }

        self.update_config(model_name, config).await?;

        self.emit_event(RoutingEvent::TargetRemoved {
            model_name: model_name.to_string(),
            version_id,
        });

        Ok(())
    }

    pub async fn adjust_weights(
        &self,
        model_name: &str,
        weights: HashMap<Uuid, u32>,
    ) -> Result<(), AppError> {
        info!("Adjusting weights for model {}: {:?}", model_name, weights);

        let mut config = self.get_config(model_name).await?;
        let mut adjusted = false;

        for target in config.targets.iter_mut() {
            if let Some(&new_weight) = weights.get(&target.model_version_id) {
                target.weight = new_weight;
                adjusted = true;
            }
        }

        if !adjusted {
            return Err(AppError::RoutingError(format!(
                "No matching targets found for weight adjustment in model {}",
                model_name
            )));
        }

        let total: u32 = config.targets.iter().map(|t| t.weight).sum();
        if total == 0 {
            return Err(AppError::RoutingError(
                "Total weight cannot be zero after adjustment".to_string(),
            ));
        }

        self.update_config(model_name, config).await?;

        self.emit_event(RoutingEvent::WeightsAdjusted {
            model_name: model_name.to_string(),
        });

        info!("Weights adjusted successfully for model {}", model_name);
        Ok(())
    }

    pub async fn list_models(&self) -> Vec<String> {
        let local = self.inner.local_configs.read();
        local.keys().cloned().collect()
    }

    pub async fn get_targets(&self, model_name: &str) -> Result<Vec<RouteTarget>, AppError> {
        let config = self.get_config(model_name).await?;
        Ok(config.targets)
    }

    pub fn cache(&self) -> &RoutingCache {
        &self.inner.cache
    }

    pub fn db_pool(&self) -> &DatabasePool {
        &self.inner.db_pool
    }
}

impl std::fmt::Debug for TrafficRouter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TrafficRouter")
            .field("default_strategy", &self.default_strategy)
            .field(
                "loaded_models",
                &self.inner.local_configs.read().len(),
            )
            .finish()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResolvedRoute {
    pub model_name: String,
    pub model_version_id: Uuid,
    pub version: String,
    pub backend_address: String,
    pub gpu_id: Option<String>,
    pub route_target: RouteTarget,
    pub is_fallback: bool,
    pub latency_ms: Option<u64>,
}

struct RouterHealthStats {
    latency_samples: DashMap<Uuid, Vec<u64>>,
    failure_counts: DashMap<Uuid, u64>,
    last_weight_adjust: DashMap<String, Instant>,
}

impl RouterHealthStats {
    fn new() -> Self {
        Self {
            latency_samples: DashMap::new(),
            failure_counts: DashMap::new(),
            last_weight_adjust: DashMap::new(),
        }
    }

    fn record_latency(&self, version_id: Uuid, latency_ms: u64) {
        let mut samples = self
            .latency_samples
            .entry(version_id)
            .or_insert_with(|| Vec::with_capacity(100));
        samples.push(latency_ms);
        if samples.len() > 100 {
            samples.remove(0);
        }
    }

    fn record_failure(&self, version_id: Uuid) {
        self.failure_counts
            .entry(version_id)
            .and_modify(|c| *c += 1)
            .or_insert(1);
    }

    fn get_avg_latency(&self, version_id: Uuid) -> Option<f64> {
        let samples = self.latency_samples.get(&version_id)?;
        if samples.is_empty() {
            return None;
        }
        let sum: u64 = samples.iter().sum();
        Some(sum as f64 / samples.len() as f64)
    }

    fn get_failure_count(&self, version_id: Uuid) -> u64 {
        self.failure_counts.get(&version_id).map(|c| *c.value()).unwrap_or(0)
    }

    fn reset_failures(&self, version_id: Uuid) {
        self.failure_counts.insert(version_id, 0);
    }

    fn should_adjust_weights(&self, model_name: &str, min_interval_secs: u64) -> bool {
        let now = Instant::now();
        let last = self
            .last_weight_adjust
            .get(model_name)
            .map(|e| *e.value())
            .unwrap_or_else(|| now - Duration::from_secs(min_interval_secs + 1));
        now.duration_since(last) >= Duration::from_secs(min_interval_secs)
    }

    fn mark_weight_adjusted(&self, model_name: &str) {
        self.last_weight_adjust
            .insert(model_name.to_string(), Instant::now());
    }
}

struct TrafficRouterExt {
    health_stats: RouterHealthStats,
    client_manager: Option<Arc<crate::grpc::ClientManager>>,
    registry_client: Option<Arc<crate::grpc::RegistryClient>>,
    version_to_backend: DashMap<Uuid, crate::grpc::BackendTarget>,
    fallback_stack: DashMap<Uuid, std::sync::atomic::AtomicU64>,
    dynamic_weight_enabled: bool,
    fallback_enabled: bool,
    min_weight_adjust_interval_secs: u64,
    health_check_enabled: bool,
}

#[derive(Clone)]
pub struct RouterService {
    router: TrafficRouter,
    ext: Arc<TrafficRouterExt>,
}

impl RouterService {
    pub fn new(
        db_pool: DatabasePool,
        redis_client: db::RedisClient,
        default_strategy: StrategyType,
    ) -> Self {
        Self::with_options(
            TrafficRouter::new(db_pool, redis_client, default_strategy),
            None,
            None,
        )
    }

    pub fn with_options(
        router: TrafficRouter,
        client_manager: Option<Arc<crate::grpc::ClientManager>>,
        registry_client: Option<Arc<crate::grpc::RegistryClient>>,
    ) -> Self {
        Self {
            router,
            ext: Arc::new(TrafficRouterExt {
                health_stats: RouterHealthStats::new(),
                client_manager,
                registry_client,
                version_to_backend: DashMap::new(),
                fallback_stack: DashMap::new(),
                dynamic_weight_enabled: true,
                fallback_enabled: true,
                min_weight_adjust_interval_secs: 60,
                health_check_enabled: true,
            }),
        }
    }

    pub fn with_client_manager(mut self, client_manager: Arc<crate::grpc::ClientManager>) -> Self {
        let ext = self.ext.clone();
        let new_ext = TrafficRouterExt {
            health_stats: RouterHealthStats::new(),
            client_manager: Some(client_manager),
            registry_client: ext.registry_client.clone(),
            version_to_backend: DashMap::new(),
            fallback_stack: DashMap::new(),
            dynamic_weight_enabled: ext.dynamic_weight_enabled,
            fallback_enabled: ext.fallback_enabled,
            min_weight_adjust_interval_secs: ext.min_weight_adjust_interval_secs,
            health_check_enabled: ext.health_check_enabled,
        };
        self.ext = Arc::new(new_ext);
        self
    }

    pub fn with_registry_client(mut self, registry_client: Arc<crate::grpc::RegistryClient>) -> Self {
        let ext = self.ext.clone();
        let new_ext = TrafficRouterExt {
            health_stats: RouterHealthStats::new(),
            client_manager: ext.client_manager.clone(),
            registry_client: Some(registry_client),
            version_to_backend: DashMap::new(),
            fallback_stack: DashMap::new(),
            dynamic_weight_enabled: ext.dynamic_weight_enabled,
            fallback_enabled: ext.fallback_enabled,
            min_weight_adjust_interval_secs: ext.min_weight_adjust_interval_secs,
            health_check_enabled: ext.health_check_enabled,
        };
        self.ext = Arc::new(new_ext);
        self
    }

    pub fn with_dynamic_weight(mut self, enabled: bool) -> Self {
        let ext = self.ext.clone();
        let new_ext = TrafficRouterExt {
            dynamic_weight_enabled: enabled,
            ..(*ext).clone()
        };
        self.ext = Arc::new(new_ext);
        self
    }

    pub fn with_fallback(mut self, enabled: bool) -> Self {
        let ext = self.ext.clone();
        let new_ext = TrafficRouterExt {
            fallback_enabled: enabled,
            ..(*ext).clone()
        };
        self.ext = Arc::new(new_ext);
        self
    }

    pub fn router(&self) -> &TrafficRouter {
        &self.router
    }

    pub fn register_backend(&self, target: crate::grpc::BackendTarget) {
        self.ext
            .version_to_backend
            .insert(target.model_version_id, target);
    }

    pub fn unregister_backend(&self, model_version_id: Uuid) {
        self.ext.version_to_backend.remove(&model_version_id);
    }

    pub fn get_backend(&self, model_version_id: Uuid) -> Option<crate::grpc::BackendTarget> {
        self.ext
            .version_to_backend
            .get(&model_version_id)
            .map(|e| e.value().clone())
    }

    async fn resolve_version_by_strategy(
        &self,
        model_name: &str,
        req_context: &InferenceRequest,
    ) -> Result<RouteTarget, AppError> {
        let target = self.router.route_request(req_context).await?;

        let is_healthy = self
            .ext
            .client_manager
            .as_ref()
            .map(|cm| cm.is_healthy(target.model_version_id))
            .unwrap_or(true);

        if !is_healthy && self.ext.fallback_enabled {
            warn!(
                "Target version {} for model {} is unhealthy, attempting fallback",
                target.model_version_id, model_name
            );

            let fallback = self
                .find_fallback_target(model_name, target.model_version_id)
                .await;

            if let Some(fb) = fallback {
                info!(
                    "Fallback: using version {} instead of {} for model {}",
                    fb.model_version_id, target.model_version_id, model_name
                );
                return Ok(fb);
            }
        }

        Ok(target)
    }

    async fn find_fallback_target(
        &self,
        model_name: &str,
        exclude_version: Uuid,
    ) -> Option<RouteTarget> {
        let config = self.router.get_config(model_name).await.ok()?;

        let primaries: Vec<&RouteTarget> = config
            .targets
            .iter()
            .filter(|t| t.model_version_id != exclude_version)
            .filter(|t| {
                self.ext
                    .client_manager
                    .as_ref()
                    .map(|cm| cm.is_healthy(t.model_version_id))
                    .unwrap_or(true)
            })
            .collect();

        let primary = primaries.iter().find(|t| t.is_primary).copied();
        if let Some(p) = primary {
            return Some(p.clone());
        }

        primaries.first().map(|t| (*t).clone())
    }

    fn resolve_backend_target(
        &self,
        _model_name: &str,
        version_id: Uuid,
    ) -> Result<crate::grpc::BackendTarget, AppError> {
        if let Some(backend) = self.ext.version_to_backend.get(&version_id) {
            return Ok(backend.value().clone());
        }

        if let Some(cm) = self.ext.client_manager.as_ref() {
            if let Some(node) = cm.get_node_info(version_id) {
                return Ok(crate::grpc::BackendTarget {
                    model_version_id: version_id,
                    version: version_id.to_string(),
                    backend_address: node.address.clone(),
                    gpu_id: node.gpu_id.clone(),
                    healthy: node.healthy,
                });
            }
        }

        Err(AppError::RoutingError(format!(
            "No backend target found for version {}",
            version_id
        )))
    }

    pub async fn route(&self, request: &InferenceRequest) -> Result<ResolvedRoute, AppError> {
        let model_name = &request.model_name;
        let user_id = request.user_id.as_deref().unwrap_or("unknown");

        let cache_key = if request.user_id.is_some() {
            Some(crate::cache::RouteCacheKey::cache_key(
                model_name,
                user_id,
            ))
        } else {
            None
        };

        if let Some(key) = &cache_key {
            if let Some(cached_target) = self
                .router
                .cache()
                .get_cached_route(key)
                .await
                .ok()
                .flatten()
            {
                if let Ok(backend) = self.resolve_backend_target(model_name, cached_target.model_version_id) {
                    if backend.healthy || !self.ext.health_check_enabled {
                        return Ok(ResolvedRoute {
                            model_name: model_name.clone(),
                            model_version_id: cached_target.model_version_id,
                            version: backend.version.clone(),
                            backend_address: backend.backend_address,
                            gpu_id: backend.gpu_id,
                            route_target: cached_target,
                            is_fallback: false,
                            latency_ms: None,
                        });
                    }
                }
            }
        }

        let route_target = self
            .resolve_version_by_strategy(model_name, request).await?;
        let is_fallback = {
            let fallback_count = self
                .ext
                .fallback_stack
                .get(&route_target.model_version_id)
                .map(|e| e.value().load(Ordering::Relaxed))
                .unwrap_or(0);
            fallback_count > 0
        };

        let backend = self.resolve_backend_target(model_name, route_target.model_version_id)?;

        let resolved = ResolvedRoute {
            model_name: model_name.clone(),
            model_version_id: route_target.model_version_id,
            version: backend.version.clone(),
            backend_address: backend.backend_address,
            gpu_id: backend.gpu_id,
            route_target: route_target.clone(),
            is_fallback,
            latency_ms: None,
        };

        if let Some(key) = &cache_key {
            let _ = self
                .router
                .cache()
                .set_cached_route(key, route_target.clone(), Some(30))
                .await;
        }

        Ok(resolved)
    }

    pub fn record_route_result(
        &self,
        model_version_id: Uuid,
        success: bool,
        latency_ms: u64,
    ) {
        self.ext.health_stats.record_latency(model_version_id, latency_ms);

        if !success {
            self.ext.health_stats.record_failure(model_version_id);
            self.ext
                .fallback_stack
                .entry(model_version_id)
                .or_insert_with(|| std::sync::atomic::AtomicU64::new(0))
                .fetch_add(1, Ordering::Relaxed);
        } else {
            self.ext.health_stats.reset_failures(model_version_id);
            self.ext
                .fallback_stack
                .entry(model_version_id)
                .or_insert_with(|| std::sync::atomic::AtomicU64::new(0))
                .store(0, Ordering::Relaxed);
        }

        if let Some(cm) = self.ext.client_manager.as_ref() {
            cm.record_request_metrics(model_version_id, success, latency_ms);
        }
    }

    pub async fn dynamic_weight_adjust(&self, model_name: &str) -> Result<bool, AppError> {
        if !self.ext.dynamic_weight_enabled {
            return Ok(false);
        }

        if !self
            .ext
            .health_stats
            .should_adjust_weights(model_name, self.ext.min_weight_adjust_interval_secs)
        {
            return Ok(false);
        }

        let config = self.router.get_config(model_name).await?;
        if config.targets.len() < 2 {
            return Ok(false);
        }

        let mut latencies: HashMap<Uuid, f64> = HashMap::new();
        let mut max_latency: f64 = 0.0;

        for target in &config.targets {
            if let Some(avg) = self.ext.health_stats.get_avg_latency(target.model_version_id) {
                latencies.insert(target.model_version_id, avg);
                max_latency = max_latency.max(avg);
            }
        }

        if latencies.len() < 2 {
            return Ok(false);
        }

        let mut new_weights: HashMap<Uuid, u32> = config
            .targets
            .iter()
            .map(|t| {
                let base_weight = t.weight as f64;
                let latency = latencies.get(&t.model_version_id).copied().unwrap_or(max_latency);
                let latency_factor = if max_latency > 0.0 {
                    (max_latency / latency.max(1.0)).sqrt()
                } else {
                    1.0
                };

                let failures = self.ext.health_stats.get_failure_count(t.model_version_id);
                let failure_penalty = (1.0 - (failures as f64 / 100.0)).max(0.1);
                let adjusted = base_weight * latency_factor * failure_penalty;
                (t.model_version_id, adjusted.max(1.0) as u32)
            })
            .collect();

        let total_original: u32 = config.targets.iter().map(|t| t.weight).sum();
        let total_new: u32 = new_weights.values().sum();

        if total_new == 0 {
            return Ok(false);
        }

        let normalized_weights: HashMap<Uuid, u32> = new_weights
            .iter()
            .map(|(id, w)| {
                let normalized = (*w as u64 * total_original as u64) / total_new as u64;
                (*id, normalized.max(1) as u32)
            })
            .collect();

        let changed = config.targets.iter().any(|t| {
            normalized_weights
                .get(&t.model_version_id)
                .map(|&w| w != t.weight)
                .unwrap_or(false)
        });

        if !changed {
            self.ext.health_stats.mark_weight_adjusted(model_name);
            return Ok(false);
        }

        info!(
            "Dynamic weight adjustment for model {}: {:?} -> {:?}",
            model_name,
            config
                .targets
                .iter()
                .map(|t| (t.model_version_id, t.weight))
                .collect::<Vec<_>>(),
            normalized_weights
        );

        self.router.adjust_weights(model_name, normalized_weights).await?;
        self.ext.health_stats.mark_weight_adjusted(model_name);

        Ok(true)
    }

    pub fn get_version_stats(&self, model_version_id: Uuid) -> (Option<f64>, u64) {
        (
            self.ext.health_stats.get_avg_latency(model_version_id),
            self.ext.health_stats.get_failure_count(model_version_id),
        )
    }

    pub async fn perform_health_check_all(&self) -> HashMap<Uuid, bool> {
        if let Some(cm) = self.ext.client_manager.as_ref() {
            cm.perform_health_checks().await
        } else {
            HashMap::new()
        }
    }

    pub async fn invalidate_routes_for_version(&self, model_name: &str) -> Result<(), AppError> {
        self.router
            .cache()
            .invalidate_model_routes(model_name)
            .await
    }

    pub fn list_registered_backends(&self) -> Vec<(Uuid, crate::grpc::BackendTarget)> {
        self.ext
            .version_to_backend
            .iter()
            .map(|e| (*e.key(), e.value().clone()))
            .collect()
    }
}

impl TrafficRouterExt {
    fn clone(&self) -> TrafficRouterExt {
        let fallback_stack = DashMap::new();
        for entry in self.fallback_stack.iter() {
            fallback_stack.insert(*entry.key(), std::sync::atomic::AtomicU64::new(entry.value().load(Ordering::Relaxed)));
        }
        TrafficRouterExt {
            health_stats: RouterHealthStats::new(),
            client_manager: self.client_manager.clone(),
            registry_client: self.registry_client.clone(),
            version_to_backend: self.version_to_backend.clone(),
            fallback_stack,
            dynamic_weight_enabled: self.dynamic_weight_enabled,
            fallback_enabled: self.fallback_enabled,
            min_weight_adjust_interval_secs: self.min_weight_adjust_interval_secs,
            health_check_enabled: self.health_check_enabled,
        }
    }
}

impl std::fmt::Debug for RouterService {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RouterService")
            .field("router", &self.router)
            .field(
                "registered_backends",
                &self.ext.version_to_backend.len(),
            )
            .field(
                "dynamic_weight_enabled",
                &self.ext.dynamic_weight_enabled,
            )
            .field("fallback_enabled", &self.ext.fallback_enabled)
            .finish()
    }
}

