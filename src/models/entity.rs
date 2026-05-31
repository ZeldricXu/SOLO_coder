use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum EntityType {
    Record,
    Document,
    Model,
    Feature,
    Prompt,
    Job,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum EntityStatus {
    Pending,
    Running,
    Succeeded,
    Failed,
    Cancelled,
    Archived,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Entity {
    pub id: String,
    pub r#type: EntityType,
    pub status: EntityStatus,
    pub attributes: HashMap<String, serde_json::Value>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl Entity {
    pub fn new(r#type: EntityType) -> Self {
        let now = Utc::now();
        Self {
            id: format!("ent_{}", Uuid::new_v4().simple()),
            r#type,
            status: EntityStatus::Pending,
            attributes: HashMap::new(),
            created_at: now,
            updated_at: now,
        }
    }

    pub fn with_attributes(mut self, attrs: HashMap<String, serde_json::Value>) -> Self {
        self.attributes = attrs;
        self
    }

    pub fn update_status(&mut self, status: EntityStatus) {
        self.status = status;
        self.updated_at = Utc::now();
    }

    pub fn set_attribute<K: Into<String>, V: Into<serde_json::Value>>(&mut self, key: K, value: V) {
        self.attributes.insert(key.into(), value.into());
        self.updated_at = Utc::now();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_entity_creation() {
        let entity = Entity::new(EntityType::Record);
        assert!(entity.id.starts_with("ent_"));
        assert_eq!(entity.status, EntityStatus::Pending);
        assert_eq!(entity.r#type, EntityType::Record);
    }

    #[test]
    fn test_entity_status_update() {
        let mut entity = Entity::new(EntityType::Document);
        entity.update_status(EntityStatus::Running);
        assert_eq!(entity.status, EntityStatus::Running);
    }
}
