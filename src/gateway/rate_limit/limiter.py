from dataclasses import dataclass
from typing import Any, Dict, Optional, Tuple
import time
import hashlib

from redis.asyncio import Redis
from redis.exceptions import RedisError

from gateway.config import get_settings
from gateway.db.redis_client import get_redis
from gateway.logger import get_logger

logger = get_logger("rate-limit")

TOKEN_BUCKET_SCRIPT = """
local key = KEYS[1]
local burst_key = KEYS[2]
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local burst_multiplier = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
local window = tonumber(ARGV[5])

local burst_capacity = capacity * burst_multiplier

local bucket = redis.call('HMGET', key, 'tokens', 'last_refill', 'total_requests', 'allowed_requests')
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2]) or 0
local total_requests = tonumber(bucket[3]) or 0
local allowed_requests = tonumber(bucket[4]) or 0

if tokens == false then
    tokens = capacity
    last_refill = now
end

local elapsed = now - last_refill
local refill_amount = (elapsed / window) * rate
tokens = math.min(capacity, tokens + refill_amount)
last_refill = now

total_requests = total_requests + 1
local allowed = 0
local remaining = 0
local retry_after = 0

if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
    allowed_requests = allowed_requests + 1
    remaining = math.floor(tokens)
    redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill, 'total_requests', total_requests, 'allowed_requests', allowed_requests)
    redis.call('EXPIRE', key, window * 2)
else
    local burst_bucket = redis.call('HMGET', burst_key, 'tokens', 'last_refill')
    local burst_tokens = tonumber(burst_bucket[1])
    local burst_last_refill = tonumber(burst_bucket[2]) or 0

    if burst_tokens == false then
        burst_tokens = burst_capacity
        burst_last_refill = now
    end

    local burst_elapsed = now - burst_last_refill
    local burst_refill = (burst_elapsed / window) * rate * 0.1
    burst_tokens = math.min(burst_capacity, burst_tokens + burst_refill)
    burst_last_refill = now

    if burst_tokens >= 1 then
        burst_tokens = burst_tokens - 1
        allowed = 1
        allowed_requests = allowed_requests + 1
        remaining = math.floor(burst_tokens) - capacity
        redis.call('HMSET', burst_key, 'tokens', burst_tokens, 'last_refill', burst_last_refill)
        redis.call('EXPIRE', burst_key, window * 2)
        redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill, 'total_requests', total_requests, 'allowed_requests', allowed_requests)
    else
        retry_after = math.ceil(window / rate)
        redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill, 'total_requests', total_requests, 'allowed_requests', allowed_requests)
    end
end

return {allowed, remaining, retry_after, total_requests, allowed_requests}
"""


@dataclass
class RateLimitResult:
    allowed: bool
    remaining: int
    limit: int
    retry_after: int
    total_requests: int
    allowed_requests: int
    used_burst: bool = False


class RateLimiter:
    def __init__(self):
        self.settings = get_settings()
        self.rl_settings = self.settings.rate_limit
        self.redis: Redis = get_redis()
        self._script_sha: Optional[str] = None

    async def init_script(self) -> None:
        try:
            self._script_sha = await self.redis.script_load(TOKEN_BUCKET_SCRIPT)
            logger.info("Rate limit Lua script loaded", sha=self._script_sha)
        except RedisError as e:
            logger.error("Failed to load rate limit script", error=str(e))

    async def check_rate_limit(self, user_id: Optional[str], api_path: str,
                               custom_user_limit: Optional[int] = None,
                               custom_api_limit: Optional[int] = None) -> RateLimitResult:
        now = int(time.time())
        window = self.rl_settings.window_seconds
        burst_multiplier = self.rl_settings.burst_multiplier

        user_limit = custom_user_limit or self.rl_settings.default_user_limit
        api_limit = custom_api_limit or self.rl_settings.default_api_limit

        results = []

        if user_id:
            user_key = f"{self.rl_settings.redis_key_prefix}user:{user_id}:{api_path}"
            user_burst_key = f"{self.rl_settings.redis_key_prefix}user:burst:{user_id}:{api_path}"

            user_result = await self._execute_check(
                user_key, user_burst_key, user_limit, user_limit, burst_multiplier, now, window
            )
            results.append(("user", user_result))

        api_key = f"{self.rl_settings.redis_key_prefix}api:{api_path}"
        api_burst_key = f"{self.rl_settings.redis_key_prefix}api:burst:{api_path}"

        api_result = await self._execute_check(
            api_key, api_burst_key, api_limit, api_limit, burst_multiplier, now, window
        )
        results.append(("api", api_result))

        final_allowed = all(r[1].allowed for r in results)
        final_result = min(results, key=lambda x: x[1].remaining)

        return RateLimitResult(
            allowed=final_allowed,
            remaining=final_result[1].remaining,
            limit=user_limit if final_result[0] == "user" else api_limit,
            retry_after=max(r[1].retry_after for r in results),
            total_requests=sum(r[1].total_requests for r in results),
            allowed_requests=sum(r[1].allowed_requests for r in results),
            used_burst=any(r[1].remaining < 0 for r in results),
        )

    async def _execute_check(self, key: str, burst_key: str, rate: int, capacity: int,
                             burst_multiplier: float, now: int, window: int) -> RateLimitResult:
        try:
            if self._script_sha:
                result = await self.redis.evalsha(
                    self._script_sha,
                    2,
                    key, burst_key,
                    str(rate), str(capacity), str(burst_multiplier),
                    str(now), str(window),
                )
            else:
                result = await self.redis.eval(
                    TOKEN_BUCKET_SCRIPT,
                    2,
                    key, burst_key,
                    str(rate), str(capacity), str(burst_multiplier),
                    str(now), str(window),
                )

            allowed = bool(result[0])
            remaining = int(result[1])
            retry_after = int(result[2])
            total_requests = int(result[3])
            allowed_requests = int(result[4])

            return RateLimitResult(
                allowed=allowed,
                remaining=remaining,
                limit=capacity,
                retry_after=retry_after,
                total_requests=total_requests,
                allowed_requests=allowed_requests,
            )

        except Exception as e:
            logger.error("Rate limit check failed", key=key, error=str(e))
            return RateLimitResult(
                allowed=True,
                remaining=capacity,
                limit=capacity,
                retry_after=0,
                total_requests=0,
                allowed_requests=0,
            )

    async def get_rate_limit_info(self, user_id: Optional[str], api_path: str) -> Dict[str, Any]:
        info = {}

        if user_id:
            user_key = f"{self.rl_settings.redis_key_prefix}user:{user_id}:{api_path}"
            user_data = await self.redis.hgetall(user_key)
            if user_data:
                info["user"] = {
                    "tokens": float(user_data.get("tokens", 0)),
                    "last_refill": int(user_data.get("last_refill", 0)),
                    "total_requests": int(user_data.get("total_requests", 0)),
                    "allowed_requests": int(user_data.get("allowed_requests", 0)),
                }

        api_key = f"{self.rl_settings.redis_key_prefix}api:{api_path}"
        api_data = await self.redis.hgetall(api_key)
        if api_data:
            info["api"] = {
                "tokens": float(api_data.get("tokens", 0)),
                "last_refill": int(api_data.get("last_refill", 0)),
                "total_requests": int(api_data.get("total_requests", 0)),
                "allowed_requests": int(api_data.get("allowed_requests", 0)),
            }

        return info

    async def reset_rate_limit(self, user_id: Optional[str], api_path: str) -> None:
        if user_id:
            user_key = f"{self.rl_settings.redis_key_prefix}user:{user_id}:{api_path}"
            user_burst_key = f"{self.rl_settings.redis_key_prefix}user:burst:{user_id}:{api_path}"
            await self.redis.delete(user_key, user_burst_key)

        api_key = f"{self.rl_settings.redis_key_prefix}api:{api_path}"
        api_burst_key = f"{self.rl_settings.redis_key_prefix}api:burst:{api_path}"
        await self.redis.delete(api_key, api_burst_key)

        logger.info("Rate limit reset", user_id=user_id, api_path=api_path)


_limiter_instance: Optional[RateLimiter] = None


def get_rate_limiter() -> RateLimiter:
    global _limiter_instance
    if _limiter_instance is None:
        _limiter_instance = RateLimiter()
    return _limiter_instance
