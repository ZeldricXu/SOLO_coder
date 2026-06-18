import { PrismaService } from '../prisma/prisma.service';
import { AlertMessage } from './notifiers/base.notifier';
export declare class NotificationService {
    private readonly prisma;
    private readonly logger;
    constructor(prisma: PrismaService);
    sendNotifications(ruleId: string, channels: {
        type: string;
        target: string;
    }[], escalationMinutes: number, escalationChannels: {
        type: string;
        target: string;
    }[] | null, message: AlertMessage): Promise<void>;
    private handleEscalation;
}
