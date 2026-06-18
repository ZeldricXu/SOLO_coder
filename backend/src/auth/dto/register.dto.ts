import {
  IsString,
  IsEmail,
  IsOptional,
  IsEnum,
  MinLength,
  IsNotEmpty,
} from 'class-validator';
import { Role } from '@prisma/client';

export class RegisterDto {
  @IsEmail()
  email: string;

  @IsString()
  @MinLength(8)
  password: string;

  @IsString()
  @IsNotEmpty()
  name: string;

  @IsOptional()
  @IsEnum(Role)
  role?: Role;

  @IsOptional()
  @IsString()
  tenantId?: string;
}
