import {
  generateId,
  generateTraceId,
  nowISO,
  nowEpoch,
  sleep,
  retryAsync,
  safeJsonParse,
  safeJsonStringify,
  deepClone,
  chunkArray,
  calculateP99,
  calculateP95,
  calculateAvg,
  formatBytes,
} from '@utils/index';

describe('Utils', () => {
  describe('generateId', () => {
    it('should generate id with prefix', () => {
      const id = generateId('test_');
      expect(id).toMatch(/^test_[a-f0-9]{24}$/);
    });

    it('should generate id without prefix', () => {
      const id = generateId();
      expect(id).toMatch(/^[a-f0-9]{24}$/);
    });
  });

  describe('generateTraceId', () => {
    it('should generate valid trace id', () => {
      const traceId = generateTraceId();
      expect(traceId).toMatch(/^[a-f0-9]{32}$/);
    });
  });

  describe('nowISO', () => {
    it('should return valid ISO date string', () => {
      const iso = nowISO();
      expect(() => new Date(iso)).not.toThrow();
    });
  });

  describe('nowEpoch', () => {
    it('should return current timestamp in milliseconds', () => {
      const before = Date.now();
      const epoch = nowEpoch();
      const after = Date.now();
      expect(epoch).toBeGreaterThanOrEqual(before);
      expect(epoch).toBeLessThanOrEqual(after);
    });
  });

  describe('sleep', () => {
    it('should sleep for specified duration', async () => {
      const start = Date.now();
      await sleep(50);
      const duration = Date.now() - start;
      expect(duration).toBeGreaterThanOrEqual(45);
    });
  });

  describe('retryAsync', () => {
    it('should succeed on first attempt', async () => {
      const fn = jest.fn().mockResolvedValue('success');
      const result = await retryAsync(fn, 3, 10);
      expect(result).toBe('success');
      expect(fn).toHaveBeenCalledTimes(1);
    });

    it('should retry on failure', async () => {
      let attempt = 0;
      const fn = jest.fn(async () => {
        attempt++;
        if (attempt < 3) throw new Error('fail');
        return 'success';
      });
      const result = await retryAsync(fn, 3, 10);
      expect(result).toBe('success');
      expect(fn).toHaveBeenCalledTimes(3);
    });

    it('should fail after max retries', async () => {
      const fn = jest.fn().mockRejectedValue(new Error('always fail'));
      await expect(retryAsync(fn, 2, 10)).rejects.toThrow('always fail');
      expect(fn).toHaveBeenCalledTimes(2);
    });
  });

  describe('safeJsonParse', () => {
    it('should parse valid JSON', () => {
      const result = safeJsonParse('{"key":"value"}', { default: 'fallback' });
      expect(result).toEqual({ key: 'value' });
    });

    it('should return fallback on invalid JSON', () => {
      const fallback = { default: 'fallback' };
      const result = safeJsonParse('invalid json', fallback);
      expect(result).toEqual(fallback);
    });
  });

  describe('safeJsonStringify', () => {
    it('should stringify valid object', () => {
      const obj = { key: 'value' };
      const result = safeJsonStringify(obj);
      expect(result).toBe(JSON.stringify(obj));
    });

    it('should return empty object on circular reference', () => {
      const obj: Record<string, unknown> = {};
      obj.self = obj;
      const result = safeJsonStringify(obj);
      expect(result).toBe('{}');
    });
  });

  describe('deepClone', () => {
    it('should deep clone object', () => {
      const original = { nested: { key: 'value' }, arr: [1, 2, 3] };
      const cloned = deepClone(original);
      expect(cloned).toEqual(original);
      expect(cloned).not.toBe(original);
      expect(cloned.nested).not.toBe(original.nested);
    });
  });

  describe('chunkArray', () => {
    it('should split array into chunks', () => {
      const arr = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
      const chunks = chunkArray(arr, 3);
      expect(chunks).toEqual([[1, 2, 3], [4, 5, 6], [7, 8, 9], [10]]);
    });

    it('should handle empty array', () => {
      expect(chunkArray([], 3)).toEqual([]);
    });
  });

  describe('calculateP99', () => {
    it('should calculate 99th percentile', () => {
      const values = Array.from({ length: 100 }, (_, i) => i + 1);
      expect(calculateP99(values)).toBe(99);
    });

    it('should return 0 for empty array', () => {
      expect(calculateP99([])).toBe(0);
    });
  });

  describe('calculateP95', () => {
    it('should calculate 95th percentile', () => {
      const values = Array.from({ length: 100 }, (_, i) => i + 1);
      expect(calculateP95(values)).toBe(95);
    });
  });

  describe('calculateAvg', () => {
    it('should calculate average', () => {
      expect(calculateAvg([1, 2, 3, 4, 5])).toBe(3);
    });

    it('should return 0 for empty array', () => {
      expect(calculateAvg([])).toBe(0);
    });
  });

  describe('formatBytes', () => {
    it('should format bytes correctly', () => {
      expect(formatBytes(0)).toBe('0.00 B');
      expect(formatBytes(1024)).toBe('1.00 KB');
      expect(formatBytes(1024 * 1024)).toBe('1.00 MB');
      expect(formatBytes(1024 * 1024 * 1024)).toBe('1.00 GB');
    });
  });
});
