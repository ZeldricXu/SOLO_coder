use crate::types::{
    AnomalyRecord, AppError, AppResult, DataQualityConfig, QualityCheckResult, QualityRule,
    QualityRuleType, SeverityLevel, generate_id, now_utc,
};
use async_trait::async_trait;
use dashmap::DashMap;
use job_scheduler::{Job, JobScheduler};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Mutex;

#[async_trait]
pub trait RuleEvaluator: Send + Sync {
    async fn evaluate(
        &self,
        rule: &QualityRule,
        dataset: &[HashMap<String, serde_json::Value>],
    ) -> AppResult<QualityCheckResult>;
}

pub struct CompletenessEvaluator;

impl CompletenessEvaluator {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl RuleEvaluator for CompletenessEvaluator {
    async fn evaluate(
        &self,
        rule: &QualityRule,
        dataset: &[HashMap<String, serde_json::Value>],
    ) -> AppResult<QualityCheckResult> {
        let started_at = now_utc();
        let start_instant = Instant::now();

        let target_field = rule.expression.clone();
        let total_count = dataset.len() as u64;
        let null_count = dataset
            .iter()
            .filter(|row| match row.get(&target_field) {
                Some(v) => v.is_null(),
                None => true,
            })
            .count() as u64;

        let actual_value = if total_count > 0 {
            1.0 - (null_count as f64 / total_count as f64)
        } else {
            1.0
        };

        let passed = actual_value >= rule.threshold;

        let sample_data: Vec<serde_json::Value> = dataset
            .iter()
            .filter(|row| match row.get(&target_field) {
                Some(v) => v.is_null(),
                None => true,
            })
            .take(10)
            .map(|row| serde_json::json!(row))
            .collect();

        Ok(QualityCheckResult {
            check_id: generate_id("chk"),
            rule_id: rule.rule_id.clone(),
            dataset: rule.dataset.clone(),
            passed,
            actual_value,
            expected_value: rule.threshold,
            anomaly_count: null_count,
            sample_data,
            started_at,
            completed_at: now_utc(),
        })
    }
}

impl Default for CompletenessEvaluator {
    fn default() -> Self {
        Self::new()
    }
}

pub struct UniquenessEvaluator;

impl UniquenessEvaluator {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl RuleEvaluator for UniquenessEvaluator {
    async fn evaluate(
        &self,
        rule: &QualityRule,
        dataset: &[HashMap<String, serde_json::Value>],
    ) -> AppResult<QualityCheckResult> {
        let started_at = now_utc();
        let target_field = rule.expression.clone();

        let total_count = dataset.len() as u64;
        let mut unique_values = std::collections::HashSet::new();
        let mut duplicate_samples = Vec::new();

        for row in dataset {
            if let Some(value) = row.get(&target_field) {
                let key = format!("{:?}", value);
                if !unique_values.insert(key) {
                    if duplicate_samples.len() < 10 {
                        duplicate_samples.push(serde_json::json!(row));
                    }
                }
            }
        }

        let unique_count = unique_values.len() as u64;
        let actual_value = if total_count > 0 {
            unique_count as f64 / total_count as f64
        } else {
            1.0
        };

        let passed = actual_value >= rule.threshold;
        let anomaly_count = total_count - unique_count;

        Ok(QualityCheckResult {
            check_id: generate_id("chk"),
            rule_id: rule.rule_id.clone(),
            dataset: rule.dataset.clone(),
            passed,
            actual_value,
            expected_value: rule.threshold,
            anomaly_count,
            sample_data: duplicate_samples,
            started_at,
            completed_at: now_utc(),
        })
    }
}

impl Default for UniquenessEvaluator {
    fn default() -> Self {
        Self::new()
    }
}

pub struct AccuracyEvaluator;

impl AccuracyEvaluator {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl RuleEvaluator for AccuracyEvaluator {
    async fn evaluate(
        &self,
        rule: &QualityRule,
        dataset: &[HashMap<String, serde_json::Value>],
    ) -> AppResult<QualityCheckResult> {
        let started_at = now_utc();
        let total_count = dataset.len() as u64;

        let re = regex::Regex::new(&rule.expression).map_err(|e| {
            AppError::DataQualityError(format!("正则表达式无效: {}", e))
        })?;

        let mut valid_count = 0u64;
        let mut invalid_samples = Vec::new();

        for row in dataset {
            let row_str = serde_json::to_string(row).unwrap_or_default();
            if re.is_match(&row_str) {
                valid_count += 1;
            } else if invalid_samples.len() < 10 {
                invalid_samples.push(serde_json::json!(row));
            }
        }

        let actual_value = if total_count > 0 {
            valid_count as f64 / total_count as f64
        } else {
            1.0
        };

        let passed = actual_value >= rule.threshold;
        let anomaly_count = total_count - valid_count;

        Ok(QualityCheckResult {
            check_id: generate_id("chk"),
            rule_id: rule.rule_id.clone(),
            dataset: rule.dataset.clone(),
            passed,
            actual_value,
            expected_value: rule.threshold,
            anomaly_count,
            sample_data: invalid_samples,
            started_at,
            completed_at: now_utc(),
        })
    }
}

impl Default for AccuracyEvaluator {
    fn default() -> Self {
        Self::new()
    }
}

pub struct TimelinessEvaluator;

impl TimelinessEvaluator {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl RuleEvaluator for TimelinessEvaluator {
    async fn evaluate(
        &self,
        rule: &QualityRule,
        dataset: &[HashMap<String, serde_json::Value>],
    ) -> AppResult<QualityCheckResult> {
        let started_at = now_utc();
        let total_count = dataset.len() as u64;
        let threshold_seconds = rule.threshold;

        let mut late_count = 0u64;
        let mut late_samples = Vec::new();

        for row in dataset {
            if let Some(ts_value) = row.get(&rule.expression) {
                if let Some(ts_str) = ts_value.as_str() {
                    if let Ok(parsed_ts) = chrono::DateTime::parse_from_rfc3339(ts_str) {
                        let latency = now_utc().signed_duration_since(parsed_ts.with_timezone(&chrono::Utc));
                        if latency.num_seconds() as f64 > threshold_seconds {
                            late_count += 1;
                            if late_samples.len() < 10 {
                                late_samples.push(serde_json::json!(row));
                            }
                        }
                    }
                }
            }
        }

        let actual_value = if total_count > 0 {
            1.0 - (late_count as f64 / total_count as f64)
        } else {
            1.0
        };

        let passed = actual_value >= rule.threshold;

        Ok(QualityCheckResult {
            check_id: generate_id("chk"),
            rule_id: rule.rule_id.clone(),
            dataset: rule.dataset.clone(),
            passed,
            actual_value,
            expected_value: rule.threshold,
            anomaly_count: late_count,
            sample_data: late_samples,
            started_at,
            completed_at: now_utc(),
        })
    }
}

impl Default for TimelinessEvaluator {
    fn default() -> Self {
        Self::new()
    }
}

pub struct CustomEvaluator;

impl CustomEvaluator {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl RuleEvaluator for CustomEvaluator {
    async fn evaluate(
        &self,
        rule: &QualityRule,
        dataset: &[HashMap<String, serde_json::Value>],
    ) -> AppResult<QualityCheckResult> {
        let started_at = now_utc();
        let total_count = dataset.len() as u64;
        let mut anomaly_count = 0u64;
        let mut anomaly_samples = Vec::new();

        let expr = rule.expression.clone();

        for (i, row) in dataset.iter().enumerate() {
            let mut passed_row = true;

            for (key, value) in row {
                if let Some(num) = value.as_f64() {
                    if expr.contains(key) {
                        if let Ok(re) = regex::Regex::new(&format!(r"{}\s*>\s*(\d+)", key)) {
                            if let Some(caps) = re.captures(&expr) {
                                if let Ok(threshold) = caps[1].parse::<f64>() {
                                    if num > threshold {
                                        passed_row = false;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if !passed_row {
                anomaly_count += 1;
                if anomaly_samples.len() < 10 {
                    anomaly_samples.push(serde_json::json!(row));
                }
            }
        }

        let actual_value = if total_count > 0 {
            1.0 - (anomaly_count as f64 / total_count as f64)
        } else {
            1.0
        };

        let passed = actual_value >= rule.threshold;

        Ok(QualityCheckResult {
            check_id: generate_id("chk"),
            rule_id: rule.rule_id.clone(),
            dataset: rule.dataset.clone(),
            passed,
            actual_value,
            expected_value: rule.threshold,
            anomaly_count,
            sample_data: anomaly_samples,
            started_at,
            completed_at: now_utc(),
        })
    }
}

impl Default for CustomEvaluator {
    fn default() -> Self {
        Self::new()
    }
}

pub struct EvaluatorRegistry {
    evaluators: DashMap<QualityRuleType, Arc<dyn RuleEvaluator>>,
}

impl EvaluatorRegistry {
    pub fn new() -> Self {
        let registry = Self {
            evaluators: DashMap::new(),
        };

        registry.register(
            QualityRuleType::Completeness,
            Arc::new(CompletenessEvaluator::new()),
        );
        registry.register(
            QualityRuleType::Uniqueness,
            Arc::new(UniquenessEvaluator::new()),
        );
        registry.register(
            QualityRuleType::Accuracy,
            Arc::new(AccuracyEvaluator::new()),
        );
        registry.register(
            QualityRuleType::Timeliness,
            Arc::new(TimelinessEvaluator::new()),
        );
        registry.register(
            QualityRuleType::Validity,
            Arc::new(AccuracyEvaluator::new()),
        );
        registry.register(
            QualityRuleType::Consistency,
            Arc::new(CustomEvaluator::new()),
        );
        registry.register(
            QualityRuleType::Custom,
            Arc::new(CustomEvaluator::new()),
        );

        registry
    }

    pub fn register(&self, rule_type: QualityRuleType, evaluator: Arc<dyn RuleEvaluator>) {
        self.evaluators.insert(rule_type, evaluator);
    }

    pub fn get(&self, rule_type: &QualityRuleType) -> Option<Arc<dyn RuleEvaluator>> {
        self.evaluators.get(rule_type).map(|e| e.clone())
    }
}

impl Default for EvaluatorRegistry {
    fn default() -> Self {
        Self::new()
    }
}

pub struct AnomalyStore {
    anomalies: DashMap<String, Vec<AnomalyRecord>>,
    enabled: bool,
}

impl AnomalyStore {
    pub fn new(enabled: bool) -> Self {
        Self {
            anomalies: DashMap::new(),
            enabled,
        }
    }

    pub async fn store(&self, rule: &QualityRule, result: &QualityCheckResult, dataset: &[HashMap<String, serde_json::Value>]) -> AppResult<Vec<AnomalyRecord>> {
        if !self.enabled {
            return Ok(Vec::new());
        }

        let mut records = Vec::new();

        for (i, row) in dataset.iter().enumerate() {
            if result.sample_data.contains(&serde_json::json!(row)) {
                let record = AnomalyRecord {
                    anomaly_id: generate_id("anom"),
                    rule_id: rule.rule_id.clone(),
                    dataset: rule.dataset.clone(),
                    record_key: format!("row_{}", i),
                    field_name: Some(rule.expression.clone()),
                    expected_value: Some(serde_json::json!(rule.threshold)),
                    actual_value: Some(serde_json::json!(result.actual_value)),
                    severity: rule.severity.clone(),
                    detected_at: now_utc(),
                    resolved: false,
                    resolved_at: None,
                };

                self.anomalies
                    .entry(rule.rule_id.clone())
                    .or_default()
                    .push(record.clone());

                records.push(record);
            }
        }

        Ok(records)
    }

    pub async fn get_anomalies(&self, rule_id: &str) -> Vec<AnomalyRecord> {
        self.anomalies
            .get(rule_id)
            .map(|a| a.clone())
            .unwrap_or_default()
    }

    pub async fn get_all_anomalies(&self) -> Vec<AnomalyRecord> {
        let mut all = Vec::new();
        for entry in self.anomalies.iter() {
            all.extend(entry.value().clone());
        }
        all
    }

    pub async fn resolve_anomaly(&self, anomaly_id: &str) -> AppResult<()> {
        for mut entry in self.anomalies.iter_mut() {
            for record in entry.value_mut().iter_mut() {
                if record.anomaly_id == anomaly_id {
                    record.resolved = true;
                    record.resolved_at = Some(now_utc());
                    return Ok(());
                }
            }
        }

        Err(AppError::NotFound(format!(
            "异常记录不存在: {}",
            anomaly_id
        )))
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }
}

pub struct QualityRuleManager {
    rules: DashMap<String, QualityRule>,
    evaluator_registry: Arc<EvaluatorRegistry>,
    anomaly_store: Arc<AnomalyStore>,
    datasets: DashMap<String, Vec<HashMap<String, serde_json::Value>>>,
    scheduler: Arc<Mutex<JobScheduler>>,
    config: DataQualityConfig,
    job_ids: DashMap<String, uuid::Uuid>,
}

impl QualityRuleManager {
    pub fn new(config: DataQualityConfig) -> Self {
        Self {
            rules: DashMap::new(),
            evaluator_registry: Arc::new(EvaluatorRegistry::new()),
            anomaly_store: Arc::new(AnomalyStore::new(config.anomaly_storage_enabled)),
            datasets: DashMap::new(),
            scheduler: Arc::new(Mutex::new(JobScheduler::new())),
            config,
            job_ids: DashMap::new(),
        }
    }

    pub fn create_rule(
        &self,
        name: &str,
        description: &str,
        rule_type: QualityRuleType,
        dataset: &str,
        expression: &str,
        severity: SeverityLevel,
        threshold: f64,
        schedule: &str,
    ) -> QualityRule {
        let rule = QualityRule {
            rule_id: generate_id("rule"),
            name: name.to_string(),
            description: description.to_string(),
            rule_type,
            dataset: dataset.to_string(),
            expression: expression.to_string(),
            severity,
            threshold,
            schedule: schedule.to_string(),
            enabled: true,
            created_at: now_utc(),
            updated_at: now_utc(),
        };

        self.rules.insert(rule.rule_id.clone(), rule.clone());
        rule
    }

    pub fn get_rule(&self, rule_id: &str) -> Option<QualityRule> {
        self.rules.get(rule_id).map(|r| r.clone())
    }

    pub fn list_rules(&self) -> Vec<QualityRule> {
        self.rules.iter().map(|r| r.clone()).collect()
    }

    pub fn update_rule(&self, rule_id: &str, mut updates: HashMap<String, serde_json::Value>) -> AppResult<QualityRule> {
        let mut rule = self
            .rules
            .get_mut(rule_id)
            .ok_or_else(|| AppError::NotFound(format!("规则不存在: {}", rule_id)))?;

        if let Some(name) = updates.remove("name").and_then(|v| v.as_str().map(|s| s.to_string())) {
            rule.name = name;
        }

        if let Some(description) = updates.remove("description").and_then(|v| v.as_str().map(|s| s.to_string())) {
            rule.description = description;
        }

        if let Some(expression) = updates.remove("expression").and_then(|v| v.as_str().map(|s| s.to_string())) {
            rule.expression = expression;
        }

        if let Some(threshold) = updates.remove("threshold").and_then(|v| v.as_f64()) {
            rule.threshold = threshold;
        }

        if let Some(enabled) = updates.remove("enabled").and_then(|v| v.as_bool()) {
            rule.enabled = enabled;
        }

        rule.updated_at = now_utc();

        Ok(rule.clone())
    }

    pub fn delete_rule(&self, rule_id: &str) -> AppResult<()> {
        self.rules
            .remove(rule_id)
            .ok_or_else(|| AppError::NotFound(format!("规则不存在: {}", rule_id)))?;

        if let Some(job_id) = self.job_ids.remove(rule_id) {
            // Job will be removed on next scheduler tick
        }

        Ok(())
    }

    pub fn set_dataset(&self, name: &str, data: Vec<HashMap<String, serde_json::Value>>) {
        self.datasets.insert(name.to_string(), data);
    }

    pub fn get_dataset(&self, name: &str) -> Option<Vec<HashMap<String, serde_json::Value>>> {
        self.datasets.get(name).map(|d| d.clone())
    }

    pub async fn execute_rule(&self, rule_id: &str) -> AppResult<QualityCheckResult> {
        let rule = self
            .get_rule(rule_id)
            .ok_or_else(|| AppError::NotFound(format!("规则不存在: {}", rule_id)))?;

        if !rule.enabled {
            return Err(AppError::DataQualityError(format!(
                "规则未启用: {}",
                rule_id
            )));
        }

        let dataset = self
            .get_dataset(&rule.dataset)
            .unwrap_or_default();

        let evaluator = self
            .evaluator_registry
            .get(&rule.rule_type)
            .ok_or_else(|| {
                AppError::DataQualityError(format!(
                    "未找到评估器: {:?}",
                    rule.rule_type
                ))
            })?;

        let result = evaluator.evaluate(&rule, &dataset).await?;

        if !result.passed {
            self.anomaly_store
                .store(&rule, &result, &dataset)
                .await?;

            if self.config.alert_enabled {
                tracing::warn!(
                    target: "data_quality",
                    "数据质量检查失败: rule={}, dataset={}, actual={}, expected={}, anomalies={}",
                    rule.name,
                    rule.dataset,
                    result.actual_value,
                    result.expected_value,
                    result.anomaly_count
                );
            }
        }

        Ok(result)
    }

    pub async fn execute_all_rules(&self) -> AppResult<Vec<QualityCheckResult>> {
        let mut results = Vec::new();

        for rule in self.list_rules() {
            if rule.enabled {
                match self.execute_rule(&rule.rule_id).await {
                    Ok(result) => results.push(result),
                    Err(e) => {
                        tracing::error!(
                            target: "data_quality",
                            "执行规则失败: {}, error={}",
                            rule.name,
                            e
                        );
                    }
                }
            }
        }

        Ok(results)
    }

    pub async fn schedule_rule(&self, rule_id: &str) -> AppResult<()> {
        let rule = self
            .get_rule(rule_id)
            .ok_or_else(|| AppError::NotFound(format!("规则不存在: {}", rule_id)))?;

        let rule_clone = rule.clone();
        let self_arc = Arc::new(self.clone());

        let job = Job::new(rule.schedule.parse().map_err(|e| {
            AppError::DataQualityError(format!("无效的Cron表达式: {}", e))
        })?, move || {
            let rule_for_task = rule_clone.clone();
            let self_for_task = self_arc.clone();
            tokio::spawn(async move {
                let _ = self_for_task.execute_rule(&rule_for_task.rule_id).await;
            });
        });

        let mut scheduler = self.scheduler.lock().await;
        let job_id = scheduler.add(job);
        self.job_ids.insert(rule_id.to_string(), job_id);

        tracing::info!(
            target: "data_quality",
            "已调度规则: {}, schedule={}",
            rule.name,
            rule.schedule
        );

        Ok(())
    }

    pub async fn start_scheduler(&self) -> AppResult<()> {
        for rule in self.list_rules() {
            if rule.enabled && !rule.schedule.is_empty() {
                let _ = self.schedule_rule(&rule.rule_id).await;
            }
        }

        let scheduler_clone = self.scheduler.clone();
        tokio::spawn(async move {
            loop {
                let mut s = scheduler_clone.lock().await;
                s.tick();
                drop(s);
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        });

        tracing::info!(target: "data_quality", "数据质量调度器已启动");
        Ok(())
    }

    pub fn anomaly_store(&self) -> &Arc<AnomalyStore> {
        &self.anomaly_store
    }

    pub fn evaluator_registry(&self) -> &Arc<EvaluatorRegistry> {
        &self.evaluator_registry
    }
}

impl Clone for QualityRuleManager {
    fn clone(&self) -> Self {
        Self {
            rules: self.rules.clone(),
            evaluator_registry: self.evaluator_registry.clone(),
            anomaly_store: self.anomaly_store.clone(),
            datasets: self.datasets.clone(),
            scheduler: self.scheduler.clone(),
            config: self.config.clone(),
            job_ids: self.job_ids.clone(),
        }
    }
}

pub fn create_quality_manager(config: DataQualityConfig) -> QualityRuleManager {
    QualityRuleManager::new(config)
}
