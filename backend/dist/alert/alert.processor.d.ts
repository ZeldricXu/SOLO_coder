import { WorkerHost } from '@nestjs/bullmq';
import { Job } from 'bullmq';
import { AlertService } from './alert.service';
export declare class AlertProcessor extends WorkerHost {
    private readonly alertService;
    private readonly logger;
    constructor(alertService: AlertService);
    process(job: Job<{
        ruleId: string;
    }>): Promise<void>;
}
