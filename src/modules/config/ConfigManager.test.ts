import { ConfigManager } from './ConfigManager';
import { IConfigSource } from './ConfigSource';

describe('ConfigManager - 性能修复测试', () => {
  let manager: ConfigManager;

  beforeEach(() => {
    manager = new ConfigManager();
  });

  describe('扁平化缓存机制', () => {
    it('get 方法应使用缓存提升性能', () => {
      const testConfig = {
        database: {
          host: 'localhost',
          port: 5432,
          pool: {
            min: 2,
            max: 20
          }
        },
        redis: {
          host: 'localhost',
          port: 6379
        }
      };

      const mockSource: IConfigSource = {
        name: 'test',
        priority: 100,
        load: jest.fn().mockResolvedValue(testConfig),
        watch: jest.fn(),
        stopWatching: jest.fn()
      };

      manager.addSource(mockSource);

      return manager.initialize().then(() => {
        const startTime = Date.now();
        for (let i = 0; i < 10000; i++) {
          manager.get('database.host');
          manager.get('database.pool.max');
          manager.get('redis.port');
        }
        const duration = Date.now() - startTime;

        expect(duration).toBeLessThan(100);

        const stats = manager.getStats();
        expect(stats.cacheHits).toBeGreaterThan(0);
        expect(stats.cacheHitRate).toBeGreaterThan(0.9);
      });
    });

    it('getMany 方法应批量获取配置', () => {
      const testConfig = {
        app: {
          name: 'TestApp',
          version: '1.0.0',
          env: 'production'
        },
        database: {
          host: 'localhost',
          port: 5432
        }
      };

      const mockSource: IConfigSource = {
        name: 'test',
        priority: 100,
        load: jest.fn().mockResolvedValue(testConfig),
        watch: jest.fn(),
        stopWatching: jest.fn()
      };

      manager.addSource(mockSource);

      return manager.initialize().then(() => {
        const result = manager.getMany([
          'app.name',
          'app.version',
          'database.host',
          'database.port'
        ]);

        expect(result.get('app.name')).toBe('TestApp');
        expect(result.get('app.version')).toBe('1.0.0');
        expect(result.get('database.host')).toBe('localhost');
        expect(result.get('database.port')).toBe(5432);
      });
    });

    it('配置变更时缓存应失效并重建', () => {
      const testConfig = {
        app: {
          name: 'OldName'
        }
      };

      const mockSource: IConfigSource = {
        name: 'test',
        priority: 100,
        load: jest.fn().mockResolvedValue(testConfig),
        watch: jest.fn(),
        stopWatching: jest.fn()
      };

      manager.addSource(mockSource);

      return manager.initialize().then(() => {
        const oldValue = manager.get('app.name');
        expect(oldValue).toBe('OldName');

        manager.set('app.name', 'NewName');

        const newValue = manager.get('app.name');
        expect(newValue).toBe('NewName');
      });
    });

    it('setMany 方法应批量设置配置', () => {
      return manager.initialize().then(() => {
        manager.setMany([
          { key: 'a.b.c', value: 1 },
          { key: 'a.b.d', value: 2 },
          { key: 'x.y', value: 3 }
        ]);

        expect(manager.get('a.b.c')).toBe(1);
        expect(manager.get('a.b.d')).toBe(2);
        expect(manager.get('x.y')).toBe(3);
      });
    });
  });

  describe('性能对比测试', () => {
    it('大数据量场景下性能应显著提升', () => {
      const largeConfig: Record<string, unknown> = {};
      for (let i = 0; i < 1000; i++) {
        largeConfig[`key${i}`] = {
          value: i,
          nested: {
            deep: {
              value: `deep_${i}`
            }
          }
        };
      }

      const mockSource: IConfigSource = {
        name: 'test',
        priority: 100,
        load: jest.fn().mockResolvedValue(largeConfig),
        watch: jest.fn(),
        stopWatching: jest.fn()
      };

      manager.addSource(mockSource);

      return manager.initialize().then(() => {
        const startTime = Date.now();
        for (let i = 0; i < 1000; i++) {
          manager.get(`key${i}.nested.deep.value`);
        }
        const duration = Date.now() - startTime;

        expect(duration).toBeLessThan(50);

        const stats = manager.getStats();
        expect(stats.cacheHitRate).toBeGreaterThan(0.95);
      });
    });

    it('缓存命中率应随访问次数增加而提升', () => {
      const testConfig = {
        a: { b: { c: { d: { e: 1 } } } },
        x: { y: { z: 2 } }
      };

      const mockSource: IConfigSource = {
        name: 'test',
        priority: 100,
        load: jest.fn().mockResolvedValue(testConfig),
        watch: jest.fn(),
        stopWatching: jest.fn()
      };

      manager.addSource(mockSource);

      return manager.initialize().then(() => {
        manager.resetStats();

        manager.get('a.b.c.d.e');
        expect(manager.getStats().cacheHitRate).toBe(0);

        manager.get('a.b.c.d.e');
        manager.get('a.b.c.d.e');
        manager.get('a.b.c.d.e');
        manager.get('a.b.c.d.e');

        const stats = manager.getStats();
        expect(stats.cacheHits).toBe(4);
        expect(stats.cacheHitRate).toBe(0.8);
      });
    });
  });

  describe('缓存统计', () => {
    it('getStats 应返回正确的缓存统计信息', () => {
      const testConfig = {
        app: { name: 'Test' }
      };

      const mockSource: IConfigSource = {
        name: 'test',
        priority: 100,
        load: jest.fn().mockResolvedValue(testConfig),
        watch: jest.fn(),
        stopWatching: jest.fn()
      };

      manager.addSource(mockSource);

      return manager.initialize().then(() => {
        manager.get('app.name');
        manager.get('app.name');
        manager.get('app.name');

        const stats = manager.getStats();
        expect(stats.totalGets).toBe(3);
        expect(stats.cacheHits).toBe(2);
        expect(stats.cacheMisses).toBe(1);
        expect(stats.cacheHitRate).toBeCloseTo(0.666);
        expect(stats.cacheSize).toBeGreaterThan(0);
      });
    });

    it('resetStats 应重置统计数据', () => {
      return manager.initialize().then(() => {
        manager.set('test.key', 'value');
        manager.get('test.key');

        manager.resetStats();

        const stats = manager.getStats();
        expect(stats.totalGets).toBe(0);
        expect(stats.cacheHits).toBe(0);
        expect(stats.cacheMisses).toBe(0);
      });
    });
  });

  describe('invalidateCache', () => {
    it('invalidateCache 应清除所有缓存', () => {
      return manager.initialize().then(() => {
        manager.set('test.key', 'value');
        manager.get('test.key');
        manager.get('test.key');

        expect(manager.getStats().cacheHits).toBe(1);

        manager.invalidateCache();

        const stats = manager.getStats();
        expect(stats.cacheSize).toBe(0);
      });
    });
  });

  describe('向后兼容性', () => {
    it('get 方法原有行为应保持不变', () => {
      const testConfig = {
        database: {
          host: 'localhost',
          port: 5432
        }
      };

      const mockSource: IConfigSource = {
        name: 'test',
        priority: 100,
        load: jest.fn().mockResolvedValue(testConfig),
        watch: jest.fn(),
        stopWatching: jest.fn()
      };

      manager.addSource(mockSource);

      return manager.initialize().then(() => {
        expect(manager.get('database.host')).toBe('localhost');
        expect(manager.get('database.port')).toBe(5432);
        expect(manager.get('nonexistent', 'default')).toBe('default');
      });
    });

    it('getAll 方法应返回完整配置副本', () => {
      const testConfig = {
        app: { name: 'Test' },
        database: { host: 'localhost' }
      };

      const mockSource: IConfigSource = {
        name: 'test',
        priority: 100,
        load: jest.fn().mockResolvedValue(testConfig),
        watch: jest.fn(),
        stopWatching: jest.fn()
      };

      manager.addSource(mockSource);

      return manager.initialize().then(() => {
        const allConfig = manager.getAll();
        expect(allConfig).toEqual(testConfig);

        allConfig.app.name = 'Modified';
        const original = manager.get('app.name');
        expect(original).toBe('Test');
      });
    });

    it('set 方法原有行为应保持不变', () => {
      return manager.initialize().then(() => {
        manager.set('new.key', 'value');
        expect(manager.get('new.key')).toBe('value');

        manager.set('nested.object', { a: 1, b: 2 });
        expect(manager.get('nested.object.a')).toBe(1);
      });
    });
  });

  describe('深嵌套配置访问', () => {
    it('应能正确访问深层嵌套配置', () => {
      const testConfig = {
        level1: {
          level2: {
            level3: {
              level4: {
                level5: {
                  value: 'deep_value'
                }
              }
            }
          }
        }
      };

      const mockSource: IConfigSource = {
        name: 'test',
        priority: 100,
        load: jest.fn().mockResolvedValue(testConfig),
        watch: jest.fn(),
        stopWatching: jest.fn()
      };

      manager.addSource(mockSource);

      return manager.initialize().then(() => {
        const value = manager.get('level1.level2.level3.level4.level5.value');
        expect(value).toBe('deep_value');

        const stats = manager.getStats();
        expect(stats.cacheMisses).toBe(1);

        const value2 = manager.get('level1.level2.level3.level4.level5.value');
        expect(value2).toBe('deep_value');
        expect(manager.getStats().cacheHits).toBe(1);
      });
    });
  });
});
