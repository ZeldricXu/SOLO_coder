import { PrismaService } from '../prisma/prisma.service';
import { QueryAuditDto } from './dto/query-audit.dto';
export declare class AuditService {
    private readonly prisma;
    constructor(prisma: PrismaService);
    findAll(dto: QueryAuditDto): Promise<{
        items: {
            id: string;
            createdAt: Date;
            tenantId: string | null;
            action: string;
            resource: string;
            userId: string;
            userEmail: string;
            resourceId: string | null;
            details: import("@prisma/client/runtime/library").JsonValue | null;
            ip: string;
        }[];
        total: number;
        page: number;
        limit: number;
        totalPages: number;
    }>;
    findOne(id: string): Promise<{
        id: string;
        createdAt: Date;
        tenantId: string | null;
        action: string;
        resource: string;
        userId: string;
        userEmail: string;
        resourceId: string | null;
        details: import("@prisma/client/runtime/library").JsonValue | null;
        ip: string;
    }>;
    log(data: {
        userId: string;
        userEmail: string;
        action: string;
        resource: string;
        resourceId?: string;
        details?: any;
        tenantId?: string;
        ip: string;
    }): Promise<{
        id: string;
        createdAt: Date;
        tenantId: string | null;
        action: string;
        resource: string;
        userId: string;
        userEmail: string;
        resourceId: string | null;
        details: import("@prisma/client/runtime/library").JsonValue | null;
        ip: string;
    }>;
}
