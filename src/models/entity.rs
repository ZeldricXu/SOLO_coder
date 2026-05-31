use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Entity {
    pub id: String,
    pub r#type: String,
    pub status: String,
    pub attributes: std::collections::HashMap<String, serde_json::Value>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl Entity {
    pub fn new(r#type: impl Into<String>) -> Self {
        let now = Utc::now();
        Self {
            id: crate::models::IdGenerator::generate("ent"),
            r#type: r#type.into(),
            status: "active".to_string(),
            attributes: std::collections::HashMap::new(),
            created_at: now,
            updated_at: now,
        }
    }

    pub fn with_attrs(mut self, attrs: std::collections::HashMap<String, serde_json::Value>) -> Self {
        self.attributes = attrs;
        self
    }

    pub fn update_status(&mut self, status: impl Into<String>) {
        self.status = status.into();
        self.updated_at = Utc::now();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_entity_creation() {
        let entity = Entity::new("resource");
        assert_eq!(entity.r#type, "resource");
        assert_eq!(entity.status, "active");
        assert!(entity.id.starts_with("ent_"));
    }
}
