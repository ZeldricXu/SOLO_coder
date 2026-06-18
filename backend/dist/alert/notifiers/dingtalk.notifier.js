"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.DingTalkNotifier = void 0;
const axios_1 = require("axios");
const base_notifier_1 = require("./base.notifier");
class DingTalkNotifier extends base_notifier_1.BaseNotifier {
    constructor(webhookUrl) {
        super();
        this.webhookUrl = webhookUrl;
    }
    async send(message) {
        const text = [
            `### 🚨 ${message.ruleName}`,
            `**Metric:** ${message.metricName}`,
            `**Value:** ${message.value}`,
            `**Condition:** ${JSON.stringify(message.condition)}`,
            `**Time:** ${message.timestamp.toISOString()}`,
            `[View Dashboard](${message.dashboardUrl})`,
        ].join('\n');
        await axios_1.default.post(this.webhookUrl, {
            msgtype: 'markdown',
            markdown: { title: `Alert: ${message.ruleName}`, text },
        });
    }
}
exports.DingTalkNotifier = DingTalkNotifier;
//# sourceMappingURL=dingtalk.notifier.js.map