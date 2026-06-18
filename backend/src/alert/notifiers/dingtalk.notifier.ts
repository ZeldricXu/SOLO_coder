import axios from 'axios';
import { BaseNotifier, AlertMessage } from './base.notifier';

export class DingTalkNotifier extends BaseNotifier {
  constructor(private readonly webhookUrl: string) {
    super();
  }

  async send(message: AlertMessage): Promise<void> {
    const text = [
      `### 🚨 ${message.ruleName}`,
      `**Metric:** ${message.metricName}`,
      `**Value:** ${message.value}`,
      `**Condition:** ${JSON.stringify(message.condition)}`,
      `**Time:** ${message.timestamp.toISOString()}`,
      `[View Dashboard](${message.dashboardUrl})`,
    ].join('\n');

    await axios.post(this.webhookUrl, {
      msgtype: 'markdown',
      markdown: { title: `Alert: ${message.ruleName}`, text },
    });
  }
}
