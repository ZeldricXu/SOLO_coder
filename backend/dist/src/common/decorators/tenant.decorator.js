"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Tenant = void 0;
const common_1 = require("@nestjs/common");
exports.Tenant = (0, common_1.createParamDecorator)((data, ctx) => {
    const request = ctx.switchToHttp().getRequest();
    const tenantId = request.user?.tenantId;
    return data ? request.user?.[data] : tenantId;
});
//# sourceMappingURL=tenant.decorator.js.map