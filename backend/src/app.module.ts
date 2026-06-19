import { Module } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { BullModule } from '@nestjs/bullmq';
import { PrismaModule } from './prisma/prisma.module';
import { DataSourceModule } from './data-source/data-source.module';
import { MetricModule } from './metric/metric.module';
import { DashboardModule } from './dashboard/dashboard.module';
import { RealtimeModule } from './realtime/realtime.module';
import { AlertModule } from './alert/alert.module';
import { AuthModule } from './auth/auth.module';
import { TenantModule } from './tenant/tenant.module';
import { AuditModule } from './audit/audit.module';
import { ChangeDetectorModule } from './change-detector/change-detector.module';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
    }),
    BullModule.forRootAsync({
      inject: [ConfigService],
      useFactory: (configService: ConfigService) => ({
        connection: {
          host: configService.get<string>('REDIS_HOST', 'localhost'),
          port: configService.get<number>('REDIS_PORT', 6379),
        },
      }),
    }),
    PrismaModule,
    DataSourceModule,
    MetricModule,
    DashboardModule,
    RealtimeModule,
    AlertModule,
    AuthModule,
    TenantModule,
    AuditModule,
    ChangeDetectorModule,
  ],
})
export class AppModule {}
