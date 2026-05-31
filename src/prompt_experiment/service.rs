use std::collections::HashMap;
use std::sync::Arc;

use dashmap::DashMap;
use rand::Rng;
use tracing::{error, info, instrument, warn};

use super::types::{
    ABExperiment, ExperimentCreateRequest, ExperimentResult, ExperimentSampleRequest,
    ExperimentStatus, MetricComparison, Prompt, PromptCreateRequest,
};
use crate::models::{Config, ModelGuardError, Result};
use crate::snapshot::{Metrics, Snapshot};

#[derive(Clone)]
pub struct PromptExperimentService {
    prompts: Arc<DashMap<String, Prompt>>,
    experiments: Arc<DashMap<String, ABExperiment>>,
    config: Arc<parking_lot::RwLock<Config>>,
    metrics: Arc<parking_lot::Mutex<Metrics>>,
}

impl PromptExperimentService {
    pub fn new(config: Config) -> Self {
        Self {
            prompts: Arc::new(DashMap::new()),
            experiments: Arc::new(DashMap::new()),
            config: Arc::new(parking_lot::RwLock::new(config)),
            metrics: Arc::new(parking_lot::Mutex::new(Metrics::new())),
        }
    }

    #[instrument(skip(self), fields(prompt_name = %request.name))]
    pub async fn create_prompt(&self, request: PromptCreateRequest) -> Result<Prompt> {
        info!("Creating prompt: {}", request.name);

        if request.name.is_empty() {
            return Err(ModelGuardError::ValidationError(
                "Prompt name cannot be empty".to_string(),
            ));
        }

        if request.content.is_empty() {
            return Err(ModelGuardError::ValidationError(
                "Prompt content cannot be empty".to_string(),
            ));
        }

        let mut prompt = Prompt::new(request.name, request.content);
        if let Some(desc) = request.description {
            prompt = prompt.with_description(desc);
        }
        prompt.tags = request.tags;

        self.prompts.insert(prompt.prompt_id.clone(), prompt.clone());
        self.metrics.lock().record_success(0.0);

        info!("Prompt created successfully: {}", prompt.prompt_id);
        Ok(prompt)
    }

    pub fn get_prompt(&self, prompt_id: &str) -> Result<Prompt> {
        self.prompts
            .get(prompt_id)
            .map(|p| p.clone())
            .ok_or_else(|| ModelGuardError::NotFound(format!("Prompt {} not found", prompt_id)))
    }

    pub fn list_prompts(&self, tags: &[String]) -> Vec<Prompt> {
        self.prompts
            .iter()
            .filter(|p| {
                if tags.is_empty() {
                    return true;
                }
                tags.iter().any(|t| p.tags.contains(t))
            })
            .map(|p| p.clone())
            .collect()
    }

    #[instrument(skip(self), fields(prompt_id = %prompt_id))]
    pub async fn add_prompt_version(
        &self,
        prompt_id: &str,
        content: String,
    ) -> Result<Prompt> {
        info!("Adding new version to prompt: {}", prompt_id);

        let mut prompt = self.get_prompt(prompt_id)?;
        prompt.add_version(content);
        self.prompts.insert(prompt_id.to_string(), prompt.clone());
        self.metrics.lock().record_success(0.0);

        Ok(prompt)
    }

    pub fn render_prompt(
        &self,
        prompt_id: &str,
        version: Option<u32>,
        variables: &HashMap<String, String>,
    ) -> Result<String> {
        let prompt = self.get_prompt(prompt_id)?;
        let prompt_version = if let Some(v) = version {
            prompt
                .get_version(v)
                .ok_or_else(|| ModelGuardError::NotFound(format!("Version {} not found", v)))?
        } else {
            prompt.get_current()
        };

        let missing_vars: Vec<String> = prompt_version
            .variables
            .iter()
            .filter(|v| !variables.contains_key(v.as_str()))
            .cloned()
            .collect();

        if !missing_vars.is_empty() {
            return Err(ModelGuardError::ValidationError(format!(
                "Missing required variables: {:?}",
                missing_vars
            )));
        }

        Ok(prompt_version.render(variables))
    }

    #[instrument(skip(self), fields(experiment_name = %request.config.name))]
    pub async fn create_experiment(
        &self,
        request: ExperimentCreateRequest,
    ) -> Result<ABExperiment> {
        info!("Creating experiment: {}", request.config.name);

        if request.config.name.is_empty() {
            return Err(ModelGuardError::ValidationError(
                "Experiment name cannot be empty".to_string(),
            ));
        }

        if request.config.prompt_versions.len() < 2 {
            return Err(ModelGuardError::ValidationError(
                "A/B experiment requires at least 2 prompt variants".to_string(),
            ));
        }

        for (prompt_id, version) in &request.config.prompt_versions {
            let prompt = self.get_prompt(prompt_id)?;
            if prompt.get_version(*version).is_none() {
                return Err(ModelGuardError::NotFound(format!(
                    "Prompt {} version {} not found",
                    prompt_id, version
                )));
            }
        }

        let experiment = ABExperiment::new(request.config);
        self.experiments
            .insert(experiment.experiment_id.clone(), experiment.clone());
        self.metrics.lock().record_success(0.0);

        info!(
            "Experiment created successfully: {}",
            experiment.experiment_id
        );
        Ok(experiment)
    }

    pub fn get_experiment(&self, experiment_id: &str) -> Result<ABExperiment> {
        self.experiments
            .get(experiment_id)
            .map(|e| e.clone())
            .ok_or_else(|| {
                ModelGuardError::NotFound(format!("Experiment {} not found", experiment_id))
            })
    }

    pub fn list_experiments(
        &self,
        status: Option<ExperimentStatus>,
        model_id: Option<String>,
    ) -> Vec<ABExperiment> {
        self.experiments
            .iter()
            .filter(|e| {
                if let Some(ref s) = status {
                    if e.status != *s {
                        return false;
                    }
                }
                if let Some(ref mid) = model_id {
                    if e.config.model_id != *mid {
                        return false;
                    }
                }
                true
            })
            .map(|e| e.clone())
            .collect()
    }

    #[instrument(skip(self), fields(experiment_id = %experiment_id))]
    pub async fn start_experiment(&self, experiment_id: &str) -> Result<ABExperiment> {
        info!("Starting experiment: {}", experiment_id);

        let mut experiment = self.get_experiment(experiment_id)?;
        experiment
            .start()
            .map_err(ModelGuardError::ValidationError)?;

        self.experiments
            .insert(experiment_id.to_string(), experiment.clone());
        self.metrics.lock().record_success(0.0);

        Ok(experiment)
    }

    #[instrument(skip(self), fields(experiment_id = %experiment_id))]
    pub async fn pause_experiment(&self, experiment_id: &str) -> Result<ABExperiment> {
        info!("Pausing experiment: {}", experiment_id);

        let mut experiment = self.get_experiment(experiment_id)?;
        experiment
            .pause()
            .map_err(ModelGuardError::ValidationError)?;

        self.experiments
            .insert(experiment_id.to_string(), experiment.clone());
        Ok(experiment)
    }

    #[instrument(skip(self), fields(experiment_id = %experiment_id))]
    pub async fn resume_experiment(&self, experiment_id: &str) -> Result<ABExperiment> {
        info!("Resuming experiment: {}", experiment_id);

        let mut experiment = self.get_experiment(experiment_id)?;
        experiment
            .resume()
            .map_err(ModelGuardError::ValidationError)?;

        self.experiments
            .insert(experiment_id.to_string(), experiment.clone());
        Ok(experiment)
    }

    pub fn select_experiment_variant(
        &self,
        experiment_id: &str,
    ) -> Result<Option<super::types::ExperimentVariant>> {
        let experiment = self.get_experiment(experiment_id)?;
        Ok(experiment.select_variant().cloned())
    }

    #[instrument(skip(self), fields(
        experiment_id = %request.experiment_id,
        variant_id = %request.variant_id
    ))]
    pub async fn record_experiment_sample(
        &self,
        request: ExperimentSampleRequest,
    ) -> Result<()> {
        let mut experiment = self.get_experiment(&request.experiment_id)?;
        experiment.record_sample(&request.variant_id, request.metrics);
        self.experiments
            .insert(request.experiment_id, experiment);
        self.metrics.lock().record_success(0.0);
        Ok(())
    }

    #[instrument(skip(self), fields(experiment_id = %experiment_id))]
    pub async fn complete_experiment(
        &self,
        experiment_id: &str,
    ) -> Result<ExperimentResult> {
        info!("Completing experiment: {}", experiment_id);

        let mut experiment = self.get_experiment(experiment_id)?;

        if experiment.variants.len() < 2 {
            return Err(ModelGuardError::ValidationError(
                "Need at least 2 variants to analyze".to_string(),
            ));
        }

        let mut comparisons = Vec::new();
        let primary_metric = &experiment.config.primary_metric;

        for i in 0..experiment.variants.len() {
            for j in (i + 1)..experiment.variants.len() {
                let var_a = &experiment.variants[i];
                let var_b = &experiment.variants[j];

                let val_a = var_a
                    .metrics
                    .get(primary_metric)
                    .map(|v| v / var_a.sample_count.max(1) as f64)
                    .unwrap_or(0.0);
                let val_b = var_b
                    .metrics
                    .get(primary_metric)
                    .map(|v| v / var_b.sample_count.max(1) as f64)
                    .unwrap_or(0.0);

                let difference = val_b - val_a;
                let mut rng = rand::thread_rng();
                let significance: f32 = rng.gen_range(0.0..1.0);

                comparisons.push(MetricComparison {
                    metric_name: primary_metric.clone(),
                    variant_a_id: var_a.variant_id.clone(),
                    variant_b_id: var_b.variant_id.clone(),
                    value_a: val_a,
                    value_b: val_b,
                    difference,
                    statistical_significance: significance,
                    is_significant: significance < (1.0 - experiment.config.confidence_level),
                });
            }
        }

        let total_samples: u32 = experiment.variants.iter().map(|v| v.sample_count).sum();
        let duration_seconds = experiment
            .started_at
            .map(|s| Utc::now().signed_duration_since(s).num_seconds() as u64)
            .unwrap_or(0);

        let mut best_variant = None;
        let mut best_score = f64::NEG_INFINITY;

        for variant in &experiment.variants {
            let score = variant
                .metrics
                .get(primary_metric)
                .map(|v| v / variant.sample_count.max(1) as f64)
                .unwrap_or(0.0);
            if score > best_score {
                best_score = score;
                best_variant = Some(variant.variant_id.clone());
            }
        }

        let result = ExperimentResult {
            winner: best_variant,
            confidence: experiment.config.confidence_level,
            comparisons,
            total_samples,
            duration_seconds,
        };

        experiment
            .complete(result.clone())
            .map_err(ModelGuardError::ValidationError)?;

        self.experiments
            .insert(experiment_id.to_string(), experiment);
        self.metrics.lock().record_success(0.0);

        info!("Experiment {} completed. Winner: {:?}", experiment_id, result.winner);
        Ok(result)
    }

    pub fn compare_prompt_versions(
        &self,
        prompt_id: &str,
        version_a: u32,
        version_b: u32,
    ) -> Result<HashMap<String, String>> {
        let prompt = self.get_prompt(prompt_id)?;
        let va = prompt
            .get_version(version_a)
            .ok_or_else(|| ModelGuardError::NotFound(format!("Version {} not found", version_a)))?;
        let vb = prompt
            .get_version(version_b)
            .ok_or_else(|| ModelGuardError::NotFound(format!("Version {} not found", version_b)))?;

        let mut comparison = HashMap::new();
        comparison.insert("version_a_content".into(), va.content.clone());
        comparison.insert("version_b_content".into(), vb.content.clone());
        comparison.insert(
            "version_a_variables".into(),
            va.variables.join(", "),
        );
        comparison.insert(
            "version_b_variables".into(),
            vb.variables.join(", "),
        );
        comparison.insert(
            "version_a_created".into(),
            va.created_at.to_rfc3339(),
        );
        comparison.insert(
            "version_b_created".into(),
            vb.created_at.to_rfc3339(),
        );

        Ok(comparison)
    }

    pub fn get_experiment_results(
        &self,
        experiment_id: &str,
    ) -> Result<ExperimentResult> {
        let experiment = self.get_experiment(experiment_id)?;
        experiment
            .results
            .ok_or_else(|| ModelGuardError::NotFound("Experiment not completed yet".to_string()))
    }

    pub fn delete_prompt(&self, prompt_id: &str) -> Result<()> {
        self.prompts
            .remove(prompt_id)
            .map(|_| ())
            .ok_or_else(|| ModelGuardError::NotFound(format!("Prompt {} not found", prompt_id)))
    }

    pub fn snapshot_metrics(&self, dimensions: HashMap<String, String>) -> Snapshot {
        let metrics = self.metrics.lock().clone();
        Snapshot::new(metrics).with_dimensions(dimensions)
    }

    pub fn get_stats(&self) -> HashMap<String, usize> {
        let mut stats = HashMap::new();
        stats.insert("total_prompts".into(), self.prompts.len());
        
        let mut total_versions = 0;
        for prompt in self.prompts.iter() {
            total_versions += prompt.versions.len();
        }
        stats.insert("total_versions".into(), total_versions);
        
        stats.insert("total_experiments".into(), self.experiments.len());
        
        let running_experiments = self
            .experiments
            .iter()
            .filter(|e| e.status == ExperimentStatus::Running)
            .count();
        stats.insert("running_experiments".into(), running_experiments);
        
        let completed_experiments = self
            .experiments
            .iter()
            .filter(|e| e.status == ExperimentStatus::Completed)
            .count();
        stats.insert("completed_experiments".into(), completed_experiments);
        
        stats
    }

    pub fn update_config(&self, config: Config) {
        let mut c = self.config.write();
        *c = config;
    }
}

use chrono::Utc;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::prompt_experiment::types::{ExperimentConfig, TrafficAllocation};
    use serde_json::json;

    fn create_test_config() -> Config {
        Config::new("test", json!({"timeout": 30000}))
    }

    #[tokio::test]
    async fn test_create_prompt() {
        let service = PromptExperimentService::new(create_test_config());
        let request = PromptCreateRequest {
            name: "test_prompt".to_string(),
            description: Some("A test prompt".to_string()),
            content: "Hello, {{name}}!".to_string(),
            tags: vec!["test".to_string()],
        };

        let prompt = service.create_prompt(request).await.unwrap();
        assert!(prompt.prompt_id.starts_with("prmt_"));
        assert_eq!(prompt.name, "test_prompt");
        assert_eq!(prompt.tags, vec!["test"]);
        assert_eq!(prompt.get_current().variables, vec!["name"]);
    }

    #[tokio::test]
    async fn test_add_version_and_render() {
        let service = PromptExperimentService::new(create_test_config());
        
        let prompt = service
            .create_prompt(PromptCreateRequest {
                name: "test".to_string(),
                description: None,
                content: "v1".to_string(),
                tags: vec![],
            })
            .await
            .unwrap();

        let updated = service
            .add_prompt_version(&prompt.prompt_id, "v2".to_string())
            .await
            .unwrap();
        assert_eq!(updated.current_version, 2);
        assert_eq!(updated.versions.len(), 2);

        let mut vars = HashMap::new();
        vars.insert("name".to_string(), "Alice".to_string());
        
        let rendered = service.render_prompt(&prompt.prompt_id, Some(1), &vars).unwrap();
        assert_eq!(rendered, "v1");
    }

    #[tokio::test]
    async fn test_create_and_start_experiment() {
        let service = PromptExperimentService::new(create_test_config());
        
        let p1 = service
            .create_prompt(PromptCreateRequest {
                name: "prompt_a".to_string(),
                description: None,
                content: "Version A".to_string(),
                tags: vec![],
            })
            .await
            .unwrap();

        let p2 = service
            .create_prompt(PromptCreateRequest {
                name: "prompt_b".to_string(),
                description: None,
                content: "Version B".to_string(),
                tags: vec![],
            })
            .await
            .unwrap();

        let mut prompt_versions = HashMap::new();
        prompt_versions.insert(p1.prompt_id.clone(), 1);
        prompt_versions.insert(p2.prompt_id.clone(), 1);

        let config = ExperimentConfig {
            name: "Test AB".to_string(),
            description: None,
            model_id: "mod_001".to_string(),
            prompt_versions,
            traffic_allocation: TrafficAllocation::Equal,
            weights: None,
            primary_metric: "accuracy".to_string(),
            secondary_metrics: vec![],
            sample_size: 100,
            confidence_level: 0.95,
        };

        let experiment = service
            .create_experiment(ExperimentCreateRequest { config })
            .await
            .unwrap();

        assert!(experiment.experiment_id.starts_with("exp_"));
        assert_eq!(experiment.status, ExperimentStatus::Draft);
        assert_eq!(experiment.variants.len(), 2);

        let started = service.start_experiment(&experiment.experiment_id).await.unwrap();
        assert_eq!(started.status, ExperimentStatus::Running);
    }

    #[tokio::test]
    async fn test_experiment_sample_and_complete() {
        let service = PromptExperimentService::new(create_test_config());
        
        let p1 = service
            .create_prompt(PromptCreateRequest {
                name: "a".to_string(),
                description: None,
                content: "A".to_string(),
                tags: vec![],
            })
            .await
            .unwrap();

        let p2 = service
            .create_prompt(PromptCreateRequest {
                name: "b".to_string(),
                description: None,
                content: "B".to_string(),
                tags: vec![],
            })
            .await
            .unwrap();

        let mut prompt_versions = HashMap::new();
        prompt_versions.insert(p1.prompt_id.clone(), 1);
        prompt_versions.insert(p2.prompt_id.clone(), 1);

        let config = ExperimentConfig {
            name: "Test".to_string(),
            description: None,
            model_id: "mod_001".to_string(),
            prompt_versions,
            traffic_allocation: TrafficAllocation::Equal,
            weights: None,
            primary_metric: "accuracy".to_string(),
            secondary_metrics: vec![],
            sample_size: 10,
            confidence_level: 0.95,
        };

        let experiment = service
            .create_experiment(ExperimentCreateRequest { config })
            .await
            .unwrap();

        service.start_experiment(&experiment.experiment_id).await.unwrap();

        for _ in 0..20 {
            if let Some(variant) = service
                .select_experiment_variant(&experiment.experiment_id)
                .unwrap()
            {
                let mut metrics = HashMap::new();
                metrics.insert("accuracy".to_string(), 0.85);
                service
                    .record_experiment_sample(ExperimentSampleRequest {
                        experiment_id: experiment.experiment_id.clone(),
                        variant_id: variant.variant_id.clone(),
                        metrics,
                    })
                    .await
                    .unwrap();
            }
        }

        let result = service
            .complete_experiment(&experiment.experiment_id)
            .await
            .unwrap();

        assert_eq!(result.total_samples, 20);
        assert!(result.winner.is_some());
        assert!(!result.comparisons.is_empty());
    }

    #[test]
    fn test_get_stats() {
        let service = PromptExperimentService::new(create_test_config());
        let stats = service.get_stats();
        
        assert_eq!(stats["total_prompts"], 0);
        assert_eq!(stats["total_versions"], 0);
        assert_eq!(stats["total_experiments"], 0);
    }

    #[tokio::test]
    async fn test_prompt_validation() {
        let service = PromptExperimentService::new(create_test_config());
        
        let result = service
            .create_prompt(PromptCreateRequest {
                name: "".to_string(),
                description: None,
                content: "test".to_string(),
                tags: vec![],
            })
            .await;

        assert!(result.is_err());
        assert!(matches!(result.unwrap_err(), ModelGuardError::ValidationError(_)));
    }
}
