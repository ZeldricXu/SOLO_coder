use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use chrono::{DateTime, Utc};
use crate::utils::error::AppError;
use crate::utils::id::generate_id;
use rand::Rng;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TestStatus {
    Draft,
    Running,
    Paused,
    Completed,
    Stopped,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TrafficAllocationStrategy {
    Uniform,
    Weighted,
    UserSticky,
    SessionSticky,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrafficAllocation {
    pub strategy: TrafficAllocationStrategy,
    pub weights: HashMap<String, f64>,
    pub seed: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Variant {
    pub variant_id: String,
    pub name: String,
    pub prompt_version_id: String,
    pub description: String,
    pub traffic_weight: f64,
    pub is_control: bool,
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ABTestConfig {
    pub name: String,
    pub description: String,
    pub prompt_id: String,
    pub variants: Vec<Variant>,
    pub traffic_allocation: TrafficAllocation,
    pub primary_metric: String,
    pub secondary_metrics: Vec<String>,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub min_sample_size: u64,
    pub confidence_level: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ABTestCreationRequest {
    pub name: String,
    pub description: String,
    pub prompt_id: String,
    pub variants: Vec<VariantConfig>,
    pub traffic_allocation: TrafficAllocation,
    pub primary_metric: String,
    pub secondary_metrics: Vec<String>,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub min_sample_size: u64,
    pub confidence_level: f64,
    pub created_by: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VariantConfig {
    pub name: String,
    pub prompt_version_id: String,
    pub description: String,
    pub traffic_weight: f64,
    pub is_control: bool,
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ABTest {
    pub test_id: String,
    pub name: String,
    pub description: String,
    pub prompt_id: String,
    pub variants: Vec<Variant>,
    pub traffic_allocation: TrafficAllocation,
    pub status: TestStatus,
    pub primary_metric: String,
    pub secondary_metrics: Vec<String>,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub min_sample_size: u64,
    pub confidence_level: f64,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub sample_count: u64,
    pub assignment_counts: HashMap<String, u64>,
}

impl TrafficAllocation {
    pub fn validate(&self) -> Result<(), AppError> {
        let total: f64 = self.weights.values().sum();
        if (total - 1.0).abs() > 0.0001 {
            return Err(AppError::Validation(format!(
                "Traffic weights must sum to 1.0, got {}",
                total
            )));
        }
        for (_, weight) in &self.weights {
            if *weight < 0.0 || *weight > 1.0 {
                return Err(AppError::Validation(format!(
                    "Traffic weight must be between 0 and 1, got {}",
                    weight
                )));
            }
        }
        Ok(())
    }
}

impl Variant {
    pub fn from_config(config: VariantConfig) -> Self {
        Self {
            variant_id: generate_id("var"),
            name: config.name,
            prompt_version_id: config.prompt_version_id,
            description: config.description,
            traffic_weight: config.traffic_weight,
            is_control: config.is_control,
            metadata: config.metadata,
        }
    }
}

impl ABTest {
    pub fn new(request: ABTestCreationRequest) -> Result<Self, AppError> {
        if request.name.is_empty() {
            return Err(AppError::Validation("Test name cannot be empty".to_string()));
        }
        if request.variants.is_empty() {
            return Err(AppError::Validation("At least one variant is required".to_string()));
        }
        
        let control_count = request.variants.iter().filter(|v| v.is_control).count();
        if control_count != 1 {
            return Err(AppError::Validation(format!(
                "Exactly one control variant is required, got {}",
                control_count
            )));
        }

        if request.confidence_level < 0.0 || request.confidence_level > 1.0 {
            return Err(AppError::Validation(format!(
                "Confidence level must be between 0 and 1, got {}",
                request.confidence_level
            )));
        }

        let variants: Vec<Variant> = request.variants
            .into_iter()
            .map(Variant::from_config)
            .collect();

        let weights: HashMap<String, f64> = variants
            .iter()
            .map(|v| (v.variant_id.clone(), v.traffic_weight))
            .collect();

        let mut traffic_allocation = request.traffic_allocation;
        traffic_allocation.weights = weights;
        traffic_allocation.validate()?;

        let assignment_counts: HashMap<String, u64> = variants
            .iter()
            .map(|v| (v.variant_id.clone(), 0))
            .collect();

        let now = Utc::now();
        Ok(Self {
            test_id: generate_id("test"),
            name: request.name,
            description: request.description,
            prompt_id: request.prompt_id,
            variants,
            traffic_allocation,
            status: TestStatus::Draft,
            primary_metric: request.primary_metric,
            secondary_metrics: request.secondary_metrics,
            start_time: request.start_time,
            end_time: request.end_time,
            min_sample_size: request.min_sample_size,
            confidence_level: request.confidence_level,
            created_by: request.created_by,
            created_at: now,
            updated_at: now,
            sample_count: 0,
            assignment_counts,
        })
    }

    pub fn start(&mut self) -> Result<(), AppError> {
        if self.status != TestStatus::Draft {
            return Err(AppError::Validation(format!(
                "Cannot start test from status: {:?}",
                self.status
            )));
        }
        self.status = TestStatus::Running;
        if self.start_time.is_none() {
            self.start_time = Some(Utc::now());
        }
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn pause(&mut self) -> Result<(), AppError> {
        if self.status != TestStatus::Running {
            return Err(AppError::Validation(format!(
                "Cannot pause test from status: {:?}",
                self.status
            )));
        }
        self.status = TestStatus::Paused;
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn resume(&mut self) -> Result<(), AppError> {
        if self.status != TestStatus::Paused {
            return Err(AppError::Validation(format!(
                "Cannot resume test from status: {:?}",
                self.status
            )));
        }
        self.status = TestStatus::Running;
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn stop(&mut self) -> Result<(), AppError> {
        if !matches!(self.status, TestStatus::Running | TestStatus::Paused) {
            return Err(AppError::Validation(format!(
                "Cannot stop test from status: {:?}",
                self.status
            )));
        }
        self.status = TestStatus::Stopped;
        self.end_time = Some(Utc::now());
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn complete(&mut self) -> Result<(), AppError> {
        if self.sample_count < self.min_sample_size {
            return Err(AppError::Validation(format!(
                "Cannot complete test: sample count {} < minimum {}",
                self.sample_count, self.min_sample_size
            )));
        }
        self.status = TestStatus::Completed;
        self.end_time = Some(Utc::now());
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn assign_variant(&mut self, user_id: Option<&str>, session_id: Option<&str>) -> Result<&Variant, AppError> {
        if self.status != TestStatus::Running {
            return Err(AppError::Validation(format!(
                "Cannot assign variant: test is not running (status: {:?})",
                self.status
            )));
        }

        let variant_id = match self.traffic_allocation.strategy {
            TrafficAllocationStrategy::Uniform | TrafficAllocationStrategy::Weighted => {
                self.random_assignment()
            }
            TrafficAllocationStrategy::UserSticky => {
                let uid = user_id.ok_or_else(|| AppError::Validation("User ID required for sticky allocation".to_string()))?;
                self.sticky_assignment(uid)
            }
            TrafficAllocationStrategy::SessionSticky => {
                let sid = session_id.ok_or_else(|| AppError::Validation("Session ID required for sticky allocation".to_string()))?;
                self.sticky_assignment(sid)
            }
        };

        self.sample_count += 1;
        *self.assignment_counts.get_mut(&variant_id).unwrap() += 1;

        self.variants
            .iter()
            .find(|v| v.variant_id == variant_id)
            .ok_or_else(|| AppError::Internal("Variant not found after assignment".to_string()))
    }

    fn random_assignment(&self) -> String {
        let mut rng = rand::thread_rng();
        let r: f64 = rng.gen_range(0.0..1.0);
        let mut cumulative = 0.0;

        for variant in &self.variants {
            cumulative += variant.traffic_weight;
            if r < cumulative {
                return variant.variant_id.clone();
            }
        }

        self.variants.last().unwrap().variant_id.clone()
    }

    fn sticky_assignment(&self, key: &str) -> String {
        let mut hash = 0u64;
        for byte in key.bytes() {
            hash = hash.wrapping_mul(31).wrapping_add(byte as u64);
        }
        if let Some(seed) = self.traffic_allocation.seed {
            hash = hash.wrapping_mul(seed);
        }
        
        let r = (hash % 10000) as f64 / 10000.0;
        let mut cumulative = 0.0;

        for variant in &self.variants {
            cumulative += variant.traffic_weight;
            if r < cumulative {
                return variant.variant_id.clone();
            }
        }

        self.variants.last().unwrap().variant_id.clone()
    }

    pub fn get_control_variant(&self) -> Option<&Variant> {
        self.variants.iter().find(|v| v.is_control)
    }

    pub fn get_variant(&self, variant_id: &str) -> Option<&Variant> {
        self.variants.iter().find(|v| v.variant_id == variant_id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_traffic_allocation_validation() {
        let mut weights = HashMap::new();
        weights.insert("v1".to_string(), 0.5);
        weights.insert("v2".to_string(), 0.5);
        
        let allocation = TrafficAllocation {
            strategy: TrafficAllocationStrategy::Weighted,
            weights,
            seed: None,
        };
        
        assert!(allocation.validate().is_ok());
    }

    #[test]
    fn test_traffic_allocation_validation_invalid_sum() {
        let mut weights = HashMap::new();
        weights.insert("v1".to_string(), 0.5);
        weights.insert("v2".to_string(), 0.6);
        
        let allocation = TrafficAllocation {
            strategy: TrafficAllocationStrategy::Weighted,
            weights,
            seed: None,
        };
        
        assert!(allocation.validate().is_err());
    }

    #[test]
    fn test_ab_test_creation() {
        let variant1 = VariantConfig {
            name: "Control".to_string(),
            prompt_version_id: "ver_1".to_string(),
            description: "Original prompt".to_string(),
            traffic_weight: 0.5,
            is_control: true,
            metadata: HashMap::new(),
        };

        let variant2 = VariantConfig {
            name: "Treatment".to_string(),
            prompt_version_id: "ver_2".to_string(),
            description: "Improved prompt".to_string(),
            traffic_weight: 0.5,
            is_control: false,
            metadata: HashMap::new(),
        };

        let request = ABTestCreationRequest {
            name: "Prompt Optimization Test".to_string(),
            description: "Testing new prompt version".to_string(),
            prompt_id: "prompt_1".to_string(),
            variants: vec![variant1, variant2],
            traffic_allocation: TrafficAllocation {
                strategy: TrafficAllocationStrategy::Weighted,
                weights: HashMap::new(),
                seed: None,
            },
            primary_metric: "accuracy".to_string(),
            secondary_metrics: vec!["latency".to_string()],
            start_time: None,
            end_time: None,
            min_sample_size: 1000,
            confidence_level: 0.95,
            created_by: "test_user".to_string(),
        };

        let test = ABTest::new(request).unwrap();
        assert!(test.test_id.starts_with("test_"));
        assert_eq!(test.status, TestStatus::Draft);
        assert_eq!(test.variants.len(), 2);
        assert!(test.get_control_variant().is_some());
    }

    #[test]
    fn test_ab_test_lifecycle() {
        let variant1 = VariantConfig {
            name: "Control".to_string(),
            prompt_version_id: "ver_1".to_string(),
            description: "Original".to_string(),
            traffic_weight: 0.5,
            is_control: true,
            metadata: HashMap::new(),
        };

        let variant2 = VariantConfig {
            name: "Treatment".to_string(),
            prompt_version_id: "ver_2".to_string(),
            description: "Improved".to_string(),
            traffic_weight: 0.5,
            is_control: false,
            metadata: HashMap::new(),
        };

        let request = ABTestCreationRequest {
            name: "Test".to_string(),
            description: "Test".to_string(),
            prompt_id: "prompt_1".to_string(),
            variants: vec![variant1, variant2],
            traffic_allocation: TrafficAllocation {
                strategy: TrafficAllocationStrategy::Uniform,
                weights: HashMap::new(),
                seed: Some(42),
            },
            primary_metric: "accuracy".to_string(),
            secondary_metrics: vec![],
            start_time: None,
            end_time: None,
            min_sample_size: 10,
            confidence_level: 0.95,
            created_by: "test".to_string(),
        };

        let mut test = ABTest::new(request).unwrap();
        
        assert!(test.start().is_ok());
        assert_eq!(test.status, TestStatus::Running);
        
        assert!(test.pause().is_ok());
        assert_eq!(test.status, TestStatus::Paused);
        
        assert!(test.resume().is_ok());
        assert_eq!(test.status, TestStatus::Running);
        
        for _ in 0..15 {
            test.assign_variant(Some("user1"), None).unwrap();
        }
        
        assert_eq!(test.sample_count, 15);
        assert!(test.complete().is_ok());
        assert_eq!(test.status, TestStatus::Completed);
    }

    #[test]
    fn test_variant_assignment_sticky() {
        let variant1 = VariantConfig {
            name: "Control".to_string(),
            prompt_version_id: "ver_1".to_string(),
            description: "Original".to_string(),
            traffic_weight: 0.5,
            is_control: true,
            metadata: HashMap::new(),
        };

        let variant2 = VariantConfig {
            name: "Treatment".to_string(),
            prompt_version_id: "ver_2".to_string(),
            description: "Improved".to_string(),
            traffic_weight: 0.5,
            is_control: false,
            metadata: HashMap::new(),
        };

        let request = ABTestCreationRequest {
            name: "Sticky Test".to_string(),
            description: "Test".to_string(),
            prompt_id: "prompt_1".to_string(),
            variants: vec![variant1, variant2],
            traffic_allocation: TrafficAllocation {
                strategy: TrafficAllocationStrategy::UserSticky,
                weights: HashMap::new(),
                seed: Some(123),
            },
            primary_metric: "accuracy".to_string(),
            secondary_metrics: vec![],
            start_time: None,
            end_time: None,
            min_sample_size: 100,
            confidence_level: 0.95,
            created_by: "test".to_string(),
        };

        let mut test = ABTest::new(request).unwrap();
        test.start().unwrap();

        let v1 = test.assign_variant(Some("user_123"), None).unwrap();
        let v2 = test.assign_variant(Some("user_123"), None).unwrap();
        
        assert_eq!(v1.variant_id, v2.variant_id);
    }
}
