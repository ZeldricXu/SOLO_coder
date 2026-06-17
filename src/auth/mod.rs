use std::collections::HashSet;

use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;
use uuid::Uuid;

use crate::config::AuthConfig;

#[derive(Error, Debug)]
pub enum AuthError {
    #[error("Invalid token: {0}")]
    InvalidToken(String),

    #[error("Token expired")]
    Expired,

    #[error("Token not yet valid")]
    NotYetValid,

    #[error("Permission denied: {0}")]
    PermissionDenied(String),

    #[error("User not found: {0}")]
    UserNotFound(String),

    #[error("Invalid signature")]
    InvalidSignature,

    #[error("Share link expired or revoked")]
    ShareLinkInvalid,

    #[error("Invalid credentials")]
    InvalidCredentials,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Role {
    Owner,
    Editor,
    Viewer,
    Commenter,
}

impl Role {
    pub fn can_read(&self) -> bool {
        true
    }

    pub fn can_write(&self) -> bool {
        matches!(self, Role::Owner | Role::Editor)
    }

    pub fn can_admin(&self) -> bool {
        matches!(self, Role::Owner)
    }

    pub fn can_comment(&self) -> bool {
        matches!(self, Role::Owner | Role::Editor | Role::Commenter)
    }

    pub fn to_str(&self) -> &'static str {
        match self {
            Role::Owner => "owner",
            Role::Editor => "editor",
            Role::Viewer => "viewer",
            Role::Commenter => "commenter",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s.to_lowercase().as_str() {
            "owner" => Some(Role::Owner),
            "editor" => Some(Role::Editor),
            "viewer" => Some(Role::Viewer),
            "commenter" => Some(Role::Commenter),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JwtClaims {
    pub sub: String,
    pub user_id: String,
    pub email: Option<String>,
    pub name: Option<String>,
    pub iss: String,
    pub aud: String,
    pub exp: u64,
    pub iat: u64,
    pub nbf: Option<u64>,
    pub jti: String,
    pub scope: Option<String>,
    pub document_permissions: Vec<DocumentPermission>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DocumentPermission {
    pub document_id: Uuid,
    pub role: Role,
    pub granted_by: Option<String>,
    pub granted_at: u64,
    pub expires_at: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShareLink {
    pub id: Uuid,
    pub document_id: Uuid,
    pub created_by: String,
    pub role: Role,
    pub token: String,
    pub expires_at: Option<chrono::DateTime<chrono::Utc>>,
    pub max_uses: Option<u32>,
    pub use_count: u32,
    pub is_revoked: bool,
    pub requires_email: Option<String>,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShareTokenClaims {
    pub sub: String,
    pub share_id: Uuid,
    pub document_id: Uuid,
    pub role: String,
    pub created_by: String,
    pub exp: u64,
    pub iat: u64,
    pub jti: String,
}

pub struct AuthService {
    pub config: AuthConfig,
    encoding_key: EncodingKey,
    decoding_key: DecodingKey,
    share_tokens: std::sync::Mutex<HashSet<String>>,
}

impl AuthService {
    pub fn new(config: AuthConfig) -> Self {
        let encoding_key = EncodingKey::from_secret(config.jwt_secret.as_bytes());
        let decoding_key = DecodingKey::from_secret(config.jwt_secret.as_bytes());

        Self {
            config,
            encoding_key,
            decoding_key,
            share_tokens: std::sync::Mutex::new(HashSet::new()),
        }
    }

    pub fn issue_token(
        &self,
        user_id: String,
        email: Option<String>,
        name: Option<String>,
        document_permissions: Vec<DocumentPermission>,
    ) -> Result<String, AuthError> {
        let now = chrono::Utc::now().timestamp() as u64;
        let jti = Uuid::new_v4().to_string();

        let claims = JwtClaims {
            sub: user_id.clone(),
            user_id,
            email,
            name,
            iss: self.config.jwt_issuer.clone(),
            aud: "collab-engine".to_string(),
            exp: now + self.config.jwt_expiry_secs,
            iat: now,
            nbf: Some(now),
            jti,
            scope: Some("collab:read collab:write".to_string()),
            document_permissions,
        };

        encode(&Header::default(), &claims, &self.encoding_key)
            .map_err(|e| AuthError::InvalidToken(e.to_string()))
    }

    pub fn verify_token(&self, token: &str) -> Result<JwtClaims, AuthError> {
        let mut validation = Validation::default();
        validation.set_audience(&["collab-engine"]);
        validation.set_issuer(&[self.config.jwt_issuer.as_str()]);
        validation.leeway = 30;

        decode::<JwtClaims>(token, &self.decoding_key, &validation)
            .map(|d| d.claims)
            .map_err(|e| match e.kind() {
                jsonwebtoken::errors::ErrorKind::ExpiredSignature => AuthError::Expired,
                jsonwebtoken::errors::ErrorKind::ImmatureSignature => AuthError::NotYetValid,
                jsonwebtoken::errors::ErrorKind::InvalidSignature => AuthError::InvalidSignature,
                _ => AuthError::InvalidToken(e.to_string()),
            })
    }

    pub fn generate_share_token(
        &self,
        document_id: Uuid,
        created_by: String,
        role: Role,
        expires_at: Option<chrono::DateTime<chrono::Utc>>,
    ) -> Result<(String, ShareTokenClaims), AuthError> {
        let share_id = Uuid::new_v4();
        let now = chrono::Utc::now().timestamp() as u64;

        let exp = match expires_at {
            Some(dt) => dt.timestamp() as u64,
            None => now + self.config.share_token_expiry_secs,
        };

        let jti = Uuid::new_v4().to_string();

        let claims = ShareTokenClaims {
            sub: format!("share:{}", share_id),
            share_id,
            document_id,
            role: role.to_str().to_string(),
            created_by: created_by.clone(),
            exp,
            iat: now,
            jti: jti.clone(),
        };

        let token = encode(&Header::default(), &claims, &self.encoding_key)
            .map_err(|e| AuthError::InvalidToken(e.to_string()))?;

        let mut hasher = Sha256::new();
        hasher.update(token.as_bytes());
        let hash = format!("{:x}", hasher.finalize());
        self.share_tokens.lock().unwrap().insert(hash);

        Ok((token, claims))
    }

    pub fn verify_share_token(&self, token: &str) -> Result<ShareTokenClaims, AuthError> {
        let mut hasher = Sha256::new();
        hasher.update(token.as_bytes());
        let hash = format!("{:x}", hasher.finalize());

        if !self.share_tokens.lock().unwrap().contains(&hash) {
            return Err(AuthError::ShareLinkInvalid);
        }

        let mut validation = Validation::default();
        validation.set_issuer(&[self.config.jwt_issuer.as_str()]);
        validation.leeway = 30;
        validation.validate_aud = false;

        decode::<ShareTokenClaims>(token, &self.decoding_key, &validation)
            .map(|d| d.claims)
            .map_err(|e| match e.kind() {
                jsonwebtoken::errors::ErrorKind::ExpiredSignature => AuthError::Expired,
                _ => AuthError::ShareLinkInvalid,
            })
    }

    pub fn revoke_share_token(&self, token: &str) {
        let mut hasher = Sha256::new();
        hasher.update(token.as_bytes());
        let hash = format!("{:x}", hasher.finalize());
        self.share_tokens.lock().unwrap().remove(&hash);
    }

    pub fn check_document_permission(
        claims: &JwtClaims,
        document_id: &Uuid,
        required: &Role,
    ) -> Result<(), AuthError> {
        if let Some(perm) = claims.document_permissions.iter().find(|p| &p.document_id == document_id) {
            match (&perm.role, required) {
                (Role::Owner, _) => Ok(()),
                (Role::Editor, Role::Editor) | (Role::Editor, Role::Viewer) | (Role::Editor, Role::Commenter) => Ok(()),
                (Role::Commenter, Role::Commenter) | (Role::Commenter, Role::Viewer) => Ok(()),
                (Role::Viewer, Role::Viewer) => Ok(()),
                _ => Err(AuthError::PermissionDenied(format!(
                    "Required {:?} but has {:?}",
                    required, perm.role
                ))),
            }
        } else {
            Err(AuthError::PermissionDenied(format!(
                "No permissions for document {}",
                document_id
            )))
        }
    }

    pub fn extract_bearer_token(auth_header: &str) -> Result<&str, AuthError> {
        if let Some(token) = auth_header.strip_prefix("Bearer ") {
            Ok(token.trim())
        } else {
            Err(AuthError::InvalidToken("Missing Bearer prefix".into()))
        }
    }
}
