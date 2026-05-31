use chrono::{DateTime, Utc};
use rand::Rng;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum ExperimentStatus {
    Draft,
    Running,
    Paused,
    Completed,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum TrafficAllocation {
    Equal,
    Weighted,
    Manual,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Prompt {
    pub prompt_id: String,
    pub name: String,
    pub description: Option<String>,
    pub current_version: u32,
    pub versions: Vec<PromptVersion>,
    pub tags: Vec<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl Prompt {
    pub fn new(name: impl Into<String>, content: impl Into<String>) -> Self {
        let now = Utc::now();
        let mut versions = Vec::new();
        versions.push(PromptVersion::new(1, content));

        Self {
            prompt_id: format!("prmt_{}", Uuid::new_v4().simple()),
            name: name.into(),
            description: None,
            current_version: 1,
            versions,
            tags: Vec::new(),
            created_at: now,
            updated_at: now,
        }
    }

    pub fn with_description(mut self, desc: impl Into<String>) -> Self {
        self.description = Some(desc.into());
        self
    }

    pub fn add_version(&mut self, content: impl Into<String>) -> &PromptVersion {
        let new_version = self.current_version + 1;
        self.versions.push(PromptVersion::new(new_version, content));
        self.current_version = new_version;
        self.updated_at = Utc::now();
        self.versions.last().unwrap()
    }

    pub fn get_version(&self, version: u32) -> Option<&PromptVersion> {
        self.versions.iter().find(|v| v.version == version)
    }

    pub fn get_current(&self) -> &PromptVersion {
        self.versions
            .iter()
            .find(|v| v.version == self.current_version)
            .unwrap_or(&self.versions[0])
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PromptVersion {
    pub version: u32,
    pub content: String,
    pub variables: Vec<String>,
    pub metadata: HashMap<String, String>,
    pub created_at: DateTime<Utc>,
}

impl PromptVersion {
    pub fn new(version: u32, content: impl Into<String>) -> Self {
        let content_str = content.into();
        let variables = extract_variables(&content_str);

        Self {
            version,
            content: content_str,
            variables,
            metadata: HashMap::new(),
            created_at: Utc::now(),
        }
    }

    pub fn with_metadata(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.metadata.insert(key.into(), value.into());
        self
    }

    pub fn render(&self, variables: &HashMap<String, String>) -> String {
        let mut rendered = self.content.clone();
        for (key, value) in variables {
            let placeholder = format!("{{{{{}}}}}", key);
            rendered = rendered.replace(&placeholder, value);
        }
        rendered
    }
}

fn extract_variables(content: &str) -> Vec<String> {
    let mut variables = Vec::new();
    let bytes = content.as_bytes();
    let mut i = 0;

    while i + 1 < bytes.len() {
        if bytes[i] == b'{' && bytes[i + 1] == b'{' {
            let start = i + 2;
            let mut end = start;
            while end + 1 < bytes.len() && !(bytes[end] == b'}' && bytes[end + 1] == b'}') {
                end += 1;
            }
            if end + 1 < bytes.len() {
                let var_name = String::from_utf8_lossy(&bytes[start..end])
                    .trim()
                    .to_string();
                if !variables.contains(&var_name) {
                    variables.push(var_name);
                }
            }
            i = end + 2;
        } else {
            i += 1;
        }
    }

    variables
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExperimentConfig {
    pub name: String,
    pub description: Option<String>,
    pub model_id: String,
    pub prompt_versions: HashMap<String, u32>,
    pub traffic_allocation: TrafficAllocation,
    pub weights: Option<HashMap<String, u32>>,
    pub primary_metric: String,
    pub secondary_metrics: Vec<String>,
    pub sample_size: u32,
    pub confidence_level: f32,
}

impl Default for ExperimentConfig {
    fn default() -> Self {
        Self {
            name: String::new(),
            description: None,
            model_id: String::new(),
            prompt_versions: HashMap::new(),
            traffic_allocation: TrafficAllocation::Equal,
            weights: None,
            primary_metric: "accuracy".to_string(),
            secondary_metrics: Vec::new(),
            sample_size: 1000,
            confidence_level: 0.95,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ABExperiment {
    pub experiment_id: String,
    pub config: ExperimentConfig,
    pub status: ExperimentStatus,
    pub variants: Vec<ExperimentVariant>,
    pub results: Option<ExperimentResult>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
}

impl ABExperiment {
    pub fn new(config: ExperimentConfig) -> Self {
        let mut variants = Vec::new();
        for (prompt_id, version) in &config.prompt_versions {
            variants.push(ExperimentVariant {
                variant_id: format!("var_{}", Uuid::new_v4().simple()),
                prompt_id: prompt_id.clone(),
                prompt_version: *version,
                traffic_percentage: 0.0,
                sample_count: 0,
                metrics: HashMap::new(),
            });
        }

        let num_variants = variants.len() as f64;
        for variant in &mut variants {
            variant.traffic_percentage = 100.0 / num_variants;
        }

        Self {
            experiment_id: format!("exp_{}", Uuid::new_v4().simple()),
            config,
            status: ExperimentStatus::Draft,
            variants,
            results: None,
            started_at: None,
            completed_at: None,
            created_at: Utc::now(),
        }
    }

    pub fn start(&mut self) -> Result<(), String> {
        if self.status != ExperimentStatus::Draft {
            return Err("Experiment is not in draft status".to_string());
        }
        if self.config.prompt_versions.len() < 2 {
            return Err("Need at least 2 variants for A/B test".to_string());
        }
        self.status = ExperimentStatus::Running;
        self.started_at = Some(Utc::now());
        Ok(())
    }

    pub fn pause(&mut self) -> Result<(), String> {
        if self.status != ExperimentStatus::Running {
            return Err("Experiment is not running".to_string());
        }
        self.status = ExperimentStatus::Paused;
        Ok(())
    }

    pub fn resume(&mut self) -> Result<(), String> {
        if self.status != ExperimentStatus::Paused {
            return Err("Experiment is not paused".to_string());
        }
        self.status = ExperimentStatus::Running;
        Ok(())
    }

    pub fn complete(&mut self, results: ExperimentResult) -> Result<(), String> {
        if self.status != ExperimentStatus::Running && self.status != ExperimentStatus::Paused {
            return Err("Experiment is not running or paused".to_string());
        }
        self.status = ExperimentStatus::Completed;
        self.completed_at = Some(Utc::now());
        self.results = Some(results);
        Ok(())
    }

    pub fn select_variant(&self) -> Option<&ExperimentVariant> {
        if self.status != ExperimentStatus::Running {
            return None;
        }
        let mut rng = rand::thread_rng();
        let r: f64 = rng.gen();
        let mut cumulative = 0.0;

        for variant in &self.variants {
            cumulative += variant.traffic_percentage / 100.0;
            if r < cumulative {
                return Some(variant);
            }
        }
        self.variants.last()
    }

    pub fn record_sample(&mut self, variant_id: &str, metrics: HashMap<String, f64>) {
        if let Some(variant) = self.variants.iter_mut().find(|v| v.variant_id == variant_id) {
            variant.sample_count += 1;
            for (k, v) in metrics {
                variant
                    .metrics
                    .entry(k)
                    .and_modify(|e| *e += v)
                    .or_insert(v);
            }
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExperimentVariant {
    pub variant_id: String,
    pub prompt_id: String,
    pub prompt_version: u32,
    pub traffic_percentage: f64,
    pub sample_count: u32,
    pub metrics: HashMap<String, f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExperimentResult {
    pub winner: Option<String>,
    pub confidence: f32,
    pub comparisons: Vec<MetricComparison>,
    pub total_samples: u32,
    pub duration_seconds: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricComparison {
    pub metric_name: String,
    pub variant_a_id: String,
    pub variant_b_id: String,
    pub value_a: f64,
    pub value_b: f64,
    pub difference: f64,
    pub statistical_significance: f32,
    pub is_significant: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct PromptCreateRequest {
    pub name: String,
    pub description: Option<String>,
    pub content: String,
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ExperimentCreateRequest {
    pub config: ExperimentConfig,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ExperimentSampleRequest {
    pub experiment_id: String,
    pub variant_id: String,
    pub metrics: HashMap<String, f64>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_prompt_creation() {
        let prompt = Prompt::new("greeting", "Hello, {{name}}!")
            .with_description("A friendly greeting prompt");

        assert!(prompt.prompt_id.starts_with("prmt_"));
        assert_eq!(prompt.name, "greeting");
        assert_eq!(prompt.current_version, 1);
        assert_eq!(prompt.versions.len(), 1);
        assert_eq!(prompt.description, Some("A friendly greeting prompt".to_string()));
    }

    #[test]
    fn test_prompt_version_variables() {
        let prompt = Prompt::new("test", "Hello {{name}}, you are {{age}} years old");
        let version = prompt.get_current();
        
        assert_eq!(version.variables.len(), 2);
        assert!(version.variables.contains(&"name".to_string()));
        assert!(version.variables.contains(&"age".to_string()));
    }

    #[test]
    fn test_prompt_rendering() {
        let prompt = Prompt::new("greeting", "Hello, {{name}}!");
        let version = prompt.get_current();
        
        let mut vars = HashMap::new();
        vars.insert("name".to_string(), "Alice".to_string());
        
        let rendered = version.render(&vars);
        assert_eq!(rendered, "Hello, Alice!");
    }

    #[test]
    fn test_add_version() {
        let mut prompt = Prompt::new("test", "v1");
        assert_eq!(prompt.current_version, 1);
        
        prompt.add_version("v2");
        assert_eq!(prompt.current_version, 2);
        assert_eq!(prompt.versions.len(), 2);
        assert_eq!(prompt.get_current().content, "v2");
    }

    #[test]
    fn test_experiment_creation() {
        let mut prompt_versions = HashMap::new();
        prompt_versions.insert("prmt_001".to_string(), 1);
        prompt_versions.insert("prmt_002".to_string(), 2);

        let config = ExperimentConfig {
            name: "Test Experiment".to_string(),
            model_id: "mod_001".to_string(),
            prompt_versions,
            ..Default::default()
        };

        let experiment = ABExperiment::new(config);
        assert!(experiment.experiment_id.starts_with("exp_"));
        assert_eq!(experiment.status, ExperimentStatus::Draft);
        assert_eq!(experiment.variants.len(), 2);
        assert_eq!(experiment.variants[0].traffic_percentage, 50.0);
    }

    #[test]
    fn test_experiment_lifecycle() {
        let mut prompt_versions = HashMap::new();
        prompt_versions.insert("prmt_001".to_string(), 1);
        prompt_versions.insert("prmt_002".to_string(), 2);

        let config = ExperimentConfig {
            name: "Test".to_string(),
            model_id: "mod_001".to_string(),
            prompt_versions,
            ..Default::default()
        };

        let mut experiment = ABExperiment::new(config);
        
        assert!(experiment.start().is_ok());
        assert_eq!(experiment.status, ExperimentStatus::Running);
        assert!(experiment.started_at.is_some());

        assert!(experiment.pause().is_ok());
        assert_eq!(experiment.status, ExperimentStatus::Paused);

        assert!(experiment.resume().is_ok());
        assert_eq!(experiment.status, ExperimentStatus::Running);

        let results = ExperimentResult {
            winner: None,
            confidence: 0.95,
            comparisons: Vec::new(),
            total_samples: 100,
            duration_seconds: 3600,
        };
        assert!(experiment.complete(results).is_ok());
        assert_eq!(experiment.status, ExperimentStatus::Completed);
        assert!(experiment.completed_at.is_some());
    }

    #[test]
    fn test_experiment_selection_and_recording() {
        let mut prompt_versions = HashMap::new();
        prompt_versions.insert("prmt_001".to_string(), 1);
        prompt_versions.insert("prmt_002".to_string(), 2);

        let config = ExperimentConfig {
            name: "Test".to_string(),
            model_id: "mod_001".to_string(),
            prompt_versions,
            ..Default::default()
        };

        let mut experiment = ABExperiment::new(config);
        experiment.start().unwrap();

        for _ in 0..100 {
            if let Some(variant) = experiment.select_variant() {
                let variant_id = variant.variant_id.clone();
                let mut metrics = HashMap::new();
                metrics.insert("accuracy".to_string(), 0.8);
                experiment.record_sample(&variant_id, metrics);
            }
        }

        let total_samples: u32 = experiment.variants.iter().map(|v| v.sample_count).sum();
        assert_eq!(total_samples, 100);
    }

    #[test]
    fn test_extract_variables() {
        let vars = extract_variables("Hello {{user}}, welcome to {{app}}!");
        assert_eq!(vars, vec!["user", "app"]);

        let vars = extract_variables("No variables here");
        assert!(vars.is_empty());
    }

    #[test]
    fn test_experiment_validation() {
        let mut prompt_versions = HashMap::new();
        prompt_versions.insert("prmt_001".to_string(), 1);

        let config = ExperimentConfig {
            name: "Test".to_string(),
            model_id: "mod_001".to_string(),
            prompt_versions,
            ..Default::default()
        };

        let mut experiment = ABExperiment::new(config);
        let result = experiment.start();
        assert!(result.is_err());
        assert_eq!(result.unwrap_err(), "Need at least 2 variants for A/B test");
    }
}
