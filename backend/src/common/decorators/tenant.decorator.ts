import { createParamDecorator, ExecutionContext } from '@nestjs/common';

export const Tenant = createParamDecorator(
  (data: string | undefined, ctx: ExecutionContext) => {
    const request = ctx.switchToHttp().getRequest();
    const tenantId = request.user?.tenantId;
    return data ? request.user?.[data] : tenantId;
  },
);
