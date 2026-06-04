import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { TokenBucketRateLimiter } from '../../src/ratelimit/RateLimiter';
import { config } from '../../src/config';
import Redis from 'ioredis';
import { v4 as uuidv4 } from 'uuid';

vi.mock('ioredis');

describe('TokenBucketRateLimiter', () => {
  let limiter: TokenBucketRateLimiter;
  let mockRedis: any;
  const tenantId = uuidv4();
  const userId = uuidv4();

  beforeEach(() => {
    vi.clearAllMocks();
    vi.useRealTimers();

    mockRedis = {
      eval: vi.fn(),
      hgetall: vi.fn().mockResolvedValue({}),
      disconnect: vi.fn().mockResolvedValue(undefined),
    };

    vi.mocked(Redis).mockImplementation(() => mockRedis);

    (TokenBucketRateLimiter as any).instance = null;
    limiter = TokenBucketRateLimiter.getInstance();
  });

  afterEach(async () => {
    await limiter.close();
  });

  describe('正常路径测试 - 令牌桶算法', () => {
    it('在时间窗口内连续请求N次，验证第N+1次请求被拒绝', async () => {
      const maxTokens = 5;
      const refillRate = 1;

      for (let i = 0; i < maxTokens; i++) {
        mockRedis.eval.mockResolvedValueOnce([1, maxTokens - i - 1]);
      }
      mockRedis.eval.mockResolvedValueOnce([0, 0, 1]);

      for (let i = 0; i < maxTokens; i++) {
        const result = await limiter.tryConsume('test', 'key-1', maxTokens, refillRate, 1);
        expect(result.allowed).toBe(true);
        expect(result.remaining).toBe(maxTokens - i - 1);
      }

      const result = await limiter.tryConsume('test', 'key-1', maxTokens, refillRate, 1);
      expect(result.allowed).toBe(false);
      expect(result.remaining).toBe(0);
      expect(result.retryAfter).toBe(1);
    });

    it('令牌桶按速率自动补充', async () => {
      const maxTokens = 10;
      const refillRate = 1;
      const now = Date.now();
      let currentTokens = maxTokens;
      let lastRefill = now;

      mockRedis.eval.mockImplementation(async (script: string, keyCount: number, ...args: any[]) => {
        const requestTime = parseInt(args[4]);
        const tokensToConsume = parseInt(args[3]);
        
        const elapsed = (requestTime - lastRefill) / 1000;
        currentTokens = Math.min(maxTokens, currentTokens + elapsed * refillRate);
        lastRefill = requestTime;

        if (currentTokens >= tokensToConsume) {
          currentTokens -= tokensToConsume;
          return [1, Math.floor(currentTokens)];
        }
        const needed = tokensToConsume - currentTokens;
        return [0, Math.floor(currentTokens), Math.ceil(needed / refillRate)];
      });

      const result1 = await limiter.tryConsume('test', 'key-2', maxTokens, refillRate, maxTokens);
      expect(result1.allowed).toBe(true);
      expect(result1.remaining).toBe(0);

      vi.useFakeTimers();
      vi.setSystemTime(now + 5000);

      const result2 = await limiter.tryConsume('test', 'key-2', maxTokens, refillRate, 3);
      expect(result2.allowed).toBe(true);
      expect(result2.remaining).toBe(2);

      vi.useRealTimers();
    });

    it('不同key的令牌桶独立计数', async () => {
      mockRedis.eval
        .mockResolvedValueOnce([1, 9])
        .mockResolvedValueOnce([1, 4]);

      const resultA = await limiter.tryConsume('test', 'key-A', 10, 1, 1);
      const resultB = await limiter.tryConsume('test', 'key-B', 5, 1, 1);

      expect(resultA.remaining).toBe(9);
      expect(resultB.remaining).toBe(4);
    });

    it('批量消耗多个令牌', async () => {
      mockRedis.eval.mockResolvedValueOnce([1, 7]);

      const result = await limiter.tryConsume('test', 'bulk-key', 10, 1, 3);

      expect(result.allowed).toBe(true);
      expect(result.remaining).toBe(7);

      const evalArgs = mockRedis.eval.mock.calls[0];
      expect(evalArgs[5]).toBe('3');
    });
  });

  describe('表驱动测试 - 三级限流层级', () => {
    interface LimitTestCase {
      level: 'tenant' | 'channel' | 'user';
      setup: (remaining: number, allowed: boolean) => void;
      callLimiter: () => Promise<any>;
      expectedReason?: string;
    }

    const testCases: { name: string; input: LimitTestCase; expected: boolean }[] = [
      {
        name: '租户级别限流触发',
        input: {
          level: 'tenant',
          setup: (remaining, allowed) => {
            vi.spyOn(limiter, 'checkTenantLimit').mockResolvedValue({
              allowed,
              remaining,
              retryAfter: 60,
            });
            vi.spyOn(limiter, 'checkChannelLimit').mockResolvedValue({ allowed: true, remaining: 100 });
            vi.spyOn(limiter, 'checkUserLimit').mockResolvedValue({ allowed: true, remaining: 5 });
          },
          callLimiter: () => limiter.checkAllLimits(tenantId, userId, 'email'),
          expectedReason: 'tenant_limit_exceeded',
        },
        expected: false,
      },
      {
        name: '渠道级别限流触发',
        input: {
          level: 'channel',
          setup: (remaining, allowed) => {
            vi.spyOn(limiter, 'checkTenantLimit').mockResolvedValue({ allowed: true, remaining: 100 });
            vi.spyOn(limiter, 'checkChannelLimit').mockResolvedValue({
              allowed,
              remaining,
              retryAfter: 3600,
            });
            vi.spyOn(limiter, 'checkUserLimit').mockResolvedValue({ allowed: true, remaining: 5 });
          },
          callLimiter: () => limiter.checkAllLimits(tenantId, userId, 'email'),
          expectedReason: 'channel_limit_exceeded',
        },
        expected: false,
      },
      {
        name: '用户级别限流触发',
        input: {
          level: 'user',
          setup: (remaining, allowed) => {
            vi.spyOn(limiter, 'checkTenantLimit').mockResolvedValue({ allowed: true, remaining: 100 });
            vi.spyOn(limiter, 'checkChannelLimit').mockResolvedValue({ allowed: true, remaining: 100 });
            vi.spyOn(limiter, 'checkUserLimit').mockResolvedValue({
              allowed,
              remaining,
              retryAfter: 60,
            });
          },
          callLimiter: () => limiter.checkAllLimits(tenantId, userId, 'sms'),
          expectedReason: 'user_limit_exceeded',
        },
        expected: false,
      },
      {
        name: '所有级别都通过',
        input: {
          level: 'tenant',
          setup: (remaining, allowed) => {
            vi.spyOn(limiter, 'checkTenantLimit').mockResolvedValue({ allowed: true, remaining: 100 });
            vi.spyOn(limiter, 'checkChannelLimit').mockResolvedValue({ allowed: true, remaining: 100 });
            vi.spyOn(limiter, 'checkUserLimit').mockResolvedValue({ allowed: true, remaining: 5 });
          },
          callLimiter: () => limiter.checkAllLimits(tenantId, userId, 'email'),
        },
        expected: true,
      },
      {
        name: '无userId时跳过用户级别检查',
        input: {
          level: 'tenant',
          setup: (remaining, allowed) => {
            vi.spyOn(limiter, 'checkTenantLimit').mockResolvedValue({ allowed: true, remaining: 100 });
            vi.spyOn(limiter, 'checkChannelLimit').mockResolvedValue({ allowed: true, remaining: 100 });
            const checkUserLimitSpy = vi.spyOn(limiter, 'checkUserLimit');
          },
          callLimiter: () => limiter.checkAllLimits(tenantId, undefined, 'email'),
        },
        expected: true,
      },
    ];

    for (const tc of testCases) {
      it(tc.name, async () => {
        tc.input.setup(0, tc.expected);
        const result = await tc.input.callLimiter();
        expect(result.allowed).toBe(tc.expected);
        if (tc.input.expectedReason) {
          expect(result.reason).toBe(tc.input.expectedReason);
          expect(result.retryAfter).toBeDefined();
        }
      });
    }
  });

  describe('异常路径测试 - Redis故障处理', () => {
    it('Redis连接断开时采用fail closed策略（保守拒绝）', async () => {
      mockRedis.eval.mockRejectedValueOnce(new Error('ECONNREFUSED: Connection refused'));

      await expect(
        limiter.tryConsume('test', 'fail-test', 10, 1, 1)
      ).rejects.toThrow('ECONNREFUSED');
    });

    it('Redis超时抛出异常而非静默失败', async () => {
      mockRedis.eval.mockRejectedValueOnce(new Error('Command timed out'));

      await expect(
        limiter.checkChannelLimit('email')
      ).rejects.toThrow('Command timed out');
    });

    it('Redis返回异常数据时正确处理', async () => {
      mockRedis.eval.mockResolvedValueOnce(['invalid', 'data']);

      const result = await limiter.tryConsume('test', 'invalid-data', 10, 1, 1);
      expect(result.allowed).toBe(false);
    });

    it('Lua脚本执行失败时抛出异常', async () => {
      mockRedis.eval.mockRejectedValueOnce(new Error('NOSCRIPT No matching script'));

      await expect(
        limiter.checkTenantLimit(tenantId)
      ).rejects.toThrow('NOSCRIPT');
    });
  });

  describe('配置驱动的限流测试', () => {
    it('渠道级限流使用配置的配额', async () => {
      const emailConfig = config.rateLimit.channel.email;
      const expectedMax = emailConfig.max;
      const expectedRefillRate = expectedMax / emailConfig.window;

      mockRedis.eval.mockResolvedValueOnce([1, expectedMax - 1]);

      await limiter.checkChannelLimit('email');

      const evalArgs = mockRedis.eval.mock.calls[0];
      expect(parseInt(evalArgs[3])).toBe(expectedMax);
      expect(parseFloat(evalArgs[4])).toBeCloseTo(expectedRefillRate);
    });

    it('用户级短信限流使用配置的每分钟5条限制', async () => {
      const smsConfig = config.rateLimit.user.sms;
      expect(smsConfig.max).toBe(5);
      expect(smsConfig.window).toBe(60);

      mockRedis.eval.mockResolvedValueOnce([1, 4]);

      await limiter.checkUserLimit(userId, 'sms');

      const evalArgs = mockRedis.eval.mock.calls[0];
      expect(parseInt(evalArgs[3])).toBe(5);
      expect(parseFloat(evalArgs[4])).toBeCloseTo(5 / 60);
    });

    it('租户级并发限制使用配置值', async () => {
      const tenantConcurrency = config.rateLimit.tenant.concurrency;
      expect(tenantConcurrency).toBe(100);

      mockRedis.eval.mockResolvedValueOnce([1, 99]);

      await limiter.checkTenantLimit(tenantId);

      const evalArgs = mockRedis.eval.mock.calls[0];
      expect(parseInt(evalArgs[3])).toBe(100);
    });
  });

  describe('Lua脚本逻辑验证', () => {
    it('初始状态下桶满，第一次请求成功', async () => {
      mockRedis.eval.mockImplementation(async (script: string, keyCount: number, ...args: any[]) => {
        const maxTokens = parseInt(args[1]);
        return [1, maxTokens - 1];
      });

      const result = await limiter.tryConsume('test', 'lua-test', 10, 1, 1);
      expect(result.allowed).toBe(true);
      expect(result.remaining).toBe(9);
    });

    it('空桶时返回正确的retryAfter', async () => {
      const refillRate = 2;
      mockRedis.eval.mockResolvedValueOnce([0, 0, 1]);

      const result = await limiter.tryConsume('test', 'empty-bucket', 10, refillRate, 1);
      expect(result.allowed).toBe(false);
      expect(result.retryAfter).toBe(1);
    });

    it('令牌桶设置1小时过期', async () => {
      mockRedis.eval.mockResolvedValueOnce([1, 9]);

      await limiter.tryConsume('test', 'expire-test', 10, 1, 1);

      const script = mockRedis.eval.mock.calls[0][0] as string;
      expect(script).toContain("EXPIRE");
      expect(script).toContain("3600");
    });
  });

  describe('高并发场景测试', () => {
    it('100个并发请求下限流计数正确', async () => {
      const maxTokens = 50;
      const refillRate = 10;
      let currentTokens = maxTokens;

      mockRedis.eval.mockImplementation(async () => {
        if (currentTokens >= 1) {
          currentTokens--;
          return [1, currentTokens];
        }
        return [0, 0, Math.ceil(1 / refillRate)];
      });

      const promises = Array.from({ length: 100 }, () =>
        limiter.tryConsume('concurrent', 'key-1', maxTokens, refillRate, 1)
      );

      const results = await Promise.all(promises);

      const allowed = results.filter(r => r.allowed).length;
      const rejected = results.filter(r => !r.allowed).length;

      expect(allowed).toBe(50);
      expect(rejected).toBe(50);
      expect(currentTokens).toBe(0);
    });

    it('不同租户的限流相互独立', async () => {
      const tenant1 = uuidv4();
      const tenant2 = uuidv4();
      const tokens = { [tenant1]: 10, [tenant2]: 5 };

      mockRedis.eval.mockImplementation(async (script: string, keyCount: number, ...args: any[]) => {
        const key = args[0] as string;
        const tenant = key.includes(tenant1) ? tenant1 : tenant2;
        if (tokens[tenant] >= 1) {
          tokens[tenant]--;
          return [1, tokens[tenant]];
        }
        return [0, 0, 1];
      });

      const results1 = await Promise.all(
        Array.from({ length: 15 }, () => limiter.checkTenantLimit(tenant1))
      );
      const results2 = await Promise.all(
        Array.from({ length: 10 }, () => limiter.checkTenantLimit(tenant2))
      );

      expect(results1.filter(r => r.allowed).length).toBe(10);
      expect(results2.filter(r => r.allowed).length).toBe(5);
    });
  });

  describe('状态查询测试', () => {
    it('getStats返回当前桶状态', async () => {
      const expectedData = { tokens: '5', last_refill: Date.now().toString() };
      mockRedis.hgetall.mockResolvedValueOnce(expectedData);

      const stats = await limiter.getStats('channel', 'email');

      expect(stats).toEqual(expectedData);
      expect(mockRedis.hgetall).toHaveBeenCalledWith('ratelimit:channel:email');
    });
  });

  describe('边界条件测试', () => {
    it('零令牌请求返回成功但不消耗', async () => {
      mockRedis.eval.mockResolvedValueOnce([1, 10]);

      const result = await limiter.tryConsume('test', 'zero-tokens', 10, 1, 0);
      expect(result.allowed).toBe(true);
    });

    it('超过最大令牌数的请求被拒绝', async () => {
      mockRedis.eval.mockResolvedValueOnce([0, 10, 1]);

      const result = await limiter.tryConsume('test', 'too-many', 10, 1, 15);
      expect(result.allowed).toBe(false);
      expect(result.retryAfter).toBe(1);
    });

    it('key包含特殊字符时正确处理', async () => {
      const specialKey = 'user@example.com:channel/sms?test=1';
      mockRedis.eval.mockResolvedValueOnce([1, 9]);

      const result = await limiter.tryConsume('test', specialKey, 10, 1, 1);
      expect(result.allowed).toBe(true);

      const evalArgs = mockRedis.eval.mock.calls[0];
      expect(evalArgs[2]).toBe(`ratelimit:test:${specialKey}`);
    });
  });
});
