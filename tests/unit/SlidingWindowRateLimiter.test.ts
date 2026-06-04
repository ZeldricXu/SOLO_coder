import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SlidingWindowRateLimiter } from '../../src/ratelimit/SlidingWindowRateLimiter';
import { config } from '../../src/config';
import Redis from 'ioredis';
import { v4 as uuidv4 } from 'uuid';

vi.mock('ioredis');

describe('SlidingWindowRateLimiter', () => {
  let limiter: SlidingWindowRateLimiter;
  let mockRedis: any;
  const tenantId = uuidv4();
  const userId = uuidv4();

  beforeEach(() => {
    vi.clearAllMocks();
    vi.useRealTimers();

    mockRedis = {
      script: vi.fn().mockResolvedValue('abc123sha'),
      evalsha: vi.fn(),
      zremrangebyscore: vi.fn().mockResolvedValue(0),
      zcard: vi.fn().mockResolvedValue(0),
      zrange: vi.fn().mockResolvedValue([]),
      del: vi.fn().mockResolvedValue(1),
      disconnect: vi.fn().mockResolvedValue(undefined),
    };

    vi.mocked(Redis).mockImplementation(() => mockRedis);

    (SlidingWindowRateLimiter as any).instance = null;
    limiter = SlidingWindowRateLimiter.getInstance();
  });

  afterEach(async () => {
    await limiter.close();
  });

  describe('正常滑动窗口测试', () => {
    it('窗口内请求数未超限，全部通过', async () => {
      const maxRequests = 5;
      const windowSeconds = 60;

      for (let i = 0; i < maxRequests; i++) {
        mockRedis.evalsha.mockResolvedValueOnce([1, maxRequests - i - 1, 0]);
      }

      for (let i = 0; i < maxRequests; i++) {
        const result = await limiter.tryConsume('test', 'key-1', maxRequests, windowSeconds, 1);
        expect(result.allowed).toBe(true);
        expect(result.remaining).toBe(maxRequests - i - 1);
      }
    });

    it('窗口内请求数超过限制时被拒绝', async () => {
      const maxRequests = 5;
      const windowSeconds = 60;

      for (let i = 0; i < maxRequests; i++) {
        mockRedis.evalsha.mockResolvedValueOnce([1, maxRequests - i - 1, 0]);
      }
      mockRedis.evalsha.mockResolvedValueOnce([0, 0, 1]);

      for (let i = 0; i < maxRequests; i++) {
        const result = await limiter.tryConsume('test', 'key-1', maxRequests, windowSeconds, 1);
        expect(result.allowed).toBe(true);
      }

      const result = await limiter.tryConsume('test', 'key-1', maxRequests, windowSeconds, 1);
      expect(result.allowed).toBe(false);
      expect(result.remaining).toBe(0);
      expect(result.retryAfter).toBe(1);
    });

    it('被拒绝时retryAfter有值，通过时retryAfter为undefined', async () => {
      mockRedis.evalsha.mockResolvedValueOnce([1, 9, 0]);
      const passResult = await limiter.tryConsume('test', 'key-r', 10, 60, 1);
      expect(passResult.allowed).toBe(true);
      expect(passResult.retryAfter).toBeUndefined();

      mockRedis.evalsha.mockResolvedValueOnce([0, 0, 5]);
      const failResult = await limiter.tryConsume('test', 'key-r', 10, 60, 1);
      expect(failResult.allowed).toBe(false);
      expect(failResult.retryAfter).toBe(5);
    });
  });

  describe('窗口滑动测试', () => {
    it('过期条目被清除后，新请求可以成功', async () => {
      const maxRequests = 3;
      const windowSeconds = 10;

      mockRedis.evalsha
        .mockResolvedValueOnce([0, 0, 5])
        .mockResolvedValueOnce([1, 2, 0]);

      const blocked = await limiter.tryConsume('test', 'slide-key', maxRequests, windowSeconds, 1);
      expect(blocked.allowed).toBe(false);

      const allowed = await limiter.tryConsume('test', 'slide-key', maxRequests, windowSeconds, 1);
      expect(allowed.allowed).toBe(true);
      expect(allowed.remaining).toBe(2);
    });

    it('不同key的滑动窗口独立计数', async () => {
      mockRedis.evalsha
        .mockResolvedValueOnce([1, 9, 0])
        .mockResolvedValueOnce([1, 4, 0]);

      const resultA = await limiter.tryConsume('test', 'key-A', 10, 60, 1);
      const resultB = await limiter.tryConsume('test', 'key-B', 5, 60, 1);

      expect(resultA.remaining).toBe(9);
      expect(resultB.remaining).toBe(4);
    });
  });

  describe('多令牌消耗测试', () => {
    it('批量消耗多个令牌成功', async () => {
      mockRedis.evalsha.mockResolvedValueOnce([1, 7, 0]);

      const result = await limiter.tryConsume('test', 'bulk-key', 10, 60, 3);

      expect(result.allowed).toBe(true);
      expect(result.remaining).toBe(7);

      const evalshaArgs = mockRedis.evalsha.mock.calls[0];
      expect(evalshaArgs[6]).toBe('3');
    });

    it('请求令牌数超过剩余配额时被拒绝', async () => {
      mockRedis.evalsha.mockResolvedValueOnce([0, 2, 1]);

      const result = await limiter.tryConsume('test', 'bulk-fail', 10, 60, 5);

      expect(result.allowed).toBe(false);
      expect(result.remaining).toBe(2);
      expect(result.retryAfter).toBe(1);
    });

    it('零令牌请求始终通过', async () => {
      mockRedis.evalsha.mockResolvedValueOnce([1, 10, 0]);

      const result = await limiter.tryConsume('test', 'zero-tokens', 10, 60, 0);
      expect(result.allowed).toBe(true);
      expect(result.remaining).toBe(10);
    });
  });

  describe('三级限流测试', () => {
    it('checkAllLimits - 所有级别通过', async () => {
      vi.spyOn(limiter, 'checkTenantLimit').mockResolvedValue({ allowed: true, remaining: 99 });
      vi.spyOn(limiter, 'checkChannelLimit').mockResolvedValue({ allowed: true, remaining: 49 });
      vi.spyOn(limiter, 'checkUserLimit').mockResolvedValue({ allowed: true, remaining: 4 });

      const result = await limiter.checkAllLimits(tenantId, userId, 'email');

      expect(result.allowed).toBe(true);
      expect(result.reason).toBeUndefined();
    });

    it('checkAllLimits - 租户限流触发', async () => {
      vi.spyOn(limiter, 'checkTenantLimit').mockResolvedValue({ allowed: false, remaining: 0, retryAfter: 60 });
      vi.spyOn(limiter, 'checkChannelLimit').mockResolvedValue({ allowed: true, remaining: 49 });
      vi.spyOn(limiter, 'checkUserLimit').mockResolvedValue({ allowed: true, remaining: 4 });

      const result = await limiter.checkAllLimits(tenantId, userId, 'email');

      expect(result.allowed).toBe(false);
      expect(result.reason).toBe('tenant_limit_exceeded');
      expect(result.retryAfter).toBe(60);
    });

    it('checkAllLimits - 渠道限流触发', async () => {
      vi.spyOn(limiter, 'checkTenantLimit').mockResolvedValue({ allowed: true, remaining: 99 });
      vi.spyOn(limiter, 'checkChannelLimit').mockResolvedValue({ allowed: false, remaining: 0, retryAfter: 3600 });
      vi.spyOn(limiter, 'checkUserLimit').mockResolvedValue({ allowed: true, remaining: 4 });

      const result = await limiter.checkAllLimits(tenantId, userId, 'email');

      expect(result.allowed).toBe(false);
      expect(result.reason).toBe('channel_limit_exceeded');
      expect(result.retryAfter).toBe(3600);
    });

    it('checkAllLimits - 用户限流触发', async () => {
      vi.spyOn(limiter, 'checkTenantLimit').mockResolvedValue({ allowed: true, remaining: 99 });
      vi.spyOn(limiter, 'checkChannelLimit').mockResolvedValue({ allowed: true, remaining: 49 });
      vi.spyOn(limiter, 'checkUserLimit').mockResolvedValue({ allowed: false, remaining: 0, retryAfter: 60 });

      const result = await limiter.checkAllLimits(tenantId, userId, 'sms');

      expect(result.allowed).toBe(false);
      expect(result.reason).toBe('user_limit_exceeded');
      expect(result.retryAfter).toBe(60);
    });

    it('checkAllLimits - 无userId时跳过用户级别检查', async () => {
      vi.spyOn(limiter, 'checkTenantLimit').mockResolvedValue({ allowed: true, remaining: 99 });
      vi.spyOn(limiter, 'checkChannelLimit').mockResolvedValue({ allowed: true, remaining: 49 });
      const userLimitSpy = vi.spyOn(limiter, 'checkUserLimit');

      const result = await limiter.checkAllLimits(tenantId, undefined, 'email');

      expect(result.allowed).toBe(true);
      expect(userLimitSpy).not.toHaveBeenCalled();
    });

    it('checkAllLimits - 短路执行：租户限流后不再检查渠道和用户', async () => {
      vi.spyOn(limiter, 'checkTenantLimit').mockResolvedValue({ allowed: false, remaining: 0, retryAfter: 30 });
      const channelSpy = vi.spyOn(limiter, 'checkChannelLimit');
      const userSpy = vi.spyOn(limiter, 'checkUserLimit');

      await limiter.checkAllLimits(tenantId, userId, 'email');

      expect(channelSpy).not.toHaveBeenCalled();
      expect(userSpy).not.toHaveBeenCalled();
    });

    it('checkChannelLimit - 未知渠道返回通过', async () => {
      const result = await limiter.checkChannelLimit('webhook' as any);
      expect(result.allowed).toBe(true);
      expect(result.remaining).toBe(999999);
    });

    it('checkUserLimit - 未知渠道返回通过', async () => {
      const result = await limiter.checkUserLimit(userId, 'webhook' as any);
      expect(result.allowed).toBe(true);
      expect(result.remaining).toBe(999999);
    });
  });

  describe('Redis故障处理 - fail closed', () => {
    it('Redis连接断开时抛出异常（fail closed）', async () => {
      mockRedis.evalsha.mockRejectedValueOnce(new Error('ECONNREFUSED: Connection refused'));

      await expect(
        limiter.tryConsume('test', 'fail-test', 10, 60, 1)
      ).rejects.toThrow('ECONNREFUSED');
    });

    it('Redis超时抛出异常', async () => {
      mockRedis.evalsha.mockRejectedValueOnce(new Error('Command timed out'));

      await expect(
        limiter.checkChannelLimit('email')
      ).rejects.toThrow('Command timed out');
    });

    it('Redis返回异常数据时正确处理', async () => {
      mockRedis.evalsha.mockResolvedValueOnce(['invalid', 'data']);

      const result = await limiter.tryConsume('test', 'invalid-data', 10, 60, 1);
      expect(result.allowed).toBe(false);
    });

    it('Redis返回null时抛出异常', async () => {
      mockRedis.evalsha.mockResolvedValueOnce(null);

      await expect(
        limiter.tryConsume('test', 'null-data', 10, 60, 1)
      ).rejects.toThrow();
    });

    it('Redis返回不完整数组时正确处理', async () => {
      mockRedis.evalsha.mockResolvedValueOnce([1]);

      const result = await limiter.tryConsume('test', 'incomplete', 10, 60, 1);
      expect(result.allowed).toBe(true);
    });
  });

  describe('Script SHA缓存与NOSCRIPT重载', () => {
    it('首次调用时加载Lua脚本并缓存SHA', async () => {
      mockRedis.evalsha.mockResolvedValueOnce([1, 9, 0]);

      await limiter.tryConsume('test', 'sha-test', 10, 60, 1);

      expect(mockRedis.script).toHaveBeenCalledWith('LOAD', expect.any(String));
    });

    it('SHA缓存后不再重复加载脚本', async () => {
      mockRedis.evalsha
        .mockResolvedValueOnce([1, 9, 0])
        .mockResolvedValueOnce([1, 8, 0]);

      await limiter.tryConsume('test', 'sha-cache', 10, 60, 1);
      await limiter.tryConsume('test', 'sha-cache', 10, 60, 1);

      expect(mockRedis.script).toHaveBeenCalledTimes(1);
    });

    it('NOSCRIPT错误时清除缓存并重新加载', async () => {
      mockRedis.evalsha
        .mockRejectedValueOnce(new Error('NOSCRIPT No matching script. Please use EVAL.'))
        .mockResolvedValueOnce([1, 9, 0]);

      const result = await limiter.tryConsume('test', 'noscript-test', 10, 60, 1);

      expect(mockRedis.script).toHaveBeenCalledTimes(2);
      expect(result.allowed).toBe(true);
      expect(result.remaining).toBe(9);
    });

    it('NOSCRIPT重载后新SHA被缓存', async () => {
      mockRedis.evalsha
        .mockRejectedValueOnce(new Error('NOSCRIPT No matching script'))
        .mockResolvedValueOnce([1, 9, 0])
        .mockResolvedValueOnce([1, 8, 0]);

      await limiter.tryConsume('test', 'noscript-reload', 10, 60, 1);
      await limiter.tryConsume('test', 'noscript-reload', 10, 60, 1);

      expect(mockRedis.script).toHaveBeenCalledTimes(2);
    });
  });

  describe('高并发场景测试', () => {
    it('100个并发请求下限流计数正确', async () => {
      const maxRequests = 50;
      const windowSeconds = 60;
      let currentCount = 0;

      mockRedis.evalsha.mockImplementation(async () => {
        if (currentCount < maxRequests) {
          currentCount++;
          return [1, maxRequests - currentCount, 0];
        }
        return [0, 0, 1];
      });

      const promises = Array.from({ length: 100 }, () =>
        limiter.tryConsume('concurrent', 'key-1', maxRequests, windowSeconds, 1)
      );

      const results = await Promise.all(promises);

      const allowed = results.filter(r => r.allowed).length;
      const rejected = results.filter(r => !r.allowed).length;

      expect(allowed).toBe(50);
      expect(rejected).toBe(50);
    });

    it('不同租户的限流相互独立', async () => {
      const tenant1 = uuidv4();
      const tenant2 = uuidv4();
      const counters: Record<string, number> = { [tenant1]: 0, [tenant2]: 0 };

      mockRedis.evalsha.mockImplementation(async (_sha: string, _numkeys: number, key: string) => {
        const tenant = key.includes(tenant1) ? tenant1 : tenant2;
        const maxForTenant = tenant === tenant1 ? 10 : 5;
        if (counters[tenant] < maxForTenant) {
          counters[tenant]++;
          return [1, maxForTenant - counters[tenant], 0];
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

  describe('配置驱动的限流测试', () => {
    it('渠道级限流使用配置的max和window', async () => {
      const emailConfig = config.rateLimit.channel.email;
      const expectedMax = emailConfig.max;
      const expectedWindow = emailConfig.window;

      mockRedis.evalsha.mockResolvedValueOnce([1, expectedMax - 1, 0]);

      await limiter.checkChannelLimit('email');

      const evalshaArgs = mockRedis.evalsha.mock.calls[0];
      expect(parseInt(evalshaArgs[5])).toBe(expectedMax);
      expect(parseInt(evalshaArgs[4])).toBe(expectedWindow * 1000);
    });

    it('用户级短信限流使用配置的每分钟5条限制', async () => {
      const smsConfig = config.rateLimit.user.sms;
      expect(smsConfig.max).toBe(5);
      expect(smsConfig.window).toBe(60);

      mockRedis.evalsha.mockResolvedValueOnce([1, 4, 0]);

      await limiter.checkUserLimit(userId, 'sms');

      const evalshaArgs = mockRedis.evalsha.mock.calls[0];
      expect(parseInt(evalshaArgs[5])).toBe(5);
      expect(parseInt(evalshaArgs[4])).toBe(60 * 1000);
    });

    it('租户级并发限制使用配置值，窗口60秒', async () => {
      const tenantConcurrency = config.rateLimit.tenant.concurrency;
      expect(tenantConcurrency).toBe(100);

      mockRedis.evalsha.mockResolvedValueOnce([1, 99, 0]);

      await limiter.checkTenantLimit(tenantId);

      const evalshaArgs = mockRedis.evalsha.mock.calls[0];
      expect(parseInt(evalshaArgs[5])).toBe(100);
      expect(parseInt(evalshaArgs[4])).toBe(60 * 1000);
    });
  });

  describe('窗口计数查询与重置', () => {
    it('getWindowCount返回窗口内当前计数', async () => {
      mockRedis.zremrangebyscore.mockResolvedValueOnce(3);
      mockRedis.zcard.mockResolvedValueOnce(7);

      const count = await limiter.getWindowCount('channel', 'email', 86400);

      expect(count).toBe(7);
      expect(mockRedis.zremrangebyscore).toHaveBeenCalledWith(
        'ratelimit:channel:email',
        '-inf',
        expect.any(Number)
      );
      expect(mockRedis.zcard).toHaveBeenCalledWith('ratelimit:channel:email');
    });

    it('getStats返回计数和成员详情', async () => {
      mockRedis.zcard.mockResolvedValueOnce(3);
      mockRedis.zrange.mockResolvedValueOnce(['member1', '1000', 'member2', '2000', 'member3', '3000']);

      const stats = await limiter.getStats('test', 'stats-key');

      expect(stats.count).toBe(3);
      expect(stats.members).toHaveLength(6);
      expect(mockRedis.zcard).toHaveBeenCalledWith('ratelimit:test:stats-key');
      expect(mockRedis.zrange).toHaveBeenCalledWith('ratelimit:test:stats-key', 0, -1, 'WITHSCORES');
    });

    it('reset删除限流key', async () => {
      mockRedis.del.mockResolvedValueOnce(1);

      await limiter.reset('test', 'reset-key');

      expect(mockRedis.del).toHaveBeenCalledWith('ratelimit:test:reset-key');
    });

    it('getWindowCount空窗口返回0', async () => {
      mockRedis.zremrangebyscore.mockResolvedValueOnce(0);
      mockRedis.zcard.mockResolvedValueOnce(0);

      const count = await limiter.getWindowCount('test', 'empty', 60);
      expect(count).toBe(0);
    });
  });

  describe('表驱动测试 - 限流阈值', () => {
    interface ThresholdTestCase {
      maxRequests: number;
      windowSeconds: number;
      requestCount: number;
      expectedAllowed: number;
      expectedRejected: number;
    }

    const thresholdCases: { name: string; input: ThresholdTestCase }[] = [
      {
        name: '5次/分钟 - 恰好5次通过',
        input: { maxRequests: 5, windowSeconds: 60, requestCount: 5, expectedAllowed: 5, expectedRejected: 0 },
      },
      {
        name: '5次/分钟 - 6次请求1次被拒',
        input: { maxRequests: 5, windowSeconds: 60, requestCount: 6, expectedAllowed: 5, expectedRejected: 1 },
      },
      {
        name: '100次/小时 - 100次通过',
        input: { maxRequests: 100, windowSeconds: 3600, requestCount: 100, expectedAllowed: 100, expectedRejected: 0 },
      },
      {
        name: '1次/秒 - 3次请求2次被拒',
        input: { maxRequests: 1, windowSeconds: 1, requestCount: 3, expectedAllowed: 1, expectedRejected: 2 },
      },
      {
        name: '1000次/天 - 单次通过',
        input: { maxRequests: 1000, windowSeconds: 86400, requestCount: 1, expectedAllowed: 1, expectedRejected: 0 },
      },
    ];

    for (const tc of thresholdCases) {
      it(tc.name, async () => {
        let consumed = 0;
        const { maxRequests, windowSeconds, requestCount } = tc.input;

        mockRedis.evalsha.mockImplementation(async () => {
          if (consumed < maxRequests) {
            consumed++;
            return [1, maxRequests - consumed, 0];
          }
          return [0, 0, 1];
        });

        const results = await Promise.all(
          Array.from({ length: requestCount }, () =>
            limiter.tryConsume('threshold', 'key', maxRequests, windowSeconds, 1)
          )
        );

        const allowed = results.filter(r => r.allowed).length;
        const rejected = results.filter(r => !r.allowed).length;

        expect(allowed).toBe(tc.input.expectedAllowed);
        expect(rejected).toBe(tc.input.expectedRejected);
      });
    }
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
            vi.spyOn(limiter, 'checkUserLimit');
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

  describe('Lua脚本逻辑验证', () => {
    it('首次请求在空窗口中成功', async () => {
      mockRedis.evalsha.mockImplementation(async () => [1, 9, 0]);

      const result = await limiter.tryConsume('test', 'lua-test', 10, 60, 1);
      expect(result.allowed).toBe(true);
      expect(result.remaining).toBe(9);
    });

    it('evalsha传递正确的参数格式', async () => {
      mockRedis.evalsha.mockResolvedValueOnce([1, 9, 0]);

      await limiter.tryConsume('test', 'args-test', 10, 60, 1);

      const callArgs = mockRedis.evalsha.mock.calls[0];
      expect(callArgs[1]).toBe(1);
      expect(callArgs[2]).toBe('ratelimit:test:args-test');
      expect(callArgs[4]).toBe('60000');
      expect(callArgs[5]).toBe('10');
      expect(callArgs[6]).toBe('1');
      expect(callArgs[7]).toBe('test:args-test');
    });

    it('windowSeconds被正确转换为毫秒', async () => {
      mockRedis.evalsha.mockResolvedValueOnce([1, 99, 0]);

      await limiter.tryConsume('test', 'window-ms', 100, 120, 1);

      const callArgs = mockRedis.evalsha.mock.calls[0];
      expect(callArgs[4]).toBe('120000');
    });
  });

  describe('边界条件测试', () => {
    it('key包含特殊字符时正确处理', async () => {
      const specialKey = 'user@example.com:channel/sms?test=1';
      mockRedis.evalsha.mockResolvedValueOnce([1, 9, 0]);

      const result = await limiter.tryConsume('test', specialKey, 10, 60, 1);
      expect(result.allowed).toBe(true);

      const callArgs = mockRedis.evalsha.mock.calls[0];
      expect(callArgs[2]).toBe(`ratelimit:test:${specialKey}`);
    });

    it('remaining不会返回负数', async () => {
      mockRedis.evalsha.mockResolvedValueOnce([1, -2, 0]);

      const result = await limiter.tryConsume('test', 'negative', 10, 60, 1);
      expect(result.remaining).toBe(0);
    });

    it('retryAfter为0时返回undefined', async () => {
      mockRedis.evalsha.mockResolvedValueOnce([0, 0, 0]);

      const result = await limiter.tryConsume('test', 'zero-retry', 10, 60, 1);
      expect(result.allowed).toBe(false);
      expect(result.retryAfter).toBeUndefined();
    });

    it('单例模式 - getInstance返回同一实例', () => {
      const instance1 = SlidingWindowRateLimiter.getInstance();
      const instance2 = SlidingWindowRateLimiter.getInstance();
      expect(instance1).toBe(instance2);
    });
  });
});
