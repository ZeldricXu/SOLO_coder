import { Strategy } from 'passport-local';
import { AuthService } from '../auth.service';
declare const LocalStrategy_base: new (...args: any[]) => Strategy;
export declare class LocalStrategy extends LocalStrategy_base {
    private authService;
    constructor(authService: AuthService);
    validate(email: string, password: string): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        role: import(".prisma/client").$Enums.Role;
        email: string;
        tenantId: string | null;
    }>;
}
export {};
