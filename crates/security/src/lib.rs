pub mod auth;
pub mod rate_limit;
pub mod masking;
pub mod middleware;

pub use auth::{
    ApiKeyAuth, ApiKeyAuthenticator, AuthenticatedTenant, X_API_KEY_HEADER,
    extract_api_key, set_tenant_header,
};
pub use rate_limit::{
    RateLimitConfig, RateLimitExceededType, RateLimitResult, RateLimiter,
};
pub use masking::{
    Aes256GcmKey, DataMasker, MaskRule, decrypt_field, encrypt_field,
    mask_credit_card, mask_email, mask_id_card, mask_phone,
};
pub use middleware::{
    AuthLayer, AuthService, RateLimitLayer, RateLimitService,
    RequestLogLayer, RequestLogService, SecurityConfig, SecurityLayer,
};
