import { Module, forwardRef } from '@nestjs/common';
import { DataSourceModule } from '../data-source/data-source.module';
import { MetricModule } from '../metric/metric.module';
import { MetricBuilderService } from './metric-builder.service';
import { MetricBuilderController } from './metric-builder.controller';

@Module({
  imports: [forwardRef(() => DataSourceModule), forwardRef(() => MetricModule)],
  controllers: [MetricBuilderController],
  providers: [MetricBuilderService],
  exports: [MetricBuilderService],
})
export class MetricBuilderModule {}
