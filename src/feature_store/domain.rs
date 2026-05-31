use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum FeatureType {
    Int,
    Float,
    String,
    Bool,
    Array,
    Object,
    Embedding,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct FeatureSchema {
    pub name: String,
    pub feature_type: FeatureType,
    pub description: Option<String>,
    pub dimensions: Option<usize>,
    pub nullable: bool,
    pub default_value: Option<serde_json::Value>,
}

impl FeatureSchema {
    pub fn new(name: impl Into<String>, feature_type: FeatureType) -> Self {
        Self {
            name: name.into(),
            feature_type,
            description: None,
            dimensions: None,
            nullable: false,
            default_value: None,
        }
    }

    pub fn with_description(mut self, desc: impl Into<String>) -> Self {
        self.description = Some(desc.into());
        self
    }

    pub fn with_dimensions(mut self, dims: usize) -> Self {
        self.dimensions = Some(dims);
        self
    }

    pub fn with_nullable(mut self, nullable: bool) -> Self {
        self.nullable = nullable;
        self
    }

    pub fn validate_value(&self, value: &FeatureValue) -> bool {
        match (&self.feature_type, value) {
            (FeatureType::Int, FeatureValue::Int(_)) => true,
            (FeatureType::Float, FeatureValue::Float(_)) => true,
            (FeatureType::Float, FeatureValue::Int(_)) => true,
            (FeatureType::String, FeatureValue::String(_)) => true,
            (FeatureType::Bool, FeatureValue::Bool(_)) => true,
            (FeatureType::Array, FeatureValue::Array(_)) => true,
            (FeatureType::Object, FeatureValue::Object(_)) => true,
            (FeatureType::Embedding, FeatureValue::Embedding(v)) => {
                if let Some(dims) = self.dimensions {
                    v.len() == dims
                } else {
                    true
                }
            }
            _ => false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "value")]
pub enum FeatureValue {
    Int(i64),
    Float(f64),
    String(String),
    Bool(bool),
    Array(Vec<FeatureValue>),
    Object(serde_json::Value),
    Embedding(Vec<f32>),
    Null,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Feature {
    pub feature_id: String,
    pub name: String,
    pub schema: FeatureSchema,
    pub entity_type: String,
    pub source: String,
    pub ttl_seconds: Option<u64>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub version: u32,
}

impl Feature {
    pub fn new(
        name: impl Into<String>,
        schema: FeatureSchema,
        entity_type: impl Into<String>,
        source: impl Into<String>,
    ) -> Self {
        let now = Utc::now();
        Self {
            feature_id: format!("feat_{}", Uuid::new_v4().simple()),
            name: name.into(),
            schema,
            entity_type: entity_type.into(),
            source: source.into(),
            ttl_seconds: None,
            created_at: now,
            updated_at: now,
            version: 1,
        }
    }

    pub fn with_ttl(mut self, ttl: u64) -> Self {
        self.ttl_seconds = Some(ttl);
        self
    }

    pub fn new_version(&self) -> Self {
        Self {
            feature_id: self.feature_id.clone(),
            name: self.name.clone(),
            schema: self.schema.clone(),
            entity_type: self.entity_type.clone(),
            source: self.source.clone(),
            ttl_seconds: self.ttl_seconds,
            created_at: self.created_at,
            updated_at: Utc::now(),
            version: self.version + 1,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureRecord {
    pub entity_id: String,
    pub feature_name: String,
    pub value: FeatureValue,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OnlineFeatureResponse {
    pub entity_id: String,
    pub features: std::collections::HashMap<String, FeatureValue>,
    pub timestamp: DateTime<Utc>,
    pub source: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OfflineBackfillRequest {
    pub feature_names: Vec<String>,
    pub entity_ids: Vec<String>,
    pub start_time: DateTime<Utc>,
    pub end_time: DateTime<Utc>,
    pub interval_seconds: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OfflineFeaturePoint {
    pub entity_id: String,
    pub feature_name: String,
    pub value: FeatureValue,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureRegistrationRequest {
    pub name: String,
    pub entity_type: String,
    pub source: String,
    pub schema: FeatureSchema,
    pub ttl_seconds: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureIngestRequest {
    pub records: Vec<FeatureRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureOnlineFetchRequest {
    pub entity_id: String,
    pub feature_names: Vec<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_feature_schema_validation() {
        let schema = FeatureSchema::new("age", FeatureType::Int);
        assert!(schema.validate_value(&FeatureValue::Int(25)));
        assert!(!schema.validate_value(&FeatureValue::String("25".to_string())));
    }

    #[test]
    fn test_embedding_schema_validation() {
        let schema = FeatureSchema::new("embedding", FeatureType::Embedding)
            .with_dimensions(4);
        
        assert!(schema.validate_value(&FeatureValue::Embedding(vec![1.0, 2.0, 3.0, 4.0])));
        assert!(!schema.validate_value(&FeatureValue::Embedding(vec![1.0, 2.0, 3.0])));
    }

    #[test]
    fn test_feature_creation() {
        let schema = FeatureSchema::new("user_age", FeatureType::Int);
        let feature = Feature::new("user_age", schema, "user", "mysql.users")
            .with_ttl(86400);
        
        assert!(feature.feature_id.starts_with("feat_"));
        assert_eq!(feature.name, "user_age");
        assert_eq!(feature.ttl_seconds, Some(86400));
        assert_eq!(feature.version, 1);
    }

    #[test]
    fn test_feature_new_version() {
        let schema = FeatureSchema::new("user_age", FeatureType::Int);
        let feature = Feature::new("user_age", schema, "user", "mysql.users");
        let v2 = feature.new_version();
        
        assert_eq!(v2.feature_id, feature.feature_id);
        assert_eq!(v2.version, 2);
        assert!(v2.updated_at > feature.created_at);
    }
}
