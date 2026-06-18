import { AuditService } from './audit.service';
import { QueryAuditDto } from './dto/query-audit.dto';
export declare class AuditController {
    private readonly auditService;
    constructor(auditService: AuditService);
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
}
