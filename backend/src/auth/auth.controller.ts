import {
  Controller,
  Post,
  Get,
  Put,
  Body,
  UseGuards,
} from '@nestjs/common';
import { AuthGuard } from '@nestjs/passport';
import { AuthService } from './auth.service';
import { RegisterDto } from './dto/register.dto';
import { LoginDto } from './dto/login.dto';
import { UpdateProfileDto } from './dto/update-profile.dto';
import { JwtAuthGuard } from '../common/guards/jwt-auth.guard';
import { RolesGuard, Roles } from '../common/guards/roles.guard';
import { CurrentUser } from '../common/decorators/current-user.decorator';
import { Role } from '@prisma/client';

@Controller('auth')
export class AuthController {
  constructor(private readonly authService: AuthService) {}

  @Post('register')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles(Role.SUPER_ADMIN, Role.TENANT_ADMIN)
  register(@Body() dto: RegisterDto) {
    return this.authService.register(dto);
  }

  @Post('login')
  @UseGuards(AuthGuard('local'))
  login(@Body() _dto: LoginDto, @CurrentUser() user: any) {
    return this.authService.login(user);
  }

  @Get('profile')
  @UseGuards(JwtAuthGuard)
  getProfile(@CurrentUser() user: any) {
    return this.authService.getProfile(user.id);
  }

  @Put('profile')
  @UseGuards(JwtAuthGuard)
  updateProfile(@CurrentUser() user: any, @Body() dto: UpdateProfileDto) {
    return this.authService.updateProfile(user.id, dto);
  }
}
