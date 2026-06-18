import { Module, forwardRef } from '@nestjs/common';
import { PrismaModule } from '../prisma/prisma.module';
import { DataSourceModule } from '../data-source/data-source.module';
import { MetricController } from './metric.controller';
import { MetricService } from './metric.service';

@Module({
  imports: [PrismaModule, forwardRef(() => DataSourceModule)],
  controllers: [MetricController],
  providers: [MetricService],
  exports: [MetricService],
})
export class MetricModule {}
