import { Tenant } from '../../../../domain/tenant/entities/Tenant';
import { TenantStatus, TenantStatusType } from '../../../../domain/tenant/value-objects/TenantStatus';
import { ResourceQuota } from '../../../../domain/tenant/value-objects/ResourceQuota';
import { UniqueEntityID } from '../../../../domain/shared/value-objects/UniqueEntityID';

export class TenantMapper {
  static toDomain(prismaTenant: any): Tenant {
    const quota = prismaTenant.quota
      ? ResourceQuota.create({
          maxTicketsPerMonth: prismaTenant.quota.maxTicketsPerMonth,
          maxAgents: prismaTenant.quota.maxAgents,
          maxProcessDefinitions: prismaTenant.quota.maxProcessDefinitions,
          maxStorageGB: prismaTenant.quota.maxStorageGB,
          maxApiCallsPerMinute: prismaTenant.quota.maxApiCallsPerMinute
        })
      : ResourceQuota.createDefault();

    return Tenant.create({
      id: prismaTenant.id,
      name: prismaTenant.name,
      email: prismaTenant.email,
      status: TenantStatus.create(prismaTenant.status as TenantStatusType),
      quota,
      config: (prismaTenant.config as Record<string, unknown>) || {},
      createdAt: prismaTenant.createdAt,
      updatedAt: prismaTenant.updatedAt
    } as any);
  }

  static toPersistence(tenant: Tenant): {
    id: string;
    name: string;
    email: string;
    status: string;
    config: Record<string, unknown>;
    createdAt: Date;
    updatedAt: Date;
  } {
    return {
      id: tenant.id.value,
      name: tenant.name,
      email: tenant.email,
      status: tenant.status.value,
      config: tenant.config,
      createdAt: tenant.createdAt,
      updatedAt: tenant.updatedAt
    };
  }

  static toPersistenceQuota(tenant: Tenant): {
    tenantId: string;
    maxTicketsPerMonth: number;
    maxAgents: number;
    maxProcessDefinitions: number;
    maxStorageGB: number;
    maxApiCallsPerMinute: number;
  } {
    const quota = tenant.quota;
    return {
      tenantId: tenant.id.value,
      maxTicketsPerMonth: quota.maxTicketsPerMonth,
      maxAgents: quota.maxAgents,
      maxProcessDefinitions: quota.maxProcessDefinitions,
      maxStorageGB: quota.maxStorageGB,
      maxApiCallsPerMinute: quota.maxApiCallsPerMinute
    };
  }
}
