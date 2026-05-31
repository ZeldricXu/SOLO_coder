import { ITenantRepository } from '../../../../domain/tenant/repositories/ITenantRepository';
import { Tenant } from '../../../../domain/tenant/entities/Tenant';
import { UniqueEntityID } from '../../../../domain/shared/value-objects/UniqueEntityID';
import { IPaginationParams, IPaginatedResult } from '../../../../domain/shared/repositories/IRepository';
import { getPrismaClient } from '../client/PrismaClient';
import { TenantMapper } from '../mappers/TenantMapper';
import { injectable } from 'tsyringe';

@injectable()
export class TenantRepository implements ITenantRepository {
  private readonly prisma: any;

  constructor() {
    this.prisma = getPrismaClient();
  }

  async findById(id: UniqueEntityID, tenantId: string): Promise<Tenant | null> {
    const prismaTenant = await this.prisma.tenant.findUnique({
      where: { id: id.value }
    });

    if (!prismaTenant || prismaTenant.id !== tenantId) {
      return null;
    }

    return TenantMapper.toDomain(prismaTenant);
  }

  async findAll(tenantId: string, options?: { skip?: number; take?: number }): Promise<Tenant[]> {
    const prismaTenants = await this.prisma.tenant.findMany({
      where: { tenantId },
      skip: options?.skip,
      take: options?.take
    });

    return prismaTenants.map((t: any) => TenantMapper.toDomain(t));
  }

  async count(tenantId: string): Promise<number> {
    return this.prisma.tenant.count({
      where: { id: tenantId }
    });
  }

  async save(tenant: Tenant, tenantId: string): Promise<Tenant> {
    const tenantData = TenantMapper.toPersistence(tenant);

    const existing = await this.prisma.tenant.findUnique({
      where: { id: tenant.id.value }
    });

    let savedTenant;
    if (existing) {
      savedTenant = await this.prisma.tenant.update({
        where: { id: tenant.id.value },
        data: tenantData
      });
    } else {
      savedTenant = await this.prisma.tenant.create({
        data: tenantData
      });
    }

    return TenantMapper.toDomain(savedTenant);
  }

  async delete(id: UniqueEntityID, tenantId: string): Promise<void> {
    await this.prisma.tenant.delete({
      where: { id: id.value }
    });
  }

  async findByEmail(email: string): Promise<Tenant | null> {
    const prismaTenant = await this.prisma.tenant.findUnique({
      where: { email }
    });

    return prismaTenant ? TenantMapper.toDomain(prismaTenant) : null;
  }

  async findByName(name: string): Promise<Tenant | null> {
    const prismaTenant = await this.prisma.tenant.findFirst({
      where: { name }
    });

    return prismaTenant ? TenantMapper.toDomain(prismaTenant) : null;
  }

  async findAllPaginated(params: IPaginationParams & { status?: string }): Promise<IPaginatedResult<Tenant>> {
    const where: Record<string, unknown> = {};
    if (params.status) where.status = params.status;

    const [total, prismaTenants] = await Promise.all([
      this.prisma.tenant.count({ where }),
      this.prisma.tenant.findMany({
        where,
        skip: (params.page - 1) * params.pageSize,
        take: params.pageSize,
        orderBy: { createdAt: 'desc' }
      })
    ]);

    return {
      items: prismaTenants.map((t: any) => TenantMapper.toDomain(t)),
      total,
      page: params.page,
      pageSize: params.pageSize,
      totalPages: Math.ceil(total / params.pageSize)
    };
  }

  async existsByEmail(email: string): Promise<boolean> {
    const count = await this.prisma.tenant.count({
      where: { email }
    });
    return count > 0;
  }
}
