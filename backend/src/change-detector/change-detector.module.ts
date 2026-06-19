import { Module } from '@nestjs/common';
import { PrismaModule } from '../prisma/prisma.module';
import { DataSourceModule } from '../data-source/data-source.module';
import { RealtimeModule } from '../realtime/realtime.module';
import { ChangeDetectorService } from './change-detector.service';

@Module({
  imports: [PrismaModule, DataSourceModule, RealtimeModule],
  providers: [ChangeDetectorService],
  exports: [ChangeDetectorService],
})
export class ChangeDetectorModule {}
