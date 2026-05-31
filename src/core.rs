use crate::config::CoreConfig;
use crate::error::SystemError;
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, error, info, warn};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataRecord {
    pub id: String,
    pub timestamp: DateTime<Utc>,
    pub source: String,
    pub data_type: String,
    pub payload: Value,
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StandardizedData {
    pub id: String,
    pub timestamp: DateTime<Utc>,
    pub source: String,
    pub data_type: String,
    pub normalized_payload: Map<String, Value>,
    pub schema_version: String,
    pub validation_passed: bool,
    pub validation_errors: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataSchema {
    pub name: String,
    pub version: String,
    pub fields: Vec<FieldDefinition>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FieldDefinition {
    pub name: String,
    pub field_type: FieldType,
    pub required: bool,
    pub default_value: Option<Value>,
    pub validation_rules: Vec<ValidationRule>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum FieldType {
    String,
    Integer,
    Float,
    Boolean,
    DateTime,
    Array,
    Object,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValidationRule {
    pub rule_type: RuleType,
    pub parameters: Map<String, Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RuleType {
    MinLength,
    MaxLength,
    MinValue,
    MaxValue,
    Pattern,
    Enum,
    Custom,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Transformation {
    pub name: String,
    pub input_fields: Vec<String>,
    pub output_field: String,
    pub operation: TransformationOperation,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TransformationOperation {
    Rename,
    Cast { target_type: FieldType },
    Format { template: String },
    Compute { expression: String },
    Extract { regex: String },
    Concat { separator: String },
    Split { separator: String },
    Trim,
    Lowercase,
    Uppercase,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessingStats {
    pub total_records: u64,
    pub successful_records: u64,
    pub failed_records: u64,
    pub average_processing_time_ms: f64,
    pub schema_validations_passed: u64,
    pub schema_validations_failed: u64,
}

type DataProcessor = Arc<dyn Fn(DataRecord) -> Result<StandardizedData, SystemError> + Send + Sync>;

pub struct CoreProcessor {
    config: CoreConfig,
    schemas: Arc<DashMap<String, DataSchema>>,
    transformations: Arc<DashMap<String, Vec<Transformation>>>,
    custom_processors: Arc<DashMap<String, DataProcessor>>,
    stats: Arc<RwLock<ProcessingStatsInternal>>,
}

#[derive(Debug, Clone, Default)]
struct ProcessingStatsInternal {
    total_records: u64,
    successful_records: u64,
    failed_records: u64,
    total_processing_time_ms: u64,
    schema_validations_passed: u64,
    schema_validations_failed: u64,
}

impl CoreProcessor {
    pub fn new(config: &CoreConfig) -> Result<Self, SystemError> {
        let processor = Self {
            config: config.clone(),
            schemas: Arc::new(DashMap::new()),
            transformations: Arc::new(DashMap::new()),
            custom_processors: Arc::new(DashMap::new()),
            stats: Arc::new(RwLock::new(ProcessingStatsInternal::default())),
        };

        processor.register_default_schemas()?;
        Ok(processor)
    }

    fn register_default_schemas(&self) -> Result<(), SystemError> {
        let default_schema = DataSchema {
            name: "default".to_string(),
            version: "1.0".to_string(),
            fields: vec![
                FieldDefinition {
                    name: "id".to_string(),
                    field_type: FieldType::String,
                    required: true,
                    default_value: None,
                    validation_rules: vec![ValidationRule {
                        rule_type: RuleType::MinLength,
                        parameters: Map::from_iter(vec![("value".to_string(), Value::from(1))]),
                    }],
                },
                FieldDefinition {
                    name: "timestamp".to_string(),
                    field_type: FieldType::DateTime,
                    required: true,
                    default_value: None,
                    validation_rules: vec![],
                },
                FieldDefinition {
                    name: "data".to_string(),
                    field_type: FieldType::Object,
                    required: false,
                    default_value: Some(Value::Object(Map::new())),
                    validation_rules: vec![],
                },
            ],
        };

        self.schemas.insert("default".to_string(), default_schema);

        let sensor_schema = DataSchema {
            name: "sensor".to_string(),
            version: "1.0".to_string(),
            fields: vec![
                FieldDefinition {
                    name: "device_id".to_string(),
                    field_type: FieldType::String,
                    required: true,
                    default_value: None,
                    validation_rules: vec![],
                },
                FieldDefinition {
                    name: "temperature".to_string(),
                    field_type: FieldType::Float,
                    required: false,
                    default_value: Some(Value::from(0.0)),
                    validation_rules: vec![
                        ValidationRule {
                            rule_type: RuleType::MinValue,
                            parameters: Map::from_iter(vec![("value".to_string(), Value::from(-273.15))]),
                        },
                        ValidationRule {
                            rule_type: RuleType::MaxValue,
                            parameters: Map::from_iter(vec![("value".to_string(), Value::from(1000.0))]),
                        },
                    ],
                },
                FieldDefinition {
                    name: "humidity".to_string(),
                    field_type: FieldType::Float,
                    required: false,
                    default_value: Some(Value::from(0.0)),
                    validation_rules: vec![
                        ValidationRule {
                            rule_type: RuleType::MinValue,
                            parameters: Map::from_iter(vec![("value".to_string(), Value::from(0.0))]),
                        },
                        ValidationRule {
                            rule_type: RuleType::MaxValue,
                            parameters: Map::from_iter(vec![("value".to_string(), Value::from(100.0))]),
                        },
                    ],
                },
            ],
        };

        self.schemas.insert("sensor".to_string(), sensor_schema);
        Ok(())
    }

    pub fn register_schema(&self, schema: DataSchema) -> Result<(), SystemError> {
        self.schemas.insert(schema.name.clone(), schema);
        Ok(())
    }

    pub fn register_transformation(&self, data_type: String, transformations: Vec<Transformation>) {
        self.transformations.insert(data_type, transformations);
    }

    pub fn register_custom_processor<F>(&self, name: String, processor: F)
    where
        F: Fn(DataRecord) -> Result<StandardizedData, SystemError> + Send + Sync + 'static,
    {
        self.custom_processors.insert(name, Arc::new(processor));
    }

    pub async fn process(&self, record: DataRecord) -> Result<StandardizedData, SystemError> {
        let start_time = std::time::Instant::now();
        let mut stats = self.stats.write().await;
        stats.total_records += 1;
        drop(stats);

        let result = self.process_internal(record).await;

        let mut stats = self.stats.write().await;
        let elapsed = start_time.elapsed().as_millis() as u64;
        stats.total_processing_time_ms += elapsed;

        match &result {
            Ok(data) => {
                stats.successful_records += 1;
                if data.validation_passed {
                    stats.schema_validations_passed += 1;
                } else {
                    stats.schema_validations_failed += 1;
                }
            }
            Err(_) => {
                stats.failed_records += 1;
            }
        }

        result
    }

    async fn process_internal(&self, record: DataRecord) -> Result<StandardizedData, SystemError> {
        if let Some(processor) = self.custom_processors.get(&record.data_type) {
            return processor(record);
        }

        let mut normalized = self.normalize(&record)?;

        let validation_errors = if self.config.validation_enabled {
            self.validate(&record.data_type, &normalized.normalized_payload)?
        } else {
            Vec::new()
        };

        normalized.validation_passed = validation_errors.is_empty();
        normalized.validation_errors = validation_errors;

        if let Some(transformations) = self.transformations.get(&record.data_type) {
            normalized = self.apply_transformations(normalized, &transformations)?;
        }

        Ok(normalized)
    }

    fn normalize(&self, record: &DataRecord) -> Result<StandardizedData, SystemError> {
        let mut normalized = Map::new();

        match &record.payload {
            Value::Object(map) => {
                for (key, value) in map {
                    normalized.insert(key.clone(), value.clone());
                }
            }
            _ => {
                normalized.insert("value".to_string(), record.payload.clone());
            }
        }

        Ok(StandardizedData {
            id: record.id.clone(),
            timestamp: record.timestamp,
            source: record.source.clone(),
            data_type: record.data_type.clone(),
            normalized_payload: normalized,
            schema_version: "1.0".to_string(),
            validation_passed: false,
            validation_errors: Vec::new(),
        })
    }

    fn validate(
        &self,
        data_type: &str,
        payload: &Map<String, Value>,
    ) -> Result<Vec<String>, SystemError> {
        let schema = self
            .schemas
            .get(data_type)
            .or_else(|| self.schemas.get("default"));

        let Some(schema) = schema else {
            return Ok(Vec::new());
        };

        let mut errors = Vec::new();

        for field in &schema.fields {
            let value = payload.get(&field.name);

            if value.is_none() {
                if field.required {
                    errors.push(format!("缺少必填字段: {}", field.name));
                }
                continue;
            }

            let value = value.unwrap();

            if let Err(e) = self.validate_field_type(value, &field.field_type) {
                errors.push(format!("字段 {} 类型错误: {}", field.name, e));
                continue;
            }

            for rule in &field.validation_rules {
                if let Err(e) = self.validate_rule(value, rule) {
                    errors.push(format!("字段 {} 验证失败: {}", field.name, e));
                }
            }
        }

        Ok(errors)
    }

    fn validate_field_type(&self, value: &Value, field_type: &FieldType) -> Result<(), String> {
        let is_valid = match field_type {
            FieldType::String => value.is_string(),
            FieldType::Integer => value.is_i64() || value.is_u64(),
            FieldType::Float => value.is_f64() || value.is_i64(),
            FieldType::Boolean => value.is_boolean(),
            FieldType::DateTime => {
                if let Some(s) = value.as_str() {
                    DateTime::parse_from_rfc3339(s).is_ok()
                } else {
                    false
                }
            }
            FieldType::Array => value.is_array(),
            FieldType::Object => value.is_object(),
        };

        if is_valid {
            Ok(())
        } else {
            Err(format!("期望类型 {:?}", field_type))
        }
    }

    fn validate_rule(&self, value: &Value, rule: &ValidationRule) -> Result<(), String> {
        match &rule.rule_type {
            RuleType::MinLength => {
                let min = rule
                    .parameters
                    .get("value")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(0) as usize;
                let len = value.as_str().map(|s| s.len()).unwrap_or(0);
                if len >= min {
                    Ok(())
                } else {
                    Err(format!("最小长度为 {}", min))
                }
            }
            RuleType::MaxLength => {
                let max = rule
                    .parameters
                    .get("value")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(u64::MAX) as usize;
                let len = value.as_str().map(|s| s.len()).unwrap_or(0);
                if len <= max {
                    Ok(())
                } else {
                    Err(format!("最大长度为 {}", max))
                }
            }
            RuleType::MinValue => {
                let min = rule
                    .parameters
                    .get("value")
                    .and_then(|v| v.as_f64())
                    .unwrap_or(f64::NEG_INFINITY);
                let val = value.as_f64().unwrap_or(f64::NAN);
                if val >= min {
                    Ok(())
                } else {
                    Err(format!("最小值为 {}", min))
                }
            }
            RuleType::MaxValue => {
                let max = rule
                    .parameters
                    .get("value")
                    .and_then(|v| v.as_f64())
                    .unwrap_or(f64::INFINITY);
                let val = value.as_f64().unwrap_or(f64::NAN);
                if val <= max {
                    Ok(())
                } else {
                    Err(format!("最大值为 {}", max))
                }
            }
            RuleType::Pattern => {
                if let (Some(pattern), Some(s)) = (
                    rule.parameters.get("pattern").and_then(|v| v.as_str()),
                    value.as_str(),
                ) {
                    let re = regex::Regex::new(pattern).map_err(|e| e.to_string())?;
                    if re.is_match(s) {
                        Ok(())
                    } else {
                        Err(format!("不匹配模式 {}", pattern))
                    }
                } else {
                    Err("无效的模式或值".to_string())
                }
            }
            RuleType::Enum => {
                if let (Some(allowed), Some(s)) = (
                    rule.parameters.get("values").and_then(|v| v.as_array()),
                    value.as_str(),
                ) {
                    if allowed.iter().any(|v| v.as_str() == Some(s)) {
                        Ok(())
                    } else {
                        Err(format!("不在允许的值列表中"))
                    }
                } else {
                    Err("无效的枚举值".to_string())
                }
            }
            RuleType::Custom => Ok(()),
        }
    }

    fn apply_transformations(
        &self,
        mut data: StandardizedData,
        transformations: &[Transformation],
    ) -> Result<StandardizedData, SystemError> {
        for transformation in transformations {
            self.apply_transformation(&mut data.normalized_payload, transformation)?;
        }
        Ok(data)
    }

    fn apply_transformation(
        &self,
        payload: &mut Map<String, Value>,
        transformation: &Transformation,
    ) -> Result<(), SystemError> {
        match &transformation.operation {
            TransformationOperation::Rename => {
                if let (Some(input), Some(first_input)) = (
                    transformation.input_fields.first(),
                    transformation.input_fields.first(),
                ) {
                    if let Some(value) = payload.remove(input) {
                        payload.insert(transformation.output_field.clone(), value);
                    }
                }
            }
            TransformationOperation::Cast { target_type } => {
                if let Some(input) = transformation.input_fields.first() {
                    if let Some(value) = payload.get(input).cloned() {
                        let casted = self.cast_value(&value, target_type)?;
                        payload.insert(transformation.output_field.clone(), casted);
                    }
                }
            }
            TransformationOperation::Trim => {
                if let Some(input) = transformation.input_fields.first() {
                    if let Some(Value::String(s)) = payload.get(input) {
                        payload.insert(
                            transformation.output_field.clone(),
                            Value::String(s.trim().to_string()),
                        );
                    }
                }
            }
            TransformationOperation::Lowercase => {
                if let Some(input) = transformation.input_fields.first() {
                    if let Some(Value::String(s)) = payload.get(input) {
                        payload.insert(
                            transformation.output_field.clone(),
                            Value::String(s.to_lowercase()),
                        );
                    }
                }
            }
            TransformationOperation::Uppercase => {
                if let Some(input) = transformation.input_fields.first() {
                    if let Some(Value::String(s)) = payload.get(input) {
                        payload.insert(
                            transformation.output_field.clone(),
                            Value::String(s.to_uppercase()),
                        );
                    }
                }
            }
            TransformationOperation::Concat { separator } => {
                let parts: Vec<String> = transformation
                    .input_fields
                    .iter()
                    .filter_map(|f| payload.get(f).and_then(|v| v.as_str()).map(|s| s.to_string()))
                    .collect();
                payload.insert(
                    transformation.output_field.clone(),
                    Value::String(parts.join(separator)),
                );
            }
            _ => {}
        }
        Ok(())
    }

    fn cast_value(&self, value: &Value, target_type: &FieldType) -> Result<Value, SystemError> {
        match target_type {
            FieldType::String => {
                let s = match value {
                    Value::String(s) => s.clone(),
                    Value::Number(n) => n.to_string(),
                    Value::Bool(b) => b.to_string(),
                    _ => serde_json::to_string(value)?,
                };
                Ok(Value::String(s))
            }
            FieldType::Integer => {
                let n = match value {
                    Value::Number(n) => n.as_i64().unwrap_or(0),
                    Value::String(s) => s.parse::<i64>().unwrap_or(0),
                    _ => 0,
                };
                Ok(Value::from(n))
            }
            FieldType::Float => {
                let n = match value {
                    Value::Number(n) => n.as_f64().unwrap_or(0.0),
                    Value::String(s) => s.parse::<f64>().unwrap_or(0.0),
                    _ => 0.0,
                };
                Ok(Value::from(n))
            }
            FieldType::Boolean => {
                let b = match value {
                    Value::Bool(b) => *b,
                    Value::String(s) => s.to_lowercase() == "true",
                    Value::Number(n) => n.as_i64().unwrap_or(0) != 0,
                    _ => false,
                };
                Ok(Value::Bool(b))
            }
            _ => Ok(value.clone()),
        }
    }

    pub async fn process_batch(&self, records: Vec<DataRecord>) -> Result<Vec<StandardizedData>, SystemError> {
        let mut results = Vec::with_capacity(records.len());
        for record in records {
            results.push(self.process(record).await?);
        }
        Ok(results)
    }

    pub async fn get_stats(&self) -> Result<ProcessingStats, SystemError> {
        let stats = self.stats.read().await;
        let average_time = if stats.total_records > 0 {
            stats.total_processing_time_ms as f64 / stats.total_records as f64
        } else {
            0.0
        };

        Ok(ProcessingStats {
            total_records: stats.total_records,
            successful_records: stats.successful_records,
            failed_records: stats.failed_records,
            average_processing_time_ms: average_time,
            schema_validations_passed: stats.schema_validations_passed,
            schema_validations_failed: stats.schema_validations_failed,
        })
    }

    pub fn get_schema(&self, name: &str) -> Option<DataSchema> {
        self.schemas.get(name).map(|s| s.clone())
    }

    pub fn list_schemas(&self) -> Vec<DataSchema> {
        self.schemas.iter().map(|s| s.clone()).collect()
    }
}

impl Clone for CoreProcessor {
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            schemas: self.schemas.clone(),
            transformations: self.transformations.clone(),
            custom_processors: self.custom_processors.clone(),
            stats: self.stats.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[tokio::test]
    async fn test_core_processor() {
        let config = CoreConfig {
            data_format: "json".to_string(),
            validation_enabled: true,
            transformation_rules: vec![],
        };

        let processor = CoreProcessor::new(&config).unwrap();

        let record = DataRecord {
            id: "test001".to_string(),
            timestamp: Utc::now(),
            source: "test".to_string(),
            data_type: "sensor".to_string(),
            payload: json!({
                "device_id": "sensor001",
                "temperature": 25.5,
                "humidity": 60.0
            }),
            metadata: HashMap::new(),
        };

        let result = processor.process(record).await.unwrap();
        assert!(result.validation_passed);
    }
}
