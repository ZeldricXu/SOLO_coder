import { AuthService } from './auth.service';
import { RegisterDto } from './dto/register.dto';
import { LoginDto } from './dto/login.dto';
import { UpdateProfileDto } from './dto/update-profile.dto';
export declare class AuthController {
    private readonly authService;
    constructor(authService: AuthService);
    register(dto: RegisterDto): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        tenantId: string | null;
        role: import(".prisma/client").$Enums.Role;
        email: string;
    }>;
    login(_dto: LoginDto, user: any): Promise<{
        access_token: string;
        user: any;
    }>;
    getProfile(user: any): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        tenantId: string | null;
        role: import(".prisma/client").$Enums.Role;
        email: string;
    }>;
    updateProfile(user: any, dto: UpdateProfileDto): Promise<{
        name: string;
        id: string;
        createdAt: Date;
        updatedAt: Date;
        tenantId: string | null;
        role: import(".prisma/client").$Enums.Role;
        email: string;
    }>;
}
