import { CanActivate, ExecutionContext } from '@nestjs/common';
export declare class TenantGuard implements CanActivate {
    canActivate(ctx: ExecutionContext): boolean;
}
