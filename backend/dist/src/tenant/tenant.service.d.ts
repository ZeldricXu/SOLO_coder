import { PrismaService } from '../prisma/prisma.service';
import { CreateTenantDto } from './dto/create-tenant.dto';
import { UpdateTenantDto } from './dto/update-tenant.dto';
import { CreateBusinessLineDto } from './dto/create-business-line.dto';
import { UpdateBusinessLineDto } from './dto/update-business-line.dto';
export declare class TenantService {
    private readonly prisma;
    constructor(prisma: PrismaService);
    create(dto: CreateTenantDto): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        slug: string;
    }>;
    findAll(user: any): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        slug: string;
    }[]>;
    findOne(id: string, user: any): Promise<{
        businessLines: {
            name: string;
            id: string;
            createdAt: Date;
            updatedAt: Date;
            code: string;
            tenantId: string;
        }[];
    } & {
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        slug: string;
    }>;
    update(id: string, dto: UpdateTenantDto, user: any): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        slug: string;
    }>;
    remove(id: string): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        slug: string;
    }>;
    addBusinessLine(tenantId: string, dto: CreateBusinessLineDto, user: any): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        code: string;
        tenantId: string;
    }>;
    updateBusinessLine(tenantId: string, blId: string, dto: UpdateBusinessLineDto, user: any): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        code: string;
        tenantId: string;
    }>;
    removeBusinessLine(tenantId: string, blId: string, user: any): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        code: string;
        tenantId: string;
    }>;
}
