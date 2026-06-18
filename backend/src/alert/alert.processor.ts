import { Processor, WorkerHost } from '@nestjs/bullmq';
import { Logger } from '@nestjs/common';
import { Job } from 'bullmq';
import { AlertService } from './alert.service';

@Processor('alert-evaluation')
export class AlertProcessor extends WorkerHost {
  private readonly logger = new Logger(AlertProcessor.name);

  constructor(private readonly alertService: AlertService) {
    super();
  }

  async process(job: Job<{ ruleId: string }>): Promise<void> {
    this.logger.debug(`Processing alert evaluation for rule ${job.data.ruleId}`);
    try {
      await this.alertService.evaluateRule(job.data.ruleId);
    } catch (error) {
      this.logger.error(
        `Failed to evaluate rule ${job.data.ruleId}: ${error.message}`,
        error.stack,
      );
      throw error;
    }
  }
}
