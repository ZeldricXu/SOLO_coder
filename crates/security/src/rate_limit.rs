use common::error::AppError;
use db::RedisClient;
use redis::{AsyncCommands, Script};
use serde::{Deserialize, Serialize};
use tracing::{debug, warn};
use uuid::Uuid;

const SLIDING_WINDOW_SCRIPT: &str = r#"
local key = KEYS[1]
local window_size = tonumber(ARGV[1])
local max_requests = tonumber(ARGV[2])
local current_time = tonumber(ARGV[3])

local window_start = current_time - window_size

redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start)

local count = redis.call('ZCARD', key)

if count < max_requests then
    redis.call('ZADD', key, current_time, current_time .. ':' .. math.random(1000000))
    redis.call('PEXPIRE', key, window_size)
    return {1, max_requests - count - 1}
else
    return {0, 0}
end
"#;

const GET_REMAINING_SCRIPT: &str = r#"
local key = KEYS[1]
local window_size = tonumber(ARGV[1])
local max_requests = tonumber(ARGV[2])
local current_time = tonumber(ARGV[3])

local window_start = current_time - window_size

redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start)

local count = redis.call('ZCARD', key)
local remaining = max_requests - count

if remaining < 0 then
    remaining = 0
end

return remaining
"#;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RateLimitConfig {
    pub qps_limit: u32,
    pub per_min_limit: u32,
    pub burst: u32,
}

impl Default for RateLimitConfig {
    fn default() -> Self {
        Self {
            qps_limit: 100,
            per_min_limit: 6000,
            burst: 200,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RateLimitResult {
    pub allowed: bool,
    pub qps_remaining: u32,
    pub per_min_remaining: u32,
    pub qps_limit: u32,
    pub per_min_limit: u32,
    pub exceeded_type: Option<RateLimitExceededType>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum RateLimitExceededType {
    Qps,
    PerMinute,
}

#[derive(Debug, Clone)]
pub struct RateLimiter {
    redis_client: RedisClient,
    default_config: RateLimitConfig,
}

impl RateLimiter {
    pub fn new(redis_client: RedisClient, default_config: RateLimitConfig) -> Self {
        Self {
            redis_client,
            default_config,
        }
    }

    pub fn redis_client(&self) -> &RedisClient {
        &self.redis_client
    }

    pub fn default_config(&self) -> &RateLimitConfig {
        &self.default_config
    }

    fn rate_limit_key(tenant_id: &Uuid, window_size_ms: u64) -> String {
        format!("rate_limit:{}:{}", tenant_id, window_size_ms)
    }

    async fn check_single_window(
        &self,
        tenant_id: &Uuid,
        window_size_ms: u64,
        max_requests: u32,
    ) -> Result<(bool, u32), AppError> {
        let max_requests = max_requests.max(1);
        let current_time = chrono::Utc::now().timestamp_millis() as u64;

        let key = Self::rate_limit_key(tenant_id, window_size_ms);
        let mut redis = self.redis_client.manager.clone();

        let script = Script::new(SLIDING_WINDOW_SCRIPT);
        let result: Vec<i64> = script
            .key(key.as_str())
            .arg(window_size_ms.to_string())
            .arg(max_requests.to_string())
            .arg(current_time.to_string())
            .invoke_async(&mut redis)
            .await
            .map_err(|e| AppError::Cache(e.to_string()))?;

        let allowed = result.get(0).copied().unwrap_or(0) == 1;
        let remaining = result.get(1).copied().unwrap_or(0) as u32;

        Ok((allowed, remaining))
    }

    async fn get_single_remaining(
        &self,
        tenant_id: &Uuid,
        window_size_ms: u64,
        max_requests: u32,
    ) -> Result<u32, AppError> {
        let max_requests = max_requests.max(1);
        let current_time = chrono::Utc::now().timestamp_millis() as u64;

        let key = Self::rate_limit_key(tenant_id, window_size_ms);
        let mut redis = self.redis_client.manager.clone();

        let script = Script::new(GET_REMAINING_SCRIPT);
        let remaining: i64 = script
            .key(key.as_str())
            .arg(window_size_ms.to_string())
            .arg(max_requests.to_string())
            .arg(current_time.to_string())
            .invoke_async(&mut redis)
            .await
            .map_err(|e| AppError::Cache(e.to_string()))?;

        Ok(remaining.max(0) as u32)
    }

    pub async fn check_rate_limit(
        &self,
        tenant_id: &Uuid,
        qps_limit: u32,
        per_min_limit: u32,
    ) -> Result<RateLimitResult, AppError> {
        let qps_limit = if qps_limit == 0 {
            self.default_config.qps_limit
        } else {
            qps_limit
        };
        let per_min_limit = if per_min_limit == 0 {
            self.default_config.per_min_limit
        } else {
            per_min_limit
        };

        let (qps_allowed, qps_remaining) = self
            .check_single_window(tenant_id, 1000, qps_limit)
            .await?;

        if !qps_allowed {
            warn!(
                "QPS rate limit exceeded for tenant {}, limit: {}",
                tenant_id, qps_limit
            );
            let per_min_remaining = self.get_per_min_remaining(tenant_id, per_min_limit).await?;
            return Ok(RateLimitResult {
                allowed: false,
                qps_remaining: 0,
                per_min_remaining,
                qps_limit,
                per_min_limit,
                exceeded_type: Some(RateLimitExceededType::Qps),
            });
        }

        let (per_min_allowed, per_min_remaining) = self
            .check_single_window(tenant_id, 60_000, per_min_limit)
            .await?;

        if !per_min_allowed {
            warn!(
                "Per-minute rate limit exceeded for tenant {}, limit: {}",
                tenant_id, per_min_limit
            );
            return Ok(RateLimitResult {
                allowed: false,
                qps_remaining,
                per_min_remaining: 0,
                qps_limit,
                per_min_limit,
                exceeded_type: Some(RateLimitExceededType::PerMinute),
            });
        }

        debug!(
            "Rate limit check passed for tenant {}, qps_remaining: {}, per_min_remaining: {}",
            tenant_id, qps_remaining, per_min_remaining
        );

        Ok(RateLimitResult {
            allowed: true,
            qps_remaining,
            per_min_remaining,
            qps_limit,
            per_min_limit,
            exceeded_type: None,
        })
    }

    pub async fn get_qps_remaining(
        &self,
        tenant_id: &Uuid,
        qps_limit: u32,
    ) -> Result<u32, AppError> {
        let qps_limit = if qps_limit == 0 {
            self.default_config.qps_limit
        } else {
            qps_limit
        };
        self.get_single_remaining(tenant_id, 1000, qps_limit)
            .await
    }

    pub async fn get_per_min_remaining(
        &self,
        tenant_id: &Uuid,
        per_min_limit: u32,
    ) -> Result<u32, AppError> {
        let per_min_limit = if per_min_limit == 0 {
            self.default_config.per_min_limit
        } else {
            per_min_limit
        };
        self.get_single_remaining(tenant_id, 60_000, per_min_limit)
            .await
    }

    pub async fn get_remaining_quota(
        &self,
        tenant_id: &Uuid,
    ) -> Result<(u32, u32), AppError> {
        let qps_remaining = self
            .get_qps_remaining(tenant_id, self.default_config.qps_limit)
            .await?;
        let per_min_remaining = self
            .get_per_min_remaining(tenant_id, self.default_config.per_min_limit)
            .await?;
        Ok((qps_remaining, per_min_remaining))
    }

    pub async fn reset_rate_limit(&self, tenant_id: &Uuid) -> Result<(), AppError> {
        let mut redis = self.redis_client.manager.clone();
        let qps_key = Self::rate_limit_key(tenant_id, 1000);
        let per_min_key = Self::rate_limit_key(tenant_id, 60_000);
        let _: Result<(), _> = redis.del::<_, ()>(&qps_key).await;
        let _: Result<(), _> = redis.del::<_, ()>(&per_min_key).await;
        Ok(())
    }
}
