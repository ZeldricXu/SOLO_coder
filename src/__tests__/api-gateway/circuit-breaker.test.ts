import { CircuitBreaker } from '../../api-gateway/circuit-breaker';

describe('CircuitBreaker', () => {
  describe('Initialization', () => {
    it('should initialize with default config', () => {
      const cb = new CircuitBreaker();
      expect(cb.getState()).toBe('closed');
    });

    it('should initialize with custom config', () => {
      const cb = new CircuitBreaker({
        failureThreshold: 3,
        timeout: 5000,
        recoveryTimeout: 10000,
      });
      expect(cb.getState()).toBe('closed');
    });

    it('should use default values for partial config', () => {
      const cb = new CircuitBreaker({
        failureThreshold: 2,
      });
      expect(cb.getState()).toBe('closed');
    });
  });

  describe('Closed State', () => {
    it('should execute successful function in closed state', async () => {
      const cb = new CircuitBreaker();
      const fn = jest.fn().mockResolvedValue('success');

      const result = await cb.execute(fn);

      expect(result).toBe('success');
      expect(fn).toHaveBeenCalled();
      expect(cb.getState()).toBe('closed');
    });

    it('should track failures in closed state', async () => {
      const cb = new CircuitBreaker({ failureThreshold: 3 });
      const failingFn = jest.fn().mockRejectedValue(new Error('Failed'));

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('closed');

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('closed');
    });

    it('should open circuit after threshold failures', async () => {
      const cb = new CircuitBreaker({ failureThreshold: 2 });
      const failingFn = jest.fn().mockRejectedValue(new Error('Failed'));

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');

      expect(cb.getState()).toBe('open');
    });
  });

  describe('Open State', () => {
    it('should reject immediately when circuit is open', async () => {
      const cb = new CircuitBreaker({ failureThreshold: 1 });
      const failingFn = jest.fn().mockRejectedValue(new Error('Failed'));

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('open');

      const anotherFn = jest.fn().mockResolvedValue('success');
      await expect(cb.execute(anotherFn)).rejects.toThrow('Circuit breaker is open');
      expect(anotherFn).not.toHaveBeenCalled();
    });

    it('should transition to half-open after recovery timeout', async () => {
      const cb = new CircuitBreaker({
        failureThreshold: 1,
        recoveryTimeout: 50,
      });
      const failingFn = jest.fn().mockRejectedValue(new Error('Failed'));

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('open');

      await new Promise(resolve => setTimeout(resolve, 60));

      const successFn = jest.fn().mockResolvedValue('success');
      const result = await cb.execute(successFn);

      expect(result).toBe('success');
      expect(cb.getState()).toBe('closed');
    });

    it('should remain open before recovery timeout', async () => {
      const cb = new CircuitBreaker({
        failureThreshold: 1,
        recoveryTimeout: 1000,
      });
      const failingFn = jest.fn().mockRejectedValue(new Error('Failed'));

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('open');

      const anotherFn = jest.fn().mockResolvedValue('success');
      await expect(cb.execute(anotherFn)).rejects.toThrow('Circuit breaker is open');
      expect(cb.getState()).toBe('open');
    });
  });

  describe('Half-Open State', () => {
    it('should close circuit on success in half-open state', async () => {
      const cb = new CircuitBreaker({
        failureThreshold: 1,
        recoveryTimeout: 50,
      });
      const failingFn = jest.fn().mockRejectedValue(new Error('Failed'));

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('open');

      await new Promise(resolve => setTimeout(resolve, 60));

      const successFn = jest.fn().mockResolvedValue('success');
      await cb.execute(successFn);

      expect(cb.getState()).toBe('closed');
    });

    it('should open circuit on failure in half-open state', async () => {
      const cb = new CircuitBreaker({
        failureThreshold: 1,
        recoveryTimeout: 50,
      });
      const failingFn = jest.fn().mockRejectedValue(new Error('Failed'));

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('open');

      await new Promise(resolve => setTimeout(resolve, 60));

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('open');
    });
  });

  describe('Timeout', () => {
    it('should timeout long-running operations', async () => {
      const cb = new CircuitBreaker({ timeout: 50 });
      const slowFn = jest.fn().mockImplementation(
        () => new Promise(resolve => setTimeout(() => resolve('slow'), 100))
      );

      await expect(cb.execute(slowFn)).rejects.toThrow('Operation timed out');
    });

    it('should complete fast operations before timeout', async () => {
      const cb = new CircuitBreaker({ timeout: 100 });
      const fastFn = jest.fn().mockImplementation(
        () => new Promise(resolve => setTimeout(() => resolve('fast'), 10))
      );

      const result = await cb.execute(fastFn);
      expect(result).toBe('fast');
    });
  });

  describe('Reset', () => {
    it('should reset to closed state', async () => {
      const cb = new CircuitBreaker({ failureThreshold: 1 });
      const failingFn = jest.fn().mockRejectedValue(new Error('Failed'));

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('open');

      cb.reset();
      expect(cb.getState()).toBe('closed');

      const successFn = jest.fn().mockResolvedValue('success');
      const result = await cb.execute(successFn);
      expect(result).toBe('success');
    });

    it('should reset failure count', async () => {
      const cb = new CircuitBreaker({ failureThreshold: 3 });
      const failingFn = jest.fn().mockRejectedValue(new Error('Failed'));

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');

      cb.reset();

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('closed');
    });
  });

  describe('Edge Cases', () => {
    it('should handle zero failure threshold', () => {
      const cb = new CircuitBreaker({ failureThreshold: 0 });
      expect(cb.getState()).toBe('closed');
    });

    it('should handle very short timeout', async () => {
      const cb = new CircuitBreaker({ timeout: 1 });
      const fn = jest.fn().mockResolvedValue('success');

      const result = await cb.execute(fn);
      expect(result).toBe('success');
    });

    it('should handle concurrent executions', async () => {
      const cb = new CircuitBreaker({ failureThreshold: 10 });
      const fn = jest.fn().mockResolvedValue('success');

      const promises = Array.from({ length: 20 }, () => cb.execute(fn));
      const results = await Promise.all(promises);

      expect(results.every(r => r === 'success')).toBe(true);
      expect(fn).toHaveBeenCalledTimes(20);
      expect(cb.getState()).toBe('closed');
    });

    it('should handle function that returns null', async () => {
      const cb = new CircuitBreaker();
      const fn = jest.fn().mockResolvedValue(null);

      const result = await cb.execute(fn);
      expect(result).toBeNull();
    });

    it('should handle function that returns undefined', async () => {
      const cb = new CircuitBreaker();
      const fn = jest.fn().mockResolvedValue(undefined);

      const result = await cb.execute(fn);
      expect(result).toBeUndefined();
    });
  });

  describe('State Transitions', () => {
    it('should go through full cycle: closed -> open -> half-open -> closed', async () => {
      const cb = new CircuitBreaker({
        failureThreshold: 2,
        recoveryTimeout: 50,
      });

      expect(cb.getState()).toBe('closed');

      const failingFn = jest.fn().mockRejectedValue(new Error('Failed'));
      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('closed');

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('open');

      await new Promise(resolve => setTimeout(resolve, 60));

      const successFn = jest.fn().mockResolvedValue('success');
      await cb.execute(successFn);
      expect(cb.getState()).toBe('closed');
    });

    it('should go through full cycle: closed -> open -> half-open -> open', async () => {
      const cb = new CircuitBreaker({
        failureThreshold: 2,
        recoveryTimeout: 50,
      });

      expect(cb.getState()).toBe('closed');

      const failingFn = jest.fn().mockRejectedValue(new Error('Failed'));
      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('open');

      await new Promise(resolve => setTimeout(resolve, 60));

      await expect(cb.execute(failingFn)).rejects.toThrow('Failed');
      expect(cb.getState()).toBe('open');
    });
  });
});
