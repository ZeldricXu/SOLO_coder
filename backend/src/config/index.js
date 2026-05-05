require('dotenv').config();

module.exports = {
  port: process.env.PORT || 3001,
  database: {
    host: process.env.DB_HOST || 'localhost',
    port: parseInt(process.env.DB_PORT) || 3306,
    user: process.env.DB_USER || 'root',
    password: process.env.DB_PASSWORD || '',
    database: process.env.DB_NAME || 'eventhub',
    waitForConnections: true,
    connectionLimit: 10,
    queueLimit: 0
  },
  jwt: {
    secret: process.env.JWT_SECRET || 'eventhub-jwt-secret-key-2026',
    expiresIn: process.env.JWT_EXPIRES_IN || '24h'
  },
  email: {
    host: process.env.EMAIL_HOST || 'smtp.example.com',
    port: parseInt(process.env.EMAIL_PORT) || 587,
    user: process.env.EMAIL_USER || '',
    password: process.env.EMAIL_PASSWORD || '',
    from: process.env.EMAIL_FROM || 'noreply@example.com'
  },
  sms: {
    provider: process.env.SMS_PROVIDER || 'aliyun',
    accessKeyId: process.env.SMS_ACCESS_KEY_ID || '',
    accessKeySecret: process.env.SMS_ACCESS_KEY_SECRET || '',
    signName: process.env.SMS_SIGN_NAME || 'EventHub',
    templateCode: process.env.SMS_TEMPLATE_CODE || 'SMS_123456'
  },
  queue: {
    enabled: process.env.QUEUE_ENABLED === 'true',
    useRedis: process.env.QUEUE_USE_REDIS === 'true',
    pollInterval: parseInt(process.env.QUEUE_POLL_INTERVAL) || 5000,
    batchSize: parseInt(process.env.QUEUE_BATCH_SIZE) || 10,
    maxRetries: parseInt(process.env.QUEUE_MAX_RETRIES) || 3,
    workerEnabled: process.env.QUEUE_WORKER_ENABLED === 'true',
    queueNames: {
      notifications: 'queue:notifications',
      emails: 'queue:emails',
      sms: 'queue:sms'
    },
    deadLetterQueue: 'queue:dead_letter'
  },
  redis: {
    enabled: process.env.REDIS_ENABLED === 'true',
    host: process.env.REDIS_HOST || 'localhost',
    port: parseInt(process.env.REDIS_PORT) || 6379,
    password: process.env.REDIS_PASSWORD || undefined,
    db: parseInt(process.env.REDIS_DB) || 0,
    prefix: process.env.REDIS_PREFIX || 'eventhub:'
  }
};
