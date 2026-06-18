"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.TenantService = void 0;
const common_1 = require("@nestjs/common");
const prisma_service_1 = require("../prisma/prisma.service");
let TenantService = class TenantService {
    constructor(prisma) {
        this.prisma = prisma;
    }
    async create(dto) {
        return this.prisma.tenant.create({
            data: {
                name: dto.name,
                slug: dto.slug,
            },
        });
    }
    async findAll(user) {
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
    async findOne(id, user) {
        if (user.role !== 'SUPER_ADMIN' && user.tenantId !== id) {
            throw new common_1.ForbiddenException('Access denied: resource belongs to another tenant');
        }
        const tenant = await this.prisma.tenant.findUnique({
            where: { id },
            include: { businessLines: true },
        });
        if (!tenant) {
            throw new common_1.NotFoundException(`Tenant ${id} not found`);
        }
        return tenant;
    }
    async update(id, dto, user) {
        if (user.role !== 'SUPER_ADMIN' && user.tenantId !== id) {
            throw new common_1.ForbiddenException('Access denied: resource belongs to another tenant');
        }
        await this.findOne(id, user);
        return this.prisma.tenant.update({
            where: { id },
            data: dto,
        });
    }
    async remove(id) {
        const tenant = await this.prisma.tenant.findUnique({ where: { id } });
        if (!tenant) {
            throw new common_1.NotFoundException(`Tenant ${id} not found`);
        }
        return this.prisma.tenant.delete({ where: { id } });
    }
    async addBusinessLine(tenantId, dto, user) {
        await this.findOne(tenantId, user);
        return this.prisma.businessLine.create({
            data: {
                name: dto.name,
                code: dto.code,
                tenantId,
            },
        });
    }
    async updateBusinessLine(tenantId, blId, dto, user) {
        await this.findOne(tenantId, user);
        const bl = await this.prisma.businessLine.findFirst({
            where: { id: blId, tenantId },
        });
        if (!bl) {
            throw new common_1.NotFoundException(`Business line ${blId} not found in tenant ${tenantId}`);
        }
        return this.prisma.businessLine.update({
            where: { id: blId },
            data: dto,
        });
    }
    async removeBusinessLine(tenantId, blId, user) {
        await this.findOne(tenantId, user);
        const bl = await this.prisma.businessLine.findFirst({
            where: { id: blId, tenantId },
        });
        if (!bl) {
            throw new common_1.NotFoundException(`Business line ${blId} not found in tenant ${tenantId}`);
        }
        return this.prisma.businessLine.delete({ where: { id: blId } });
    }
};
exports.TenantService = TenantService;
exports.TenantService = TenantService = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [prisma_service_1.PrismaService])
], TenantService);
//# sourceMappingURL=tenant.service.js.map