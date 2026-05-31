use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use parking_lot::Mutex;
use chrono::{DateTime, Utc};
use crate::utils::error::AppError;
use crate::utils::metrics::MetricsCollector;
use crate::prompt_experiments::prompt::{Prompt, PromptRegistrationRequest, PromptContent};
use crate::prompt_experiments::version::{PromptVersion, SemanticVersion, VersionBumpType, VersionStatus};
use crate::prompt_experiments::ab_test::{ABTest, ABTestCreationRequest, TestStatus, Variant};
use crate::prompt_experiments::evaluation::{ExperimentEvaluation, ComparisonReport};

pub use crate::prompt_experiments::ab_test::VariantConfig;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionBumpRequest {
    pub prompt_id: String,
    pub bump_type: VersionBumpType,
    pub new_content: PromptContent,
    pub change_log: String,
    pub created_by: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricRecord {
    pub variant_id: String,
    pub metric_name: String,
    pub value: f64,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PromptExperimentStats {
    pub total_prompts: usize,
    pub total_versions: usize,
    pub total_tests: usize,
    pub running_tests: usize,
    pub completed_tests: usize,
}

pub struct PromptExperimentManager {
    prompts: Arc<Mutex<HashMap<String, Prompt>>>,
    versions: Arc<Mutex<HashMap<String, Vec<PromptVersion>>>>,
    tests: Arc<Mutex<HashMap<String, ABTest>>>,
    metric_records: Arc<Mutex<HashMap<String, HashMap<String, HashMap<String, Vec<f64>>>>>>,
    metrics: Arc<MetricsCollector>,
}

impl PromptExperimentManager {
    pub fn new(metrics: Arc<MetricsCollector>) -> Self {
        Self {
            prompts: Arc::new(Mutex::new(HashMap::new())),
            versions: Arc::new(Mutex::new(HashMap::new())),
            tests: Arc::new(Mutex::new(HashMap::new())),
            metric_records: Arc::new(Mutex::new(HashMap::new())),
            metrics,
        }
    }

    pub fn register_prompt(&self, request: PromptRegistrationRequest) -> Result<Prompt, AppError> {
        self.metrics.increment_counter("prompt_registration");
        
        let prompt = Prompt::new(request)?;
        self.prompts.lock().insert(prompt.prompt_id.clone(), prompt.clone());
        self.versions.lock().insert(prompt.prompt_id.clone(), Vec::new());
        
        Ok(prompt)
    }

    pub fn create_initial_version(
        &self,
        prompt_id: &str,
        content: PromptContent,
        created_by: String,
    ) -> Result<PromptVersion, AppError> {
        let prompt = self.get_prompt(prompt_id)?;
        let version = SemanticVersion::new(1, 0, 0);
        
        let prompt_version = PromptVersion::new(
            prompt.prompt_id.clone(),
            version,
            content,
            "Initial version".to_string(),
            created_by,
        );

        let mut versions = self.versions.lock();
        let prompt_versions = versions.entry(prompt_id.to_string())
            .or_insert_with(Vec::new);
        
        for v in prompt_versions.iter_mut() {
            v.is_latest = false;
        }
        
        prompt_versions.push(prompt_version.clone());
        prompt_versions.sort_by(|a, b| b.version.cmp(&a.version));
        
        self.metrics.increment_counter("version_created");
        Ok(prompt_version)
    }

    pub fn bump_version(&self, request: VersionBumpRequest) -> Result<PromptVersion, AppError> {
        let current = self.get_latest_version(&request.prompt_id)
            .ok_or_else(|| AppError::NotFound(format!(
                "No versions found for prompt {}",
                request.prompt_id
            )))?;

        let mut versions = self.versions.lock();
        let prompt_versions = versions.get_mut(&request.prompt_id).unwrap();
        
        for v in prompt_versions.iter_mut() {
            v.is_latest = false;
        }

        let new_version = current.bump(
            request.bump_type,
            request.new_content,
            request.change_log,
            request.created_by,
        );
        
        prompt_versions.push(new_version.clone());
        prompt_versions.sort_by(|a, b| b.version.cmp(&a.version));
        
        self.metrics.increment_counter("version_bumped");
        Ok(new_version)
    }

    pub fn set_version_status(&self, version_id: &str, status: VersionStatus) -> Result<PromptVersion, AppError> {
        let mut versions = self.versions.lock();
        
        for prompt_versions in versions.values_mut() {
            for v in prompt_versions.iter_mut() {
                if v.version_id == version_id {
                    v.set_status(status);
                    self.metrics.increment_counter("version_status_updated");
                    return Ok(v.clone());
                }
            }
        }
        
        Err(AppError::NotFound(format!("Version {} not found", version_id)))
    }

    pub fn get_prompt(&self, prompt_id: &str) -> Result<Prompt, AppError> {
        self.prompts.lock()
            .get(prompt_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Prompt {} not found", prompt_id)))
    }

    pub fn list_prompts(&self, tags: Option<Vec<String>>) -> Vec<Prompt> {
        let prompts = self.prompts.lock();
        
        match tags {
            Some(filter_tags) => prompts.values()
                .filter(|p| filter_tags.iter().any(|t| p.tags.contains(t)))
                .cloned()
                .collect(),
            None => prompts.values().cloned().collect(),
        }
    }

    pub fn get_versions(&self, prompt_id: &str) -> Result<Vec<PromptVersion>, AppError> {
        self.versions.lock()
            .get(prompt_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("No versions for prompt {}", prompt_id)))
    }

    pub fn get_latest_version(&self, prompt_id: &str) -> Option<PromptVersion> {
        self.versions.lock()
            .get(prompt_id)
            .and_then(|versions| versions.iter().find(|v| v.is_latest).cloned())
    }

    pub fn get_version(&self, version_id: &str) -> Option<PromptVersion> {
        let versions = self.versions.lock();
        
        for prompt_versions in versions.values() {
            for v in prompt_versions {
                if v.version_id == version_id {
                    return Some(v.clone());
                }
            }
        }
        
        None
    }

    pub fn create_ab_test(&self, request: ABTestCreationRequest) -> Result<ABTest, AppError> {
        self.get_prompt(&request.prompt_id)?;
        
        for variant_config in &request.variants {
            let version = self.get_version(&variant_config.prompt_version_id)
                .ok_or_else(|| AppError::NotFound(format!(
                    "Version {} not found",
                    variant_config.prompt_version_id
                )))?;
            
            if version.status != VersionStatus::Testing && version.status != VersionStatus::Production {
                return Err(AppError::Validation(format!(
                    "Version {} is not in Testing or Production status",
                    version.version_id
                )));
            }
        }

        let test = ABTest::new(request)?;
        self.tests.lock().insert(test.test_id.clone(), test.clone());
        self.metric_records.lock().insert(test.test_id.clone(), HashMap::new());
        
        self.metrics.increment_counter("ab_test_created");
        Ok(test)
    }

    pub fn start_test(&self, test_id: &str) -> Result<ABTest, AppError> {
        let mut tests = self.tests.lock();
        let test = tests.get_mut(test_id)
            .ok_or_else(|| AppError::NotFound(format!("Test {} not found", test_id)))?;
        
        test.start()?;
        self.metrics.increment_counter("ab_test_started");
        Ok(test.clone())
    }

    pub fn pause_test(&self, test_id: &str) -> Result<ABTest, AppError> {
        let mut tests = self.tests.lock();
        let test = tests.get_mut(test_id)
            .ok_or_else(|| AppError::NotFound(format!("Test {} not found", test_id)))?;
        
        test.pause()?;
        self.metrics.increment_counter("ab_test_paused");
        Ok(test.clone())
    }

    pub fn resume_test(&self, test_id: &str) -> Result<ABTest, AppError> {
        let mut tests = self.tests.lock();
        let test = tests.get_mut(test_id)
            .ok_or_else(|| AppError::NotFound(format!("Test {} not found", test_id)))?;
        
        test.resume()?;
        self.metrics.increment_counter("ab_test_resumed");
        Ok(test.clone())
    }

    pub fn stop_test(&self, test_id: &str) -> Result<ABTest, AppError> {
        let mut tests = self.tests.lock();
        let test = tests.get_mut(test_id)
            .ok_or_else(|| AppError::NotFound(format!("Test {} not found", test_id)))?;
        
        test.stop()?;
        self.metrics.increment_counter("ab_test_stopped");
        Ok(test.clone())
    }

    pub fn complete_test(&self, test_id: &str) -> Result<ABTest, AppError> {
        let mut tests = self.tests.lock();
        let test = tests.get_mut(test_id)
            .ok_or_else(|| AppError::NotFound(format!("Test {} not found", test_id)))?;
        
        test.complete()?;
        self.metrics.increment_counter("ab_test_completed");
        Ok(test.clone())
    }

    pub fn assign_variant(
        &self,
        test_id: &str,
        user_id: Option<&str>,
        session_id: Option<&str>,
    ) -> Result<Variant, AppError> {
        let mut tests = self.tests.lock();
        let test = tests.get_mut(test_id)
            .ok_or_else(|| AppError::NotFound(format!("Test {} not found", test_id)))?;
        
        let variant = test.assign_variant(user_id, session_id)?;
        self.metrics.increment_counter("variant_assigned");
        Ok(variant.clone())
    }

    pub fn record_metric(&self, test_id: &str, record: MetricRecord) -> Result<(), AppError> {
        let test = self.get_test(test_id)?;
        
        if test.get_variant(&record.variant_id).is_none() {
            return Err(AppError::NotFound(format!(
                "Variant {} not found in test {}",
                record.variant_id, test_id
            )));
        }

        let mut records = self.metric_records.lock();
        let test_records = records.entry(test_id.to_string())
            .or_insert_with(HashMap::new);
        
        let variant_records = test_records.entry(record.variant_id.clone())
            .or_insert_with(HashMap::new);
        
        let metric_values = variant_records.entry(record.metric_name.clone())
            .or_insert_with(Vec::new);
        
        metric_values.push(record.value);
        
        self.metrics.increment_counter("metric_recorded");
        Ok(())
    }

    pub fn evaluate_test(&self, test_id: &str) -> Result<ExperimentEvaluation, AppError> {
        let test = self.get_test(test_id)?;
        let records = self.metric_records.lock();
        
        let metric_data = records.get(test_id)
            .ok_or_else(|| AppError::NotFound(format!("No metric records for test {}", test_id)))?;
        
        let evaluation = ExperimentEvaluation::new(&test, metric_data)?;
        
        for (variant_id, result) in &evaluation.results {
            if let Some(version) = self.get_version(&test.get_variant(variant_id).unwrap().prompt_version_id) {
                let mut versions = self.versions.lock();
                for prompt_versions in versions.values_mut() {
                    for v in prompt_versions.iter_mut() {
                        if v.version_id == version.version_id {
                            v.add_evaluation_score(evaluation.primary_metric.clone(), result.mean);
                        }
                    }
                }
            }
        }
        
        self.metrics.increment_counter("test_evaluated");
        Ok(evaluation)
    }

    pub fn generate_report(&self, test_id: &str) -> Result<ComparisonReport, AppError> {
        let test = self.get_test(test_id)?;
        let evaluation = self.evaluate_test(test_id)?;
        
        let report = ComparisonReport::generate(&test, &evaluation)?;
        self.metrics.increment_counter("report_generated");
        Ok(report)
    }

    pub fn get_test(&self, test_id: &str) -> Result<ABTest, AppError> {
        self.tests.lock()
            .get(test_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Test {} not found", test_id)))
    }

    pub fn list_tests(&self, status: Option<TestStatus>) -> Vec<ABTest> {
        let tests = self.tests.lock();
        
        match status {
            Some(filter_status) => tests.values()
                .filter(|t| t.status == filter_status)
                .cloned()
                .collect(),
            None => tests.values().cloned().collect(),
        }
    }

    pub fn render_prompt(&self, version_id: &str, variables: &HashMap<String, String>) -> Result<String, AppError> {
        let version = self.get_version(version_id)
            .ok_or_else(|| AppError::NotFound(format!("Version {} not found", version_id)))?;
        
        let prompt = self.get_prompt(&version.prompt_id)?;
        prompt.render(variables)
    }

    pub fn get_stats(&self) -> PromptExperimentStats {
        let prompts = self.prompts.lock();
        let versions = self.versions.lock();
        let tests = self.tests.lock();
        
        let total_versions: usize = versions.values().map(|v| v.len()).sum();
        let running_tests = tests.values().filter(|t| t.status == TestStatus::Running).count();
        let completed_tests = tests.values().filter(|t| t.status == TestStatus::Completed).count();
        
        PromptExperimentStats {
            total_prompts: prompts.len(),
            total_versions,
            total_tests: tests.len(),
            running_tests,
            completed_tests,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::utils::metrics::MetricsCollector;
    use crate::prompt_experiments::ab_test::{TrafficAllocation, TrafficAllocationStrategy};

    fn create_manager() -> PromptExperimentManager {
        PromptExperimentManager::new(Arc::new(MetricsCollector::new()))
    }

    #[test]
    fn test_full_prompt_lifecycle() {
        let manager = create_manager();
        
        let content = PromptContent {
            text: "Hello, {name}!".to_string(),
            variables: vec!["name".to_string()],
            placeholders: HashMap::new(),
        };

        let request = PromptRegistrationRequest {
            name: "Test Prompt".to_string(),
            description: "A test prompt".to_string(),
            prompt_type: crate::prompt_experiments::prompt::PromptType::User,
            content: content.clone(),
            tags: vec!["test".to_string()],
            metadata: HashMap::new(),
            created_by: "test_user".to_string(),
        };

        let prompt = manager.register_prompt(request).unwrap();
        assert!(prompt.prompt_id.starts_with("prompt_"));

        let v1 = manager.create_initial_version(&prompt.prompt_id, content, "test_user".to_string()).unwrap();
        assert_eq!(v1.version_string, "1.0.0");

        let new_content = PromptContent {
            text: "Hi, {name}! Welcome!".to_string(),
            variables: vec!["name".to_string()],
            placeholders: HashMap::new(),
        };

        let bump_request = VersionBumpRequest {
            prompt_id: prompt.prompt_id.clone(),
            bump_type: VersionBumpType::Minor,
            new_content,
            change_log: "Updated greeting".to_string(),
            created_by: "test_user".to_string(),
        };

        let v2 = manager.bump_version(bump_request).unwrap();
        assert_eq!(v2.version_string, "1.1.0");

        let versions = manager.get_versions(&prompt.prompt_id).unwrap();
        assert_eq!(versions.len(), 2);
    }

    #[test]
    fn test_ab_test_full_flow() {
        let manager = create_manager();
        
        let content1 = PromptContent {
            text: "Hello, {name}!".to_string(),
            variables: vec!["name".to_string()],
            placeholders: HashMap::new(),
        };

        let content2 = PromptContent {
            text: "Hi, {name}! Welcome!".to_string(),
            variables: vec!["name".to_string()],
            placeholders: HashMap::new(),
        };

        let prompt_request = PromptRegistrationRequest {
            name: "Greeting".to_string(),
            description: "Greeting prompt".to_string(),
            prompt_type: crate::prompt_experiments::prompt::PromptType::User,
            content: content1.clone(),
            tags: vec![],
            metadata: HashMap::new(),
            created_by: "test".to_string(),
        };

        let prompt = manager.register_prompt(prompt_request).unwrap();
        let v1 = manager.create_initial_version(&prompt.prompt_id, content1, "test".to_string()).unwrap();
        let v2 = manager.create_initial_version(&prompt.prompt_id, content2, "test".to_string()).unwrap();

        manager.set_version_status(&v1.version_id, VersionStatus::Testing).unwrap();
        manager.set_version_status(&v2.version_id, VersionStatus::Testing).unwrap();

        let variant1 = VariantConfig {
            name: "Control".to_string(),
            prompt_version_id: v1.version_id.clone(),
            description: "Original".to_string(),
            traffic_weight: 0.5,
            is_control: true,
            metadata: HashMap::new(),
        };

        let variant2 = VariantConfig {
            name: "Treatment".to_string(),
            prompt_version_id: v2.version_id.clone(),
            description: "Improved".to_string(),
            traffic_weight: 0.5,
            is_control: false,
            metadata: HashMap::new(),
        };

        let test_request = ABTestCreationRequest {
            name: "Greeting Test".to_string(),
            description: "Test greeting prompts".to_string(),
            prompt_id: prompt.prompt_id.clone(),
            variants: vec![variant1, variant2],
            traffic_allocation: TrafficAllocation {
                strategy: TrafficAllocationStrategy::Uniform,
                weights: HashMap::new(),
                seed: Some(42),
            },
            primary_metric: "click_rate".to_string(),
            secondary_metrics: vec![],
            start_time: None,
            end_time: None,
            min_sample_size: 10,
            confidence_level: 0.95,
            created_by: "test".to_string(),
        };

        let test = manager.create_ab_test(test_request).unwrap();
        manager.start_test(&test.test_id).unwrap();

        for i in 0..20 {
            let variant = manager.assign_variant(&test.test_id, Some(&format!("user_{}", i)), None).unwrap();
            
            let value = if variant.is_control {
                0.7 + rand::random::<f64>() * 0.1
            } else {
                0.85 + rand::random::<f64>() * 0.05
            };

            manager.record_metric(&test.test_id, MetricRecord {
                variant_id: variant.variant_id.clone(),
                metric_name: "click_rate".to_string(),
                value,
                timestamp: Utc::now(),
            }).unwrap();
        }

        let report = manager.generate_report(&test.test_id).unwrap();
        assert!(!report.recommendations.is_empty());
        
        let stats = manager.get_stats();
        assert_eq!(stats.total_prompts, 1);
        assert_eq!(stats.total_tests, 1);
    }

    #[test]
    fn test_prompt_rendering() {
        let manager = create_manager();
        
        let content = PromptContent {
            text: "Hello, {name}! Welcome to {platform}.".to_string(),
            variables: vec!["name".to_string()],
            placeholders: vec![("platform".to_string(), "our platform".to_string())]
                .into_iter().collect(),
        };

        let request = PromptRegistrationRequest {
            name: "Test".to_string(),
            description: "Test".to_string(),
            prompt_type: crate::prompt_experiments::prompt::PromptType::User,
            content: content.clone(),
            tags: vec![],
            metadata: HashMap::new(),
            created_by: "test".to_string(),
        };

        let prompt = manager.register_prompt(request).unwrap();
        let version = manager.create_initial_version(&prompt.prompt_id, content, "test".to_string()).unwrap();

        let mut vars = HashMap::new();
        vars.insert("name".to_string(), "Alice".to_string());
        
        let rendered = manager.render_prompt(&version.version_id, &vars).unwrap();
        assert_eq!(rendered, "Hello, Alice! Welcome to our platform.");
    }
}
