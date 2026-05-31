import { config as loadEnv } from 'dotenv';

loadEnv();

export interface AppConfig {
  port: number;
  env: 'development' | 'staging' | 'production';
  nodeEnv: string;
  logLevel: string;
  databaseUrl: string;
  redisUrl: string;
  jwtSecret: string;
  defaultTenantConfig: Record<string, unknown>;
  featureFlags: Record<string, boolean>;
}

const config: AppConfig = {
  port: parseInt(process.env.PORT || '3000', 10),
  env: (process.env.NODE_ENV as 'development' | 'staging' | 'production') || 'development',
  nodeEnv: process.env.NODE_ENV || 'development',
  logLevel: process.env.LOG_LEVEL || 'info',
  databaseUrl: process.env.DATABASE_URL || 'postgresql://postgres:password@localhost:5432/ticket_routing',
  redisUrl: process.env.REDIS_URL || 'redis://localhost:6379',
  jwtSecret: process.env.JWT_SECRET || 'your-secret-key-change-in-production',
  defaultTenantConfig: {
    maxTicketsPerMonth: 1000,
    maxAgents: 10,
    maxProcessDefinitions: 20,
    maxStorageGB: 10,
    maxApiCallsPerMinute: 100
  },
  featureFlags: {
    enableDocumentCompare: true,
    enableProcessDesigner: true,
    enableApprovalEngine: true,
    enableSLAMonitoring: true
  }
};

export const getConfig = (): Readonly<AppConfig> => Object.freeze({ ...config });

export const getTenantConfig = (tenantId: string): Readonly<Record<string, unknown>> => {
  return Object.freeze({
    ...config.defaultTenantConfig,
    tenantId
  });
};
