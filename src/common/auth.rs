use hmac::{Hmac, Mac};
use sha2::Sha256;
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use chrono::{Utc, Duration};
use crate::common::error::{AppError, AppResult};

type HmacSha256 = Hmac<Sha256>;

pub struct SignatureValidator {
    secret_key: String,
    max_timestamp_skew: Duration,
}

impl SignatureValidator {
    pub fn new(secret_key: impl Into<String>) -> Self {
        Self {
            secret_key: secret_key.into(),
            max_timestamp_skew: Duration::minutes(5),
        }
    }

    pub fn validate(&self, payload: &str, signature: &str, timestamp: i64) -> AppResult<()> {
        let now = Utc::now().timestamp();
        if (now - timestamp).abs() > self.max_timestamp_skew.num_seconds() {
            return Err(AppError::Unauthorized("时间戳偏移过大".into()));
        }

        let mut mac = HmacSha256::new_from_slice(self.secret_key.as_bytes())
            .map_err(|_| AppError::Internal("HMAC初始化失败".into()))?;
        mac.update(format!("{}|{}", payload, timestamp).as_bytes());
        let expected = BASE64.encode(mac.finalize().into_bytes());

        if expected != signature {
            return Err(AppError::Unauthorized("签名验证失败".into()));
        }

        Ok(())
    }

    pub fn sign(&self, payload: &str) -> (String, i64) {
        let timestamp = Utc::now().timestamp();
        let mut mac = HmacSha256::new_from_slice(self.secret_key.as_bytes())
            .expect("HMAC初始化失败");
        mac.update(format!("{}|{}", payload, timestamp).as_bytes());
        let signature = BASE64.encode(mac.finalize().into_bytes());
        (signature, timestamp)
    }
}

pub struct AuthContext {
    pub device_id: String,
    pub tenant_id: String,
    pub permissions: Vec<String>,
    pub authenticated: bool,
}

impl AuthContext {
    pub fn new(device_id: impl Into<String>, tenant_id: impl Into<String>) -> Self {
        Self {
            device_id: device_id.into(),
            tenant_id: tenant_id.into(),
            permissions: vec![],
            authenticated: false,
        }
    }

    pub fn with_permission(mut self, perm: impl Into<String>) -> Self {
        self.permissions.push(perm.into());
        self
    }

    pub fn has_permission(&self, perm: &str) -> bool {
        self.permissions.contains(&perm.to_string()) || self.permissions.contains(&"*".to_string())
    }

    pub fn require_permission(&self, perm: &str) -> AppResult<()> {
        if !self.has_permission(perm) {
            return Err(AppError::Forbidden(format!("缺少权限: {}", perm)));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct DeviceCredentials {
    pub device_id: String,
    pub device_secret: String,
}

pub struct DeviceAuthenticator {
    credentials: std::collections::HashMap<String, String>,
}

impl DeviceAuthenticator {
    pub fn new() -> Self {
        Self {
            credentials: std::collections::HashMap::new(),
        }
    }

    pub fn register(&mut self, device_id: impl Into<String>, secret: impl Into<String>) {
        self.credentials.insert(device_id.into(), secret.into());
    }

    pub fn authenticate(&self, creds: &DeviceCredentials) -> AppResult<AuthContext> {
        let expected = self.credentials.get(&creds.device_id)
            .ok_or_else(|| AppError::Unauthorized("设备未注册".into()))?;

        if expected != &creds.device_secret {
            return Err(AppError::Unauthorized("设备密钥错误".into()));
        }

        Ok(AuthContext {
            device_id: creds.device_id.clone(),
            tenant_id: "default".into(),
            permissions: vec!["device:*".into()],
            authenticated: true,
        })
    }
}
