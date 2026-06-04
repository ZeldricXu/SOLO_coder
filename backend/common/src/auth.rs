use crate::{config::AuthConfig, error::AppResult};
use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use chrono::{Duration, Utc};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use shared::UserRole;
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize)]
pub struct Claims {
    pub sub: Uuid,
    pub role: UserRole,
    pub exp: i64,
    pub iat: i64,
}

pub struct AuthService {
    encoding_key: EncodingKey,
    decoding_key: DecodingKey,
    config: AuthConfig,
}

impl Clone for AuthService {
    fn clone(&self) -> Self {
        Self {
            encoding_key: self.encoding_key.clone(),
            decoding_key: self.decoding_key.clone(),
            config: self.config.clone(),
        }
    }
}

impl AuthService {
    pub fn new(config: AuthConfig) -> Self {
        Self {
            encoding_key: EncodingKey::from_secret(config.jwt_secret.as_bytes()),
            decoding_key: DecodingKey::from_secret(config.jwt_secret.as_bytes()),
            config,
        }
    }

    pub fn hash_password(password: &str) -> AppResult<String> {
        let salt = SaltString::generate(&mut OsRng);
        let argon2 = Argon2::default();
        let password_hash = argon2
            .hash_password(password.as_bytes(), &salt)?
            .to_string();
        Ok(password_hash)
    }

    pub fn verify_password(password: &str, hash: &str) -> AppResult<bool> {
        let parsed_hash = PasswordHash::new(hash)?;
        Ok(Argon2::default()
            .verify_password(password.as_bytes(), &parsed_hash)
            .is_ok())
    }

    pub fn generate_token(&self, user_id: Uuid, role: UserRole) -> AppResult<String> {
        let now = Utc::now();
        let claims = Claims {
            sub: user_id,
            role,
            exp: (now + Duration::hours(self.config.jwt_expiry_hours)).timestamp(),
            iat: now.timestamp(),
        };

        let token = encode(&Header::default(), &claims, &self.encoding_key)?;
        Ok(token)
    }

    pub fn validate_token(&self, token: &str) -> AppResult<Claims> {
        let claims = decode::<Claims>(token, &self.decoding_key, &Validation::default())?;
        Ok(claims.claims)
    }
}
