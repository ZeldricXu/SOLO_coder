"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.TenantGuard = void 0;
const common_1 = require("@nestjs/common");
let TenantGuard = class TenantGuard {
    canActivate(ctx) {
        const request = ctx.switchToHttp().getRequest();
        const user = request.user;
        const resourceTenantId = request.params?.tenantId ?? request.body?.tenantId;
        if (!user) {
            throw new common_1.ForbiddenException('User not authenticated');
        }
        if (user.role === 'SUPER_ADMIN') {
            return true;
        }
        if (!user.tenantId) {
            throw new common_1.ForbiddenException('User has no tenant assignment');
        }
        if (resourceTenantId && resourceTenantId !== user.tenantId) {
            throw new common_1.ForbiddenException('Access denied: resource belongs to another tenant');
        }
        request.tenantId = user.tenantId;
        return true;
    }
};
exports.TenantGuard = TenantGuard;
exports.TenantGuard = TenantGuard = __decorate([
    (0, common_1.Injectable)()
], TenantGuard);
//# sourceMappingURL=tenant.guard.js.map