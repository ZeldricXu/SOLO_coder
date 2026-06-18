import { Injectable, NotFoundException } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { QueryAuditDto } from './dto/query-audit.dto';

@Injectable()
export class AuditService {
  constructor(private readonly prisma: PrismaService) {}

  async findAll(dto: QueryAuditDto) {
    const { tenantId, action, resource, startDate, endDate, page = 1, limit = 20 } = dto;

    const where: Record<string, any> = {};
    if (tenantId) where.tenantId = tenantId;
    if (action) where.action = action;
    if (resource) where.resource = resource;
    if (startDate || endDate) {
      where.createdAt = {};
      if (startDate) where.createdAt.gte = new Date(startDate);
      if (endDate) where.createdAt.lte = new Date(endDate);
    }

    const [items, total] = await Promise.all([
      this.prisma.auditLog.findMany({
        where,
        orderBy: { createdAt: 'desc' },
        skip: (page - 1) * limit,
        take: limit,
      }),
      this.prisma.auditLog.count({ where }),
    ]);

    return {
      items,
      total,
      page,
      limit,
      totalPages: Math.ceil(total / limit),
    };
  }

  async findOne(id: string) {
    const log = await this.prisma.auditLog.findUnique({ where: { id } });
    if (!log) {
      throw new NotFoundException(`Audit log ${id} not found`);
    }
    return log;
  }

  async log(data: {
    userId: string;
    userEmail: string;
    action: string;
    resource: string;
    resourceId?: string;
    details?: any;
    tenantId?: string;
    ip: string;
  }) {
    return this.prisma.auditLog.create({ data });
  }
}
