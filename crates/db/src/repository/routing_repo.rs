use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use uuid::Uuid;

use crate::error::DbResult;

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct RoutingRule {
    pub id: Uuid,
    pub model_name: String,
    pub strategy: String,
    pub config: Option<Value>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateRoutingRuleParams {
    pub model_name: String,
    pub strategy: String,
    pub config: Option<Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateRoutingRuleParams {
    pub strategy: Option<String>,
    pub config: Option<Option<Value>>,
}

#[async_trait]
pub trait RoutingRepository: Send + Sync {
    async fn create_routing_rule(&self, params: &CreateRoutingRuleParams) -> DbResult<RoutingRule>;
    async fn get_routing_rule_by_id(&self, id: Uuid) -> DbResult<Option<RoutingRule>>;
    async fn get_routing_rule_by_model_name(&self, model_name: &str) -> DbResult<Option<RoutingRule>>;
    async fn list_routing_rules(&self, strategy: Option<&str>, limit: i64, offset: i64) -> DbResult<Vec<RoutingRule>>;
    async fn update_routing_rule(&self, id: Uuid, params: &UpdateRoutingRuleParams) -> DbResult<RoutingRule>;
    async fn upsert_routing_rule(&self, params: &CreateRoutingRuleParams) -> DbResult<RoutingRule>;
    async fn delete_routing_rule(&self, id: Uuid) -> DbResult<()>;
    async fn delete_routing_rule_by_model_name(&self, model_name: &str) -> DbResult<()>;
}
