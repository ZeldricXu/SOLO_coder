import { FastifyRequest } from 'fastify';
import { Tenant, TenantStatus } from '@prisma/client';
import { connectionPool } from './connection-pool';
import { redisManager } from './redis-manager';
import { TenantContext, TenantLimits, PlanTier } from '@types/index';
import { logger } from '@utils/logger';

const TENANT_CACHE_TTL = 300;
const API_KEY_HEADER = 'x-api-key';
const TENANT_CODE_HEADER = 'x-tenant-code';

const PLAN_LIMITS: Record<PlanTier, TenantLimits> = {
  free: {
    maxApiCallsPerDay: 10000,
    maxStorageGb: 1,
    maxContentModels: 5,
    maxUsers: 10,
    maxWebhooks: 3,
    enableVersioning: false,
    enableWorkflow: false,
    enableElasticsearch: false,
    enableCDN: false,
  },
  starter: {
    maxApiCallsPerDay: 50000,
    maxStorageGb: 10,
    maxContentModels: 20,
    maxUsers: 50,
    maxWebhooks: 10,
    enableVersioning: true,
    enableWorkflow: false,
    enableElasticsearch: true,
    enableCDN: false,
  },
  professional: {
    maxApiCallsPerDay: 500000,
    maxStorageGb: 100,
    maxContentModels: 100,
    maxUsers: 200,
    maxWebhooks: 50,
    enableVersioning: true,
    enableWorkflow: true,
    enableElasticsearch: true,
    enableCDN: true,
  },
  enterprise: {
    maxApiCallsPerDay: 10000000,
    maxStorageGb: 1000,
    maxContentModels: 1000,
    maxUsers: 10000,
    maxWebhooks: 1000,
    enableVersioning: true,
    enableWorkflow: true,
    enableElasticsearch: true,
    enableCDN: true,
  },
};

export class TenantResolver {
  private prisma = connectionPool.getPlatformPrisma();
  private redis = redisManager.getDefaultClient();

  async resolveFromRequest(request: FastifyRequest): Promise<TenantContext | null> {
    let tenant: Tenant | null = null;

    const apiKey = request.headers[API_KEY_HEADER] as string | undefined;
    if (apiKey) {
      tenant = await this.findByApiKey(apiKey);
    }

    if (!tenant) {
      const tenantCode = request.headers[TENANT_CODE_HEADER] as string | undefined;
      if (tenantCode) {
        tenant = await this.findByCode(tenantCode);
      }
    }

    if (!tenant) {
      const host = request.headers.host;
      if (host) {
        tenant = await this.findByHost(host);
      }
    }

    if (!tenant) {
      return null;
    }

    if (tenant.status !== TenantStatus.ACTIVE) {
      logger.warn(`Tenant ${tenant.code} is not active: ${tenant.status}`);
      return null;
    }

    return this.buildTenantContext(tenant);
  }

  async findByApiKey(apiKey: string): Promise<Tenant | null> {
    const cacheKey = `tenant:apikey:${apiKey}`;
    const cached = await this.redis.get(cacheKey);

    if (cached) {
      return JSON.parse(cached);
    }

    const tenant = await this.prisma.tenant.findFirst({
      where: { apiKey, deletedAt: null },
    });

    if (tenant) {
      await this.redis.setex(cacheKey, TENANT_CACHE_TTL, JSON.stringify(tenant));
    }

    return tenant;
  }

  async findByCode(code: string): Promise<Tenant | null> {
    const cacheKey = `tenant:code:${code}`;
    const cached = await this.redis.get(cacheKey);

    if (cached) {
      return JSON.parse(cached);
    }

    const tenant = await this.prisma.tenant.findFirst({
      where: { code, deletedAt: null },
    });

    if (tenant) {
      await this.redis.setex(cacheKey, TENANT_CACHE_TTL, JSON.stringify(tenant));
    }

    return tenant;
  }

  async findByHost(host: string): Promise<Tenant | null> {
    const cacheKey = `tenant:host:${host}`;
    const cached = await this.redis.get(cacheKey);

    if (cached) {
      return JSON.parse(cached);
    }

    const tenant = await this.prisma.tenant.findFirst({
      where: {
        OR: [
          { hostPattern: host },
          { customDomain: host },
        ],
        deletedAt: null,
      },
    });

    if (tenant) {
      await this.redis.setex(cacheKey, TENANT_CACHE_TTL, JSON.stringify(tenant));
    }

    return tenant;
  }

  private buildTenantContext(tenant: Tenant): TenantContext {
    const planTier = tenant.plan.toLowerCase() as PlanTier;
    const baseLimits = PLAN_LIMITS[planTier] || PLAN_LIMITS.free;

    const limits: TenantLimits = {
      maxApiCallsPerDay: tenant.maxApiCallsPerDay || baseLimits.maxApiCallsPerDay,
      maxStorageGb: tenant.maxStorageGb || baseLimits.maxStorageGb,
      maxContentModels: tenant.maxContentModels || baseLimits.maxContentModels,
      maxUsers: tenant.maxUsers || baseLimits.maxUsers,
      maxWebhooks: tenant.maxWebhooks || baseLimits.maxWebhooks,
      enableVersioning: tenant.enableVersioning || baseLimits.enableVersioning,
      enableWorkflow: tenant.enableWorkflow || baseLimits.enableWorkflow,
      enableElasticsearch: tenant.enableElasticsearch || baseLimits.enableElasticsearch,
      enableCDN: tenant.enableCDN || baseLimits.enableCDN,
    };

    return {
      tenantId: tenant.id,
      tenantCode: tenant.code,
      plan: planTier,
      dbSchema: tenant.dbSchema,
      elasticIndexPrefix: tenant.elasticIndexPrefix,
      limits,
    };
  }

  async invalidateTenantCache(tenantId: string): Promise<void> {
    const tenant = await this.prisma.tenant.findUnique({
      where: { id: tenantId },
    });

    if (tenant) {
      const keys = [
        `tenant:apikey:${tenant.apiKey}`,
        `tenant:code:${tenant.code}`,
      ];

      if (tenant.hostPattern) {
        keys.push(`tenant:host:${tenant.hostPattern}`);
      }
      if (tenant.customDomain) {
        keys.push(`tenant:host:${tenant.customDomain}`);
      }

      await this.redis.del(...keys);
      logger.info(`Invalidated cache for tenant: ${tenant.code}`);
    }
  }
}

export const tenantResolver = new TenantResolver();
