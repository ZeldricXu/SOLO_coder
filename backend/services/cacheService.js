const config = require('../config/config');
const logger = require('../utils/logger');

class CacheService {
  constructor(options = {}) {
    this.cache = new Map();
    this.ttlMap = new Map();
    this.hitCount = 0;
    this.missCount = 0;
    this.maxSize = options.maxSize || config.cache.maxSize;
    this.defaultTTL = options.defaultTTL || config.cache.defaultTTL;
    
    if (config.cache.checkPeriod > 0) {
      this.cleanupInterval = setInterval(() => {
        this.cleanup();
      }, config.cache.checkPeriod);
    }
    
    logger.info(`缓存服务已初始化: maxSize=${this.maxSize}, defaultTTL=${this.defaultTTL}ms`);
  }

  generateKey(prefix, params = {}) {
    const sortedKeys = Object.keys(params).sort();
    const keyParts = [prefix];
    
    for (const key of sortedKeys) {
      const value = params[key];
      if (value !== undefined && value !== null) {
        keyParts.push(`${key}=${JSON.stringify(value)}`);
      }
    }
    
    return keyParts.join(':');
  }

  get(key) {
    if (!config.cache.enabled) {
      this.missCount++;
      return null;
    }

    const now = Date.now();
    const ttl = this.ttlMap.get(key);
    
    if (ttl && now > ttl) {
      this.delete(key);
      this.missCount++;
      return null;
    }

    if (this.cache.has(key)) {
      this.hitCount++;
      
      const value = this.cache.get(key);
      if (value && value._isExpirable) {
        return value.data;
      }
      return value;
    }

    this.missCount++;
    return null;
  }

  set(key, value, ttl = this.defaultTTL) {
    if (!config.cache.enabled) {
      return false;
    }

    try {
      if (this.cache.size >= this.maxSize) {
        this.evictLRU();
      }

      const now = Date.now();
      
      if (ttl > 0) {
        this.ttlMap.set(key, now + ttl);
        this.cache.set(key, {
          data: value,
          _isExpirable: true,
          _createdAt: now
        });
      } else {
        this.cache.set(key, value);
      }

      logger.debug(`缓存已设置: key=${key}, size=${JSON.stringify(value).length} bytes`);
      return true;
    } catch (error) {
      logger.error(`设置缓存失败: ${error.message}`, { error });
      return false;
    }
  }

  delete(key) {
    this.cache.delete(key);
    this.ttlMap.delete(key);
    logger.debug(`缓存已删除: key=${key}`);
  }

  deleteByPattern(pattern) {
    const regex = new RegExp(pattern);
    let deletedCount = 0;
    
    for (const key of this.cache.keys()) {
      if (regex.test(key)) {
        this.delete(key);
        deletedCount++;
      }
    }
    
    logger.debug(`按模式删除缓存: pattern=${pattern}, deleted=${deletedCount}`);
    return deletedCount;
  }

  deleteByPrefix(prefix) {
    return this.deleteByPattern(`^${prefix}`);
  }

  clear() {
    this.cache.clear();
    this.ttlMap.clear();
    this.hitCount = 0;
    this.missCount = 0;
    logger.info('缓存已清空');
  }

  cleanup() {
    const now = Date.now();
    let cleanedCount = 0;
    
    for (const [key, ttl] of this.ttlMap.entries()) {
      if (now > ttl) {
        this.delete(key);
        cleanedCount++;
      }
    }
    
    if (cleanedCount > 0) {
      logger.debug(`缓存清理完成: 清理了 ${cleanedCount} 个过期条目`);
    }
  }

  evictLRU() {
    let oldestKey = null;
    let oldestTime = Infinity;
    
    for (const [key, value] of this.cache.entries()) {
      let createdAt;
      if (value && value._isExpirable) {
        createdAt = value._createdAt;
      } else {
        createdAt = 0;
      }
      
      if (createdAt < oldestTime) {
        oldestTime = createdAt;
        oldestKey = key;
      }
    }
    
    if (oldestKey) {
      this.delete(oldestKey);
      logger.debug(`缓存LRU淘汰: key=${oldestKey}`);
    }
  }

  async getOrSet(key, fetcher, ttl = this.defaultTTL) {
    const cachedValue = this.get(key);
    if (cachedValue !== null) {
      return {
        fromCache: true,
        data: cachedValue
      };
    }

    try {
      const data = await fetcher();
      this.set(key, data, ttl);
      return {
        fromCache: false,
        data: data
      };
    } catch (error) {
      logger.error(`获取或设置缓存失败: ${error.message}`, { error, key });
      throw error;
    }
  }

  getStats() {
    const totalRequests = this.hitCount + this.missCount;
    const hitRate = totalRequests > 0 ? (this.hitCount / totalRequests) * 100 : 0;
    
    return {
      size: this.cache.size,
      maxSize: this.maxSize,
      hitCount: this.hitCount,
      missCount: this.missCount,
      hitRate: hitRate.toFixed(2) + '%',
      ttlEntries: this.ttlMap.size
    };
  }

  has(key) {
    if (!config.cache.enabled) {
      return false;
    }
    
    const value = this.get(key);
    return value !== null;
  }

  keys() {
    return Array.from(this.cache.keys());
  }

  shutdown() {
    if (this.cleanupInterval) {
      clearInterval(this.cleanupInterval);
      this.cleanupInterval = null;
    }
    logger.info('缓存服务已关闭');
  }
}

const cacheService = new CacheService();

module.exports = cacheService;
