import {
  Injectable,
  NestInterceptor,
  ExecutionContext,
  CallHandler,
} from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { Observable, tap } from 'rxjs';
import { PrismaService } from '../../prisma/prisma.service';
import { AUDIT_KEY } from '../decorators/audit.decorator';

@Injectable()
export class AuditInterceptor implements NestInterceptor {
  constructor(
    private reflector: Reflector,
    private prisma: PrismaService,
  ) {}

  intercept(ctx: ExecutionContext, next: CallHandler): Observable<any> {
    const auditMeta = this.reflector.get(AUDIT_KEY, ctx.getHandler());

    if (!auditMeta) {
      return next.handle();
    }

    const request = ctx.switchToHttp().getRequest();
    const user = request.user;

    return next.handle().pipe(
      tap(async () => {
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
      }),
    );
  }
}
