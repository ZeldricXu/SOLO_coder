export const config = {
  server: {
    port: parseInt(process.env.PORT || '3000'),
    host: process.env.HOST || '0.0.0.0',
  },
  database: {
    url: process.env.DATABASE_URL || 'postgresql://postgres:postgres@localhost:5432/notification_gateway',
  },
  redis: {
    url: process.env.REDIS_URL || 'redis://localhost:6379',
  },
  jwt: {
    secret: process.env.JWT_SECRET || 'dev-secret-change-me',
  },
  email: {
    sendgridApiKey: process.env.SENDGRID_API_KEY || '',
    smtp: {
      host: process.env.SMTP_HOST || 'localhost',
      port: parseInt(process.env.SMTP_PORT || '587'),
      user: process.env.SMTP_USER || '',
      pass: process.env.SMTP_PASS || '',
    },
    dailyQuota: 50000,
  },
  sms: {
    aliyun: {
      accessKey: process.env.ALIYUN_SMS_ACCESS_KEY || '',
      secret: process.env.ALIYUN_SMS_SECRET || '',
    },
    twilio: {
      accountSid: process.env.TWILIO_ACCOUNT_SID || '',
      authToken: process.env.TWILIO_AUTH_TOKEN || '',
    },
  },
  push: {
    fcmServerKey: process.env.FCM_SERVER_KEY || '',
    apns: {
      keyId: process.env.APNS_KEY_ID || '',
      teamId: process.env.APNS_TEAM_ID || '',
      keyPath: process.env.APNS_KEY_PATH || '',
    },
  },
  queue: {
    defaultJobOptions: {
      attempts: 3,
      backoff: {
        type: 'exponential',
        delay: 1000,
      },
      removeOnComplete: true,
      removeOnFail: false,
    },
    dlq: {
      maxAge: 7 * 24 * 60 * 60 * 1000,
    },
  },
  rateLimit: {
    channel: {
      email: { max: 50000, window: 86400 },
      sms: { max: 10000, window: 86400 },
      push: { max: 100000, window: 86400 },
    },
    user: {
      sms: { max: 5, window: 60 },
      email: { max: 20, window: 3600 },
    },
    tenant: {
      concurrency: 100,
    },
  },
  ttl: {
    queuedMessage: 24 * 60 * 60 * 1000,
  },
};
