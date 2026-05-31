use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum EntityType {
    Record,
    Model,
    Feature,
    Prompt,
    Document,
    Job,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
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
    #[serde(rename = "type")]
    pub entity_type: EntityType,
    pub status: EntityStatus,
    pub attributes: serde_json::Value,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl Entity {
    pub fn new(entity_type: EntityType, attributes: serde_json::Value) -> Self {
        let now = Utc::now();
        Self {
            id: format!("ent_{}", Uuid::new_v4().simple()),
            entity_type,
            status: EntityStatus::Pending,
            attributes,
            created_at: now,
            updated_at: now,
        }
    }

    pub fn with_status(mut self, status: EntityStatus) -> Self {
        self.status = status;
        self.updated_at = Utc::now();
        self
    }

    pub fn update_attributes(&mut self, new_attrs: serde_json::Value) {
        if let serde_json::Value::Object(ref mut current) = self.attributes {
            if let serde_json::Value::Object(new) = new_attrs {
                for (k, v) in new {
                    current.insert(k, v);
                }
            }
        }
        self.updated_at = Utc::now();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn test_entity_creation() {
        let attrs = json!({"key": "value"});
        let entity = Entity::new(EntityType::Record, attrs.clone());
        
        assert!(entity.id.starts_with("ent_"));
        assert_eq!(entity.entity_type, EntityType::Record);
        assert_eq!(entity.status, EntityStatus::Pending);
        assert_eq!(entity.attributes, attrs);
    }

    #[test]
    fn test_entity_status_update() {
        let entity = Entity::new(EntityType::Model, json!({}));
        let updated = entity.with_status(EntityStatus::Running);
        
        assert_eq!(updated.status, EntityStatus::Running);
        assert!(updated.updated_at > updated.created_at);
    }
}
