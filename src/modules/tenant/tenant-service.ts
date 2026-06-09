import { Tenant, TenantStatus, PlanTier, Prisma } from '@prisma/client';
import { connectionPool } from './connection-pool';
import { tenantResolver } from './tenant-resolver';
import { generateApiKey, generateId } from '@utils/crypto';
import { logger } from '@utils/logger';

export interface CreateTenantInput {
  code: string;
  name: string;
  plan: PlanTier;
  hostPattern?: string;
  customDomain?: string;
}

export interface UpdateTenantInput {
  name?: string;
  status?: TenantStatus;
  plan?: PlanTier;
  hostPattern?: string;
  customDomain?: string;
  maxApiCallsPerDay?: number;
  maxStorageGb?: number;
  maxContentModels?: number;
  maxUsers?: number;
  maxWebhooks?: number;
  enableVersioning?: boolean;
  enableWorkflow?: boolean;
  enableElasticsearch?: boolean;
  enableCDN?: boolean;
}

export class TenantService {
  private prisma = connectionPool.getPlatformPrisma();

  async createTenant(input: CreateTenantInput): Promise<Tenant> {
    const existing = await this.prisma.tenant.findFirst({
      where: { code: input.code },
    });

    if (existing) {
      throw new Error(`Tenant with code ${input.code} already exists`);
    }

    const tenantId = generateId('tenant');
    const apiKey = generateApiKey();
    const dbSchema = `tenant_${input.code.toLowerCase().replace(/[^a-z0-9_]/g, '_')}`;
    const elasticIndexPrefix = `cms_${input.code.toLowerCase()}`;

    const tenant = await this.prisma.tenant.create({
      data: {
        id: tenantId,
        code: input.code,
        name: input.name,
        status: TenantStatus.ACTIVE,
        plan: input.plan.toUpperCase() as Prisma.EnumPlanTierFilter,
        hostPattern: input.hostPattern,
        customDomain: input.customDomain,
        apiKey,
        dbSchema,
        elasticIndexPrefix,
      },
    });

    await this.createTenantSchema(dbSchema);
    await tenantResolver.invalidateTenantCache(tenantId);

    logger.info(`Created tenant: ${input.code} (${tenantId})`);

    return tenant;
  }

  async getTenant(id: string): Promise<Tenant | null> {
    return this.prisma.tenant.findUnique({
      where: { id, deletedAt: null },
    });
  }

  async listTenants(
    page = 1,
    pageSize = 50,
    status?: TenantStatus
  ): Promise<{ tenants: Tenant[]; total: number }> {
    const where: Prisma.TenantWhereInput = { deletedAt: null };
    if (status) {
      where.status = status;
    }

    const [tenants, total] = await Promise.all([
      this.prisma.tenant.findMany({
        where,
        skip: (page - 1) * pageSize,
        take: pageSize,
        orderBy: { createdAt: 'desc' },
      }),
      this.prisma.tenant.count({ where }),
    ]);

    return { tenants, total };
  }

  async updateTenant(id: string, input: UpdateTenantInput): Promise<Tenant> {
    const updateData: Prisma.TenantUpdateInput = {};

    if (input.name !== undefined) updateData.name = input.name;
    if (input.status !== undefined) updateData.status = input.status;
    if (input.plan !== undefined) updateData.plan = input.plan.toUpperCase() as Prisma.EnumPlanTierFilter;
    if (input.hostPattern !== undefined) updateData.hostPattern = input.hostPattern;
    if (input.customDomain !== undefined) updateData.customDomain = input.customDomain;
    if (input.maxApiCallsPerDay !== undefined) updateData.maxApiCallsPerDay = input.maxApiCallsPerDay;
    if (input.maxStorageGb !== undefined) updateData.maxStorageGb = input.maxStorageGb;
    if (input.maxContentModels !== undefined) updateData.maxContentModels = input.maxContentModels;
    if (input.maxUsers !== undefined) updateData.maxUsers = input.maxUsers;
    if (input.maxWebhooks !== undefined) updateData.maxWebhooks = input.maxWebhooks;
    if (input.enableVersioning !== undefined) updateData.enableVersioning = input.enableVersioning;
    if (input.enableWorkflow !== undefined) updateData.enableWorkflow = input.enableWorkflow;
    if (input.enableElasticsearch !== undefined) updateData.enableElasticsearch = input.enableElasticsearch;
    if (input.enableCDN !== undefined) updateData.enableCDN = input.enableCDN;

    const tenant = await this.prisma.tenant.update({
      where: { id },
      data: updateData,
    });

    await tenantResolver.invalidateTenantCache(id);
    logger.info(`Updated tenant: ${tenant.code} (${id})`);

    return tenant;
  }

  async suspendTenant(id: string): Promise<Tenant> {
    const tenant = await this.prisma.tenant.update({
      where: { id },
      data: { status: TenantStatus.SUSPENDED },
    });

    await tenantResolver.invalidateTenantCache(id);
    logger.info(`Suspended tenant: ${tenant.code} (${id})`);

    return tenant;
  }

  async activateTenant(id: string): Promise<Tenant> {
    const tenant = await this.prisma.tenant.update({
      where: { id },
      data: { status: TenantStatus.ACTIVE },
    });

    await tenantResolver.invalidateTenantCache(id);
    logger.info(`Activated tenant: ${tenant.code} (${id})`);

    return tenant;
  }

  async deleteTenant(id: string): Promise<void> {
    const tenant = await this.prisma.tenant.findUnique({ where: { id } });
    if (!tenant) return;

    await this.prisma.tenant.update({
      where: { id },
      data: { deletedAt: new Date() },
    });

    await tenantResolver.invalidateTenantCache(id);
    logger.info(`Soft deleted tenant: ${tenant.code} (${id})`);
  }

  async regenerateApiKey(id: string): Promise<string> {
    const apiKey = generateApiKey();
    await this.prisma.tenant.update({
      where: { id },
      data: { apiKey },
    });
    await tenantResolver.invalidateTenantCache(id);
    logger.info(`Regenerated API key for tenant: ${id}`);
    return apiKey;
  }

  private async createTenantSchema(schemaName: string): Promise<void> {
    const pool = connectionPool.getPlatformPool();
    const client = await pool.connect();

    try {
      await client.query(`CREATE SCHEMA IF NOT EXISTS "${schemaName}"`);
      await client.query(`
        CREATE TABLE IF NOT EXISTS "${schemaName}".schema_migrations (
          version varchar(255) PRIMARY KEY,
          applied_at timestamp with time zone DEFAULT NOW()
        )
      `);
      logger.info(`Created tenant schema: ${schemaName}`);
    } finally {
      client.release();
    }
  }

  async checkTenantLimits(
    tenantId: string
  ): Promise<{ withinLimits: boolean; violations: string[] }> {
    const tenant = await this.getTenant(tenantId);
    if (!tenant) {
      return { withinLimits: false, violations: ['Tenant not found'] };
    }

    const violations: string[] = [];

    if (tenant.apiCallsToday > tenant.maxApiCallsPerDay) {
      violations.push('API call limit exceeded');
    }

    const storageLimitBytes = tenant.maxStorageGb * 1024 * 1024 * 1024;
    if (tenant.storageUsedBytes > storageLimitBytes) {
      violations.push('Storage limit exceeded');
    }

    const [contentModelCount] = await Promise.all([
      this.prisma.contentModel.count({
        where: { tenantId, deletedAt: null },
      }),
    ]);

    if (contentModelCount > tenant.maxContentModels) {
      violations.push('Content model limit exceeded');
    }

    return {
      withinLimits: violations.length === 0,
      violations,
    };
  }
}

export const tenantService = new TenantService();
