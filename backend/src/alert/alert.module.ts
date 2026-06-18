import { Module, forwardRef } from '@nestjs/common';
import { BullModule } from '@nestjs/bullmq';
import { PrismaModule } from '../prisma/prisma.module';
import { MetricModule } from '../metric/metric.module';
import { AlertController } from './alert.controller';
import { AlertService } from './alert.service';
import { AlertProcessor } from './alert.processor';
import { NotificationService } from './notification.service';

@Module({
  imports: [
    PrismaModule,
    forwardRef(() => MetricModule),
    BullModule.registerQueue({
      name: 'alert-evaluation',
    }),
  ],
  controllers: [AlertController],
  providers: [AlertService, AlertProcessor, NotificationService],
  exports: [AlertService],
})
export class AlertModule {}
