import {
  Injectable,
  CanActivate,
  ExecutionContext,
  ForbiddenException,
} from '@nestjs/common';

@Injectable()
export class TenantGuard implements CanActivate {
  canActivate(ctx: ExecutionContext): boolean {
    const request = ctx.switchToHttp().getRequest();
    const user = request.user;
    const resourceTenantId =
      request.params?.tenantId ?? request.body?.tenantId;

    if (!user) {
      throw new ForbiddenException('User not authenticated');
    }

    if (user.role === 'SUPER_ADMIN') {
      return true;
    }

    if (!user.tenantId) {
      throw new ForbiddenException('User has no tenant assignment');
    }

    if (resourceTenantId && resourceTenantId !== user.tenantId) {
      throw new ForbiddenException(
        'Access denied: resource belongs to another tenant',
      );
    }

    request.tenantId = user.tenantId;
    return true;
  }
}
