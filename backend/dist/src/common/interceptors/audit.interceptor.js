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
exports.AuditInterceptor = void 0;
const common_1 = require("@nestjs/common");
const core_1 = require("@nestjs/core");
const rxjs_1 = require("rxjs");
const prisma_service_1 = require("../../prisma/prisma.service");
const audit_decorator_1 = require("../decorators/audit.decorator");
let AuditInterceptor = class AuditInterceptor {
    constructor(reflector, prisma) {
        this.reflector = reflector;
        this.prisma = prisma;
    }
    intercept(ctx, next) {
        const auditMeta = this.reflector.get(audit_decorator_1.AUDIT_KEY, ctx.getHandler());
        if (!auditMeta) {
            return next.handle();
        }
        const request = ctx.switchToHttp().getRequest();
        const user = request.user;
        return next.handle().pipe((0, rxjs_1.tap)(async () => {
            await this.prisma.auditLog.create({
                data: {
                    userId: user?.id ?? 'anonymous',
                    userEmail: user?.email ?? 'anonymous',
                    action: auditMeta.action,
                    resource: ctx.getClass().name,
                    resourceId: request.params?.id ?? null,
                    details: {
                        method: request.method,
                        body: request.body,
                        query: request.query,
                    },
                    tenantId: user?.tenantId ?? null,
                    ip: request.ip,
                },
            });
        }));
    }
};
exports.AuditInterceptor = AuditInterceptor;
exports.AuditInterceptor = AuditInterceptor = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [core_1.Reflector,
        prisma_service_1.PrismaService])
], AuditInterceptor);
//# sourceMappingURL=audit.interceptor.js.map