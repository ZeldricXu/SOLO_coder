require('dotenv').config();

module.exports = {
  port: process.env.PORT || 3001,
  nodeEnv: process.env.NODE_ENV || 'development',
  mongodb: {
    uri: process.env.MONGODB_URI || 'mongodb://localhost:27017/dochub',
    options: {
      useNewUrlParser: true,
      useUnifiedTopology: true,
    }
  },
  redis: {
    host: process.env.REDIS_HOST || 'localhost',
    port: parseInt(process.env.REDIS_PORT) || 6379,
    password: process.env.REDIS_PASSWORD || null,
    db: parseInt(process.env.REDIS_DB) || 0,
    keyPrefix: process.env.REDIS_KEY_PREFIX || 'dochub:',
    options: {
      retryStrategy: (times) => {
        return Math.min(times * 50, 2000);
      }
    }
  },
  elasticsearch: {
    url: process.env.ELASTICSEARCH_URL || 'http://localhost:9200',
    index: 'dochub_docs'
  },
  jwt: {
    secret: process.env.JWT_SECRET || 'dochub_jwt_secret_key_2026',
    expiresIn: '24h'
  },
  log: {
    level: process.env.LOG_LEVEL || 'info'
  },
  compression: {
    enabled: process.env.COMPRESSION_ENABLED !== 'false',
    threshold: parseInt(process.env.COMPRESSION_THRESHOLD) || 1024,
    algorithm: process.env.COMPRESSION_ALGORITHM || 'gzip',
    level: parseInt(process.env.COMPRESSION_LEVEL) || 6
  },
  version: {
    useDelta: process.env.VERSION_USE_DELTA !== 'false',
    fullVersionInterval: parseInt(process.env.FULL_VERSION_INTERVAL) || 10,
    maxDeltaChain: parseInt(process.env.MAX_DELTA_CHAIN) || 20
  },
  cache: {
    enabled: process.env.CACHE_ENABLED !== 'false',
    useRedisCache: process.env.CACHE_USE_REDIS === 'true',
    defaultTTL: parseInt(process.env.CACHE_DEFAULT_TTL) || 60000,
    searchTTL: parseInt(process.env.CACHE_SEARCH_TTL) || 30000,
    maxSize: parseInt(process.env.CACHE_MAX_SIZE) || 1000,
    checkPeriod: parseInt(process.env.CACHE_CHECK_PERIOD) || 60000
  },
  asyncSave: {
    enabled: process.env.ASYNC_SAVE_ENABLED !== 'false',
    useRedisQueue: process.env.ASYNC_SAVE_USE_REDIS === 'true',
    maxWorkers: parseInt(process.env.ASYNC_SAVE_MAX_WORKERS) || 4,
    queueSize: parseInt(process.env.ASYNC_SAVE_QUEUE_SIZE) || 1000,
    retryDelay: parseInt(process.env.ASYNC_SAVE_RETRY_DELAY) || 1000,
    maxRetries: parseInt(process.env.ASYNC_SAVE_MAX_RETRIES) || 3,
    taskLockTTL: parseInt(process.env.ASYNC_SAVE_LOCK_TTL) || 30000
  },
  compressionStrategies: {
    code: {
      enabled: true,
      diffLevel: 'line',
      compressionAlgorithm: 'gzip',
      compressionLevel: 6
    },
    richText: {
      enabled: true,
      diffLevel: 'paragraph',
      compressionAlgorithm: 'gzip',
      compressionLevel: 6
    },
    plainText: {
      enabled: true,
      diffLevel: 'word',
      compressionAlgorithm: 'gzip',
      compressionLevel: 6
    }
  }
};
