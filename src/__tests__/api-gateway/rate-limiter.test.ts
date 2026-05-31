import { RateLimiter, RateLimitConfig } from '../../api-gateway/rate-limiter';

describe('RateLimiter', () => {
  describe('Memory Backend', () => {
    const config: RateLimitConfig = {
      maxRequests: 5,
      windowMs: 1000,
      keyPrefix: 'test:',
    };

    it('should allow requests within limit', async () => {
      const limiter = new RateLimiter(config);

      for (let i = 0; i < 5; i++) {
        const result = await limiter.checkLimit(`user${i}`);
        expect(result.allowed).toBe(true);
      }

      limiter.destroy();
    });

    it('should reject requests exceeding limit', async () => {
      const limiter = new RateLimiter(config);
      const clientId = 'user1';

      for (let i = 0; i < 5; i++) {
        await limiter.checkLimit(clientId);
      }

      const result = await limiter.checkLimit(clientId);
      expect(result.allowed).toBe(false);
      expect(result.info.resetTime).toBeGreaterThan(0);
      expect(result.info.remaining).toBe(0);

      limiter.destroy();
    });

    it('should reset limit after window expires', async () => {
      const limiter = new RateLimiter({
        maxRequests: 2,
        windowMs: 100,
        keyPrefix: 'test:',
      });
      const clientId = 'user-reset';

      await limiter.checkLimit(clientId);
      await limiter.checkLimit(clientId);
      const blocked = await limiter.checkLimit(clientId);
      expect(blocked.allowed).toBe(false);

      await new Promise(resolve => setTimeout(resolve, 110));

      const result = await limiter.checkLimit(clientId);
      expect(result.allowed).toBe(true);

      limiter.destroy();
    });

    it('should track usage per client independently', async () => {
      const limiter = new RateLimiter(config);

      for (let i = 0; i < 5; i++) {
        await limiter.checkLimit('user1');
      }

      const resultUser2 = await limiter.checkLimit('user2');
      expect(resultUser2.allowed).toBe(true);

      limiter.destroy();
    });

    it('should handle empty clientId', async () => {
      const limiter = new RateLimiter(config);
      const result = await limiter.checkLimit('');
      expect(result.allowed).toBe(true);
      limiter.destroy();
    });

    it('should handle very long clientId', async () => {
      const limiter = new RateLimiter(config);
      const longId = 'x'.repeat(10000);
      const result = await limiter.checkLimit(longId);
      expect(result.allowed).toBe(true);
      limiter.destroy();
    });

    it('should return correct remaining count', async () => {
      const limiter = new RateLimiter(config);
      const clientId = 'remaining-test';

      const result1 = await limiter.checkLimit(clientId);
      expect(result1.info.remaining).toBe(4);

      const result2 = await limiter.checkLimit(clientId);
      expect(result2.info.remaining).toBe(3);

      limiter.destroy();
    });

    it('should return zero remaining when limit reached', async () => {
      const limiter = new RateLimiter({
        maxRequests: 2,
        windowMs: 1000,
        keyPrefix: 'test:',
      });
      const clientId = 'zero-remaining';

      await limiter.checkLimit(clientId);
      const result = await limiter.checkLimit(clientId);
      expect(result.info.remaining).toBe(0);

      limiter.destroy();
    });

    it('should allow large maxRequests value', async () => {
      const limiter = new RateLimiter({
        maxRequests: 1000000,
        windowMs: 1000,
        keyPrefix: 'test:',
      });

      const result = await limiter.checkLimit('user');
      expect(result.allowed).toBe(true);
      expect(result.info.remaining).toBe(999999);

      limiter.destroy();
    });
  });

  describe('Concurrency', () => {
    it('should handle concurrent requests correctly', async () => {
      const limiter = new RateLimiter({
        maxRequests: 100,
        windowMs: 10000,
        keyPrefix: 'concurrent:',
      });
      const clientId = 'concurrent-user';

      const promises = Array.from({ length: 150 }, () =>
        limiter.checkLimit(clientId)
      );

      const results = await Promise.all(promises);
      const allowed = results.filter(r => r.allowed).length;
      const rejected = results.filter(r => !r.allowed).length;

      expect(allowed).toBe(100);
      expect(rejected).toBe(50);

      limiter.destroy();
    });

    it('should maintain atomicity under high concurrency', async () => {
      const limiter = new RateLimiter({
        maxRequests: 10,
        windowMs: 10000,
        keyPrefix: 'atomic:',
      });

      const clientIds = ['user1', 'user2', 'user3', 'user4', 'user5'];
      const promises: Promise<any>[] = [];

      for (let i = 0; i < 100; i++) {
        const clientId = clientIds[i % clientIds.length];
        promises.push(limiter.checkLimit(clientId));
      }

      await expect(Promise.all(promises)).resolves.not.toThrow();

      limiter.destroy();
    }, 10000);
  });

  describe('Error Handling', () => {
    it('should throw error when destroyed', async () => {
      const limiter = new RateLimiter({
        maxRequests: 5,
        windowMs: 1000,
        keyPrefix: 'test:',
      });

      limiter.destroy();

      await expect(limiter.checkLimit('user')).rejects.toThrow();
    });

    it('should throw error on resetLimit when destroyed', async () => {
      const limiter = new RateLimiter({
        maxRequests: 5,
        windowMs: 1000,
        keyPrefix: 'test:',
      });

      limiter.destroy();

      await expect(limiter.resetLimit('user')).rejects.toThrow();
    });
  });

  describe('Reset Limit', () => {
    it('should reset limit for a client', async () => {
      const limiter = new RateLimiter({
        maxRequests: 2,
        windowMs: 10000,
        keyPrefix: 'reset:',
      });
      const clientId = 'reset-user';

      await limiter.checkLimit(clientId);
      await limiter.checkLimit(clientId);

      const beforeReset = await limiter.checkLimit(clientId);
      expect(beforeReset.allowed).toBe(false);

      await limiter.resetLimit(clientId);

      const afterReset = await limiter.checkLimit(clientId);
      expect(afterReset.allowed).toBe(true);

      limiter.destroy();
    });

    it('should not affect other clients when resetting', async () => {
      const limiter = new RateLimiter({
        maxRequests: 2,
        windowMs: 10000,
        keyPrefix: 'reset:',
      });

      await limiter.checkLimit('user1');
      await limiter.checkLimit('user1');
      await limiter.checkLimit('user2');
      await limiter.checkLimit('user2');

      await limiter.resetLimit('user1');

      const result1 = await limiter.checkLimit('user1');
      const result2 = await limiter.checkLimit('user2');

      expect(result1.allowed).toBe(true);
      expect(result2.allowed).toBe(false);

      limiter.destroy();
    });
  });

  describe('RateLimitInfo', () => {
    it('should return correct limit info', async () => {
      const config: RateLimitConfig = {
        maxRequests: 5,
        windowMs: 1000,
        keyPrefix: 'test:',
      };
      const limiter = new RateLimiter(config);
      const result = await limiter.checkLimit('info-user');

      expect(result.info.limit).toBe(5);
      expect(result.info.remaining).toBe(4);
      expect(typeof result.info.resetTime).toBe('number');
      expect(result.info.resetTime).toBeGreaterThan(0);

      limiter.destroy();
    });

    it('should return correct info when blocked', async () => {
      const limiter = new RateLimiter({
        maxRequests: 1,
        windowMs: 1000,
        keyPrefix: 'info:',
      });

      await limiter.checkLimit('blocked-user');
      const result = await limiter.checkLimit('blocked-user');

      expect(result.info.remaining).toBe(0);
      expect(result.info.limit).toBe(1);

      limiter.destroy();
    });
  });
});
