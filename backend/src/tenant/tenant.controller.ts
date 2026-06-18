import {
  Controller,
  Get,
  Post,
  Put,
  Delete,
  Body,
  Param,
  UseGuards,
} from '@nestjs/common';
import { TenantService } from './tenant.service';
import { CreateTenantDto } from './dto/create-tenant.dto';
import { UpdateTenantDto } from './dto/update-tenant.dto';
import { CreateBusinessLineDto } from './dto/create-business-line.dto';
import { UpdateBusinessLineDto } from './dto/update-business-line.dto';
import { JwtAuthGuard } from '../common/guards/jwt-auth.guard';
import { RolesGuard, Roles } from '../common/guards/roles.guard';
import { CurrentUser } from '../common/decorators/current-user.decorator';
import { Role } from '@prisma/client';

@Controller('tenants')
@UseGuards(JwtAuthGuard, RolesGuard)
export class TenantController {
  constructor(private readonly tenantService: TenantService) {}

  @Post()
  @Roles(Role.SUPER_ADMIN)
  create(@Body() dto: CreateTenantDto) {
    return this.tenantService.create(dto);
  }

  @Get()
  findAll(@CurrentUser() user: any) {
    return this.tenantService.findAll(user);
  }

  @Get(':id')
  findOne(@Param('id') id: string, @CurrentUser() user: any) {
    return this.tenantService.findOne(id, user);
  }

  @Put(':id')
  update(
    @Param('id') id: string,
    @Body() dto: UpdateTenantDto,
    @CurrentUser() user: any,
  ) {
    return this.tenantService.update(id, dto, user);
  }

  @Delete(':id')
  @Roles(Role.SUPER_ADMIN)
  remove(@Param('id') id: string) {
    return this.tenantService.remove(id);
  }

  @Post(':id/business-lines')
  addBusinessLine(
    @Param('id') id: string,
    @Body() dto: CreateBusinessLineDto,
    @CurrentUser() user: any,
  ) {
    return this.tenantService.addBusinessLine(id, dto, user);
  }

  @Put(':id/business-lines/:blId')
  updateBusinessLine(
    @Param('id') id: string,
    @Param('blId') blId: string,
    @Body() dto: UpdateBusinessLineDto,
    @CurrentUser() user: any,
  ) {
    return this.tenantService.updateBusinessLine(id, blId, dto, user);
  }

  @Delete(':id/business-lines/:blId')
  removeBusinessLine(
    @Param('id') id: string,
    @Param('blId') blId: string,
    @CurrentUser() user: any,
  ) {
    return this.tenantService.removeBusinessLine(id, blId, user);
  }
}
