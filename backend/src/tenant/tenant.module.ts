import { Module } from '@nestjs/common';
import { PrismaModule } from '../prisma/prisma.module';
import { AuthModule } from '../auth/auth.module';
import { TenantController } from './tenant.controller';
import { TenantService } from './tenant.service';

@Module({
  imports: [PrismaModule, AuthModule],
  controllers: [TenantController],
  providers: [TenantService],
  exports: [TenantService],
})
export class TenantModule {}
