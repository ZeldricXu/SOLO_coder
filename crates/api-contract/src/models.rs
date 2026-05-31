use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::DateTime;
use std::collections::HashMap;
use serde_json::Value;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum SchemaType {
    OpenAPI,
    GraphQL,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum ValidationLevel {
    Strict,
    Lenient,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiContract {
    pub id: Uuid,
    pub name: String,
    pub schema_type: SchemaType,
    pub schema_content: String,
    pub version: String,
    pub created_at: DateTime<chrono::Utc>,
    pub updated_at: DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum Severity {
    Error,
    Warning,
    Info,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValidationIssue {
    pub path: String,
    pub message: String,
    pub severity: Severity,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValidationResult {
    pub passed: bool,
    pub issues: Vec<ValidationIssue>,
    pub duration_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MockEndpoint {
    pub path: String,
    pub method: String,
    pub response_body: Value,
    pub status_code: u16,
    pub response_headers: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MockServerConfig {
    pub server_id: Uuid,
    pub port: u16,
    pub endpoints: Vec<MockEndpoint>,
    pub is_running: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContractDiff {
    pub added_endpoints: Vec<String>,
    pub removed_endpoints: Vec<String>,
    pub modified_endpoints: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValidationRequest {
    pub schema_type: SchemaType,
    pub content: String,
}
