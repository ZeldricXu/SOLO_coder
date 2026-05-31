use serde::{Serialize, Deserialize};
use chrono::{DateTime, Utc};
use uuid::Uuid;
use serde_json::Value;
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum EntityStatus {
    Pending,
    Active,
    Inactive,
    Failed,
    Deleted,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Entity {
    pub id: String,
    pub entity_type: String,
    pub status: EntityStatus,
    pub attributes: HashMap<String, Value>,
    pub labels: HashMap<String, String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl Entity {
    pub fn new(entity_type: impl Into<String>) -> Self {
        let now = Utc::now();
        Self {
            id: format!("ent_{}", Uuid::new_v4().simple()),
            entity_type: entity_type.into(),
            status: EntityStatus::Pending,
            attributes: HashMap::new(),
            labels: HashMap::new(),
            created_at: now,
            updated_at: now,
        }
    }

    pub fn with_id(mut self, id: impl Into<String>) -> Self {
        self.id = id.into();
        self
    }

    pub fn with_attribute(mut self, key: impl Into<String>, value: Value) -> Self {
        self.attributes.insert(key.into(), value);
        self
    }

    pub fn with_label(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.labels.insert(key.into(), value.into());
        self
    }

    pub fn set_status(&mut self, status: EntityStatus) {
        self.status = status;
        self.updated_at = Utc::now();
    }

    pub fn set_attribute(&mut self, key: impl Into<String>, value: Value) {
        self.attributes.insert(key.into(), value);
        self.updated_at = Utc::now();
    }

    pub fn get_attribute(&self, key: &str) -> Option<&Value> {
        self.attributes.get(key)
    }

    pub fn is_active(&self) -> bool {
        matches!(self.status, EntityStatus::Active)
    }

    pub fn touch(&mut self) {
        self.updated_at = Utc::now();
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityQuery {
    pub entity_type: Option<String>,
    pub status: Option<EntityStatus>,
    pub labels: Option<HashMap<String, String>>,
    pub page: u32,
    pub page_size: u32,
}

impl Default for EntityQuery {
    fn default() -> Self {
        Self {
            entity_type: None,
            status: None,
            labels: None,
            page: 1,
            page_size: 20,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityCreateRequest {
    pub entity_type: String,
    pub attributes: HashMap<String, Value>,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityUpdateRequest {
    pub status: Option<EntityStatus>,
    pub attributes: Option<HashMap<String, Value>>,
    pub labels: Option<HashMap<String, String>>,
}
