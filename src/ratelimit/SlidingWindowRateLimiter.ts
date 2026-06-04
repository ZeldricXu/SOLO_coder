import { ChannelType } from '../types';
import { config } from '../config';
import { logger } from '../utils/logger';
import Redis from 'ioredis';

const SLIDING_WINDOW_SCRIPT = `
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local max_requests = tonumber(ARGV[3])
local tokens = tonumber(ARGV[4])
local member_prefix = ARGV[5]

local window_start = now - window_ms

redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start)

local current_count = redis.call('ZCARD', key)

if current_count + tokens <= max_requests then
    for i = 1, tokens do
        redis.call('ZADD', key, now, member_prefix .. ':' .. now .. ':' .. i)
    end
    redis.call('PEXPIRE', key, window_ms)
    local remaining = max_requests - current_count - tokens
    return {1, remaining, 0}
else
    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    local retry_after_ms = 0
    if #oldest > 0 then
        retry_after_ms = math.ceil((window_start + tonumber(oldest[2]) + 1) / 1000)
        if retry_after_ms < 1 then retry_after_ms = 1 end
    end
    return {0, max_requests - current_count, retry_after_ms}
end
`;

export class SlidingWindowRateLimiter {
  private redis: Redis;
  private static instance: SlidingWindowRateLimiter;
  private scriptSha: string | null = null;

  private constructor() {
    this.redis = new Redis(config.redis.url);
  }

  public static getInstance(): SlidingWindowRateLimiter {
    if (!SlidingWindowRateLimiter.instance) {
      SlidingWindowRateLimiter.instance = new SlidingWindowRateLimiter();
    }
    return SlidingWindowRateLimiter.instance;
  }

  private getKey(prefix: string, id: string): string {
    return `ratelimit:${prefix}:${id}`;
  }

  private async getScriptSha(): Promise<string> {
    if (!this.scriptSha) {
      const sha = await this.redis.script('LOAD', SLIDING_WINDOW_SCRIPT);
      this.scriptSha = sha as string;
    }
    return this.scriptSha!;
  }

  public async tryConsume(
    prefix: string,
    id: string,
    maxRequests: number,
    windowSeconds: number,
    tokens: number = 1
  ): Promise<{ allowed: boolean; remaining: number; retryAfter?: number }> {
    const key = this.getKey(prefix, id);
    const now = Date.now();
    const windowMs = windowSeconds * 1000;
    const memberPrefix = `${prefix}:${id}`;

    try {
      const sha = await this.getScriptSha();
      const result = await this.redis.evalsha(
        sha,
        1,
        key,
        now.toString(),
        windowMs.toString(),
        maxRequests.toString(),
        tokens.toString(),
        memberPrefix
      ) as [number, number, number];

      return {
        allowed: result[0] === 1,
        remaining: Math.max(0, result[1]),
        retryAfter: result[2] > 0 ? result[2] : undefined,
      };
    } catch (err: any) {
      if (err.message?.includes('NOSCRIPT')) {
        this.scriptSha = null;
        return this.tryConsume(prefix, id, maxRequests, windowSeconds, tokens);
      }
      throw err;
    }
  }

  public async checkChannelLimit(channel: ChannelType, tokens: number = 1): Promise<{
    allowed: boolean;
    remaining: number;
    retryAfter?: number;
  }> {
    const channelConfig = (config.rateLimit.channel as any)[channel];
    if (!channelConfig) {
      return { allowed: true, remaining: 999999 };
    }

    const result = await this.tryConsume(
      'channel',
      channel,
      channelConfig.max,
      channelConfig.window,
      tokens
    );
    logger.debug('Channel rate limit check', { channel, ...result });
    return result;
  }

  public async checkUserLimit(
    userId: string,
    channel: ChannelType,
    tokens: number = 1
  ): Promise<{
    allowed: boolean;
    remaining: number;
    retryAfter?: number;
  }> {
    const userConfig = (config.rateLimit.user as any)[channel];
    if (!userConfig) {
      return { allowed: true, remaining: 999999 };
    }

    const key = `${userId}:${channel}`;
    const result = await this.tryConsume('user', key, userConfig.max, userConfig.window, tokens);
    logger.debug('User rate limit check', { userId, channel, ...result });
    return result;
  }

  public async checkTenantLimit(
    tenantId: string,
    tokens: number = 1
  ): Promise<{
    allowed: boolean;
    remaining: number;
    retryAfter?: number;
  }> {
    const maxRequests = config.rateLimit.tenant.concurrency;
    const result = await this.tryConsume('tenant', tenantId, maxRequests, 60, tokens);
    logger.debug('Tenant rate limit check', { tenantId, ...result });
    return result;
  }

  public async checkAllLimits(
    tenantId: string,
    userId: string | undefined,
    channel: ChannelType
  ): Promise<{
    allowed: boolean;
    retryAfter?: number;
    reason?: string;
  }> {
    const tenantResult = await this.checkTenantLimit(tenantId);
    if (!tenantResult.allowed) {
      return { allowed: false, retryAfter: tenantResult.retryAfter, reason: 'tenant_limit_exceeded' };
    }

    const channelResult = await this.checkChannelLimit(channel);
    if (!channelResult.allowed) {
      return { allowed: false, retryAfter: channelResult.retryAfter, reason: 'channel_limit_exceeded' };
    }

    if (userId) {
      const userResult = await this.checkUserLimit(userId, channel);
      if (!userResult.allowed) {
        return { allowed: false, retryAfter: userResult.retryAfter, reason: 'user_limit_exceeded' };
      }
    }

    return { allowed: true };
  }

  public async getWindowCount(prefix: string, id: string, windowSeconds: number): Promise<number> {
    const key = this.getKey(prefix, id);
    const now = Date.now();
    const windowStart = now - windowSeconds * 1000;
    await this.redis.zremrangebyscore(key, '-inf', windowStart);
    return this.redis.zcard(key);
  }

  public async getStats(prefix: string, id: string): Promise<any> {
    const key = this.getKey(prefix, id);
    const count = await this.redis.zcard(key);
    const members = await this.redis.zrange(key, 0, -1, 'WITHSCORES');
    return { count, members };
  }

  public async reset(prefix: string, id: string): Promise<void> {
    const key = this.getKey(prefix, id);
    await this.redis.del(key);
  }

  public async close(): Promise<void> {
    await this.redis.disconnect();
  }
}

export { TokenBucketRateLimiter } from './TokenBucketRateLimiter';
