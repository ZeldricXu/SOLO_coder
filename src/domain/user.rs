use serde::{Deserialize, Serialize};
use std::collections::HashSet;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum PermissionLevel {
    Public,
    Internal,
    Confidential,
    Restricted,
}

impl PermissionLevel {
    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "public" => Some(PermissionLevel::Public),
            "internal" => Some(PermissionLevel::Internal),
            "confidential" => Some(PermissionLevel::Confidential),
            "restricted" => Some(PermissionLevel::Restricted),
            _ => None,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            PermissionLevel::Public => "public",
            PermissionLevel::Internal => "internal",
            PermissionLevel::Confidential => "confidential",
            PermissionLevel::Restricted => "restricted",
        }
    }

    pub fn can_access(&self, required: &PermissionLevel) -> bool {
        use PermissionLevel::*;
        match (self, required) {
            (Restricted, _) => true,
            (Confidential, Public | Internal | Confidential) => true,
            (Internal, Public | Internal) => true,
            (Public, Public) => true,
            _ => false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct User {
    pub user_id: String,
    pub username: String,
    pub permission_level: PermissionLevel,
    pub roles: HashSet<String>,
    pub departments: HashSet<String>,
    pub allowed_data_classes: HashSet<String>,
}

impl User {
    pub fn new(user_id: impl Into<String>, username: impl Into<String>, level: PermissionLevel) -> Self {
        Self {
            user_id: user_id.into(),
            username: username.into(),
            permission_level: level,
            roles: HashSet::new(),
            departments: HashSet::new(),
            allowed_data_classes: HashSet::new(),
        }
    }

    pub fn has_role(&self, role: &str) -> bool {
        self.roles.contains(role)
    }

    pub fn has_department(&self, dept: &str) -> bool {
        self.departments.contains(dept)
    }

    pub fn can_access_data_class(&self, class: &str) -> bool {
        self.allowed_data_classes.is_empty() || self.allowed_data_classes.contains(class)
    }

    pub fn add_role(&mut self, role: impl Into<String>) {
        self.roles.insert(role.into());
    }

    pub fn add_department(&mut self, dept: impl Into<String>) {
        self.departments.insert(dept.into());
    }

    pub fn add_allowed_data_class(&mut self, class: impl Into<String>) {
        self.allowed_data_classes.insert(class.into());
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthContext {
    pub user: User,
    pub session_id: String,
    pub authenticated_at: chrono::DateTime<chrono::Utc>,
    pub expires_at: chrono::DateTime<chrono::Utc>,
}

impl AuthContext {
    pub fn new(user: User, session_ttl_seconds: i64) -> Self {
        let now = chrono::Utc::now();
        Self {
            user,
            session_id: uuid::Uuid::new_v4().to_string(),
            authenticated_at: now,
            expires_at: now + chrono::Duration::seconds(session_ttl_seconds),
        }
    }

    pub fn is_expired(&self) -> bool {
        chrono::Utc::now() > self.expires_at
    }

    pub fn can_access(&self, required_level: &PermissionLevel) -> bool {
        !self.is_expired() && self.user.permission_level.can_access(required_level)
    }
}
