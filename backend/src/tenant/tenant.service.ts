import {
  Injectable,
  NotFoundException,
  ForbiddenException,
} from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { CreateTenantDto } from './dto/create-tenant.dto';
import { UpdateTenantDto } from './dto/update-tenant.dto';
import { CreateBusinessLineDto } from './dto/create-business-line.dto';
import { UpdateBusinessLineDto } from './dto/update-business-line.dto';

@Injectable()
export class TenantService {
  constructor(private readonly prisma: PrismaService) {}

  async create(dto: CreateTenantDto) {
    return this.prisma.tenant.create({
      data: {
        name: dto.name,
        slug: dto.slug,
      },
    });
  }

  async findAll(user: any) {
    if (user.role === 'SUPER_ADMIN') {
      return this.prisma.tenant.findMany({
        orderBy: { createdAt: 'desc' },
      });
    }
    return this.prisma.tenant.findMany({
      where: { id: user.tenantId },
      orderBy: { createdAt: 'desc' },
    });
  }

  async findOne(id: string, user: any) {
    if (user.role !== 'SUPER_ADMIN' && user.tenantId !== id) {
      throw new ForbiddenException(
        'Access denied: resource belongs to another tenant',
      );
    }

    const tenant = await this.prisma.tenant.findUnique({
      where: { id },
      include: { businessLines: true },
    });

    if (!tenant) {
      throw new NotFoundException(`Tenant ${id} not found`);
    }

    return tenant;
  }

  async update(id: string, dto: UpdateTenantDto, user: any) {
    if (user.role !== 'SUPER_ADMIN' && user.tenantId !== id) {
      throw new ForbiddenException(
        'Access denied: resource belongs to another tenant',
      );
    }

    await this.findOne(id, user);
    return this.prisma.tenant.update({
      where: { id },
      data: dto as any,
    });
  }

  async remove(id: string) {
    const tenant = await this.prisma.tenant.findUnique({ where: { id } });
    if (!tenant) {
      throw new NotFoundException(`Tenant ${id} not found`);
    }
    return this.prisma.tenant.delete({ where: { id } });
  }

  async addBusinessLine(
    tenantId: string,
    dto: CreateBusinessLineDto,
    user: any,
  ) {
    await this.findOne(tenantId, user);
    return this.prisma.businessLine.create({
      data: {
        name: dto.name,
        code: dto.code,
        tenantId,
      },
    });
  }

  async updateBusinessLine(
    tenantId: string,
    blId: string,
    dto: UpdateBusinessLineDto,
    user: any,
  ) {
    await this.findOne(tenantId, user);
    const bl = await this.prisma.businessLine.findFirst({
      where: { id: blId, tenantId },
    });
    if (!bl) {
      throw new NotFoundException(
        `Business line ${blId} not found in tenant ${tenantId}`,
      );
    }
    return this.prisma.businessLine.update({
      where: { id: blId },
      data: dto as any,
    });
  }

  async removeBusinessLine(
    tenantId: string,
    blId: string,
    user: any,
  ) {
    await this.findOne(tenantId, user);
    const bl = await this.prisma.businessLine.findFirst({
      where: { id: blId, tenantId },
    });
    if (!bl) {
      throw new NotFoundException(
        `Business line ${blId} not found in tenant ${tenantId}`,
      );
    }
    return this.prisma.businessLine.delete({ where: { id: blId } });
  }
}
