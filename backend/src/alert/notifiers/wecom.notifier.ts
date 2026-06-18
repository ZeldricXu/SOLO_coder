import axios from 'axios';
import { BaseNotifier, AlertMessage } from './base.notifier';

export class WeComNotifier extends BaseNotifier {
  constructor(private readonly webhookUrl: string) {
    super();
  }

  async send(message: AlertMessage): Promise<void> {
    const content = [
      `### 🚨 ${message.ruleName}`,
      `**Metric:** ${message.metricName}`,
      `**Value:** ${message.value}`,
      `**Condition:** ${JSON.stringify(message.condition)}`,
      `**Time:** ${message.timestamp.toISOString()}`,
      `[View Dashboard](${message.dashboardUrl})`,
    ].join('\n');

    await axios.post(this.webhookUrl, {
      msgtype: 'markdown',
      markdown: { content },
    });
  }
}
