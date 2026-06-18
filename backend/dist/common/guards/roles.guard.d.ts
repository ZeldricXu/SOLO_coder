import { CanActivate, ExecutionContext } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { Role } from '@prisma/client';
export declare const ROLES_KEY = "roles";
export declare const Roles: (...roles: Role[]) => import("@nestjs/common").CustomDecorator<string>;
export declare class RolesGuard implements CanActivate {
    private reflector;
    constructor(reflector: Reflector);
    canActivate(ctx: ExecutionContext): boolean;
}
