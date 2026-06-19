import { JwtService } from '@nestjs/jwt';
import { PrismaService } from '../prisma/prisma.service';
import { RegisterDto } from './dto/register.dto';
import { UpdateProfileDto } from './dto/update-profile.dto';
export declare class AuthService {
    private readonly prisma;
    private readonly jwtService;
    constructor(prisma: PrismaService, jwtService: JwtService);
    register(dto: RegisterDto): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        role: import(".prisma/client").$Enums.Role;
        email: string;
        tenantId: string | null;
    }>;
    login(user: any): Promise<{
        access_token: string;
        user: any;
    }>;
    validateUser(email: string, password: string): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        role: import(".prisma/client").$Enums.Role;
        email: string;
        tenantId: string | null;
    }>;
    generateToken(user: any): string;
    getProfile(userId: string): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        role: import(".prisma/client").$Enums.Role;
        email: string;
        tenantId: string | null;
    }>;
    updateProfile(userId: string, dto: UpdateProfileDto): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        role: import(".prisma/client").$Enums.Role;
        email: string;
        tenantId: string | null;
    }>;
}
