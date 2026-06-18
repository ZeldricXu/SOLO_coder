import { Injectable, Logger } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { NotifierFactory } from './notifiers/notifier-factory';
import { AlertMessage } from './notifiers/base.notifier';

@Injectable()
export class NotificationService {
  private readonly logger = new Logger(NotificationService.name);

  constructor(private readonly prisma: PrismaService) {}

  async sendNotifications(
    ruleId: string,
    channels: { type: string; target: string }[],
    escalationMinutes: number,
    escalationChannels: { type: string; target: string }[] | null,
    message: AlertMessage,
  ): Promise<void> {
    const results = await Promise.allSettled(
      channels.map((ch) => {
        const notifier = NotifierFactory.create(ch.type as any, ch.target);
        return notifier.send(message);
      }),
    );

    const failures = results.filter((r) => r.status === 'rejected');
    if (failures.length > 0) {
      this.logger.warn(
        `${failures.length}/${channels.length} notification(s) failed for rule ${ruleId}`,
      );
    }

    await this.prisma.alertRecord.updateMany({
      where: { ruleId, notified: false },
      data: { notified: true, notifiedAt: new Date() },
    });

    if (escalationMinutes > 0 && escalationChannels && escalationChannels.length > 0) {
      setTimeout(
        () => this.handleEscalation(ruleId, escalationChannels, message),
        escalationMinutes * 60 * 1000,
      );
    }
  }

  private async handleEscalation(
    ruleId: string,
    escalationChannels: { type: string; target: string }[],
    message: AlertMessage,
  ): Promise<void> {
    const unacknowledged = await this.prisma.alertRecord.findFirst({
      where: { ruleId, acknowledged: false },
      orderBy: { createdAt: 'desc' },
    });

    if (!unacknowledged) {
      return;
    }

    this.logger.warn(`Escalating alert for rule ${ruleId}`);

    const escalationMessage: AlertMessage = {
      ...message,
      ruleName: `[ESCALATION] ${message.ruleName}`,
    };

    await Promise.allSettled(
      escalationChannels.map((ch) => {
        const notifier = NotifierFactory.create(ch.type as any, ch.target);
        return notifier.send(escalationMessage);
      }),
    );
  }
}
