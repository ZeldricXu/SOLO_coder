import { ChannelType } from '../types';
import { config } from '../config';
import { logger } from '../utils/logger';
import Redis from 'ioredis';

export class TokenBucketRateLimiter {
  private redis: Redis;
  private static instance: TokenBucketRateLimiter;

  private constructor() {
    this.redis = new Redis(config.redis.url);
  }

  public static getInstance(): TokenBucketRateLimiter {
    if (!TokenBucketRateLimiter.instance) {
      TokenBucketRateLimiter.instance = new TokenBucketRateLimiter();
    }
    return TokenBucketRateLimiter.instance;
  }

  private getBucketKey(prefix: string, id: string): string {
    return `ratelimit:${prefix}:${id}`;
  }

  public async tryConsume(
    prefix: string,
    id: string,
    maxTokens: number,
    refillRate: number,
    tokens: number = 1
  ): Promise<{ allowed: boolean; remaining: number; retryAfter?: number }> {
    const key = this.getBucketKey(prefix, id);
    const now = Date.now();

    const result = await this.redis.eval(
      `
      local key = KEYS[1]
      local max_tokens = tonumber(ARGV[1])
      local refill_rate = tonumber(ARGV[2])
      local tokens = tonumber(ARGV[3])
      local now = tonumber(ARGV[4])

      local data = redis.call('HMGET', key, 'tokens', 'last_refill')
      local current_tokens = tonumber(data[1]) or max_tokens
      local last_refill = tonumber(data[2]) or now

      local elapsed = (now - last_refill) / 1000
      local new_tokens = math.min(max_tokens, current_tokens + elapsed * refill_rate)

      if new_tokens >= tokens then
          new_tokens = new_tokens - tokens
          redis.call('HMSET', key, 'tokens', new_tokens, 'last_refill', now)
          redis.call('EXPIRE', key, 3600)
          return {1, new_tokens}
      else
          local needed = tokens - new_tokens
          local wait_time = math.ceil(needed / refill_rate)
          return {0, new_tokens, wait_time}
      end
      `,
      1,
      key,
      maxTokens.toString(),
      refillRate.toString(),
      tokens.toString(),
      now.toString()
    );

    const resultArray = result as [number, number, number];
    return {
      allowed: resultArray[0] === 1,
      remaining: Math.floor(resultArray[1]),
      retryAfter: resultArray[2],
    };
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

    const maxTokens = channelConfig.max;
    const refillRate = maxTokens / channelConfig.window;

    const result = await this.tryConsume('channel', channel, maxTokens, refillRate, tokens);
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

    const maxTokens = userConfig.max;
    const refillRate = maxTokens / userConfig.window;
    const key = `${userId}:${channel}`;

    const result = await this.tryConsume('user', key, maxTokens, refillRate, tokens);
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
    const maxTokens = config.rateLimit.tenant.concurrency;
    const refillRate = maxTokens / 60;

    const result = await this.tryConsume('tenant', tenantId, maxTokens, refillRate, tokens);
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

  public async getStats(prefix: string, id: string): Promise<any> {
    const key = this.getBucketKey(prefix, id);
    const data = await this.redis.hgetall(key);
    return data;
  }

  public async close(): Promise<void> {
    await this.redis.disconnect();
  }
}
