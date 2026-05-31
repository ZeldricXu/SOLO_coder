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
    maxTicketsPerDay: 100,
    maxAgents: 50,
    maxSkillCategories: 20,
    enableSLAMonitoring: true,
    enableAutoEscalation: true,
    defaultLoadBalancingStrategy: 'round_robin',
    skillMatchThreshold: 0.7
  },
  featureFlags: {
    enableBillingModule: true,
    enableApprovalModule: true,
    enableDocumentComparison: true,
    enableSkillGraph: true,
    enableProcessDesigner: true,
    enableSLAMonitoring: true
  }
};

export const getConfig = (): AppConfig => config;

export const getTenantConfig = (tenantId: string): Record<string, unknown> => {
  return {
    ...config.defaultTenantConfig,
    tenantId
  };
};

export const isFeatureEnabled = (feature: keyof AppConfig['featureFlags']): boolean => {
  return config.featureFlags[feature] ?? false;
};
