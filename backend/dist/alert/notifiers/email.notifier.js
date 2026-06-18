"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.EmailNotifier = void 0;
const nodemailer = require("nodemailer");
const base_notifier_1 = require("./base.notifier");
class EmailNotifier extends base_notifier_1.BaseNotifier {
    constructor(target) {
        super();
        this.target = target;
        this.transporter = nodemailer.createTransport({
            host: process.env.SMTP_HOST || 'localhost',
            port: Number(process.env.SMTP_PORT) || 587,
            secure: process.env.SMTP_SECURE === 'true',
            auth: {
                user: process.env.SMTP_USER,
                pass: process.env.SMTP_PASS,
            },
        });
    }
    async send(message) {
        const subject = `[Alert] ${message.ruleName} - ${message.metricName}`;
        const body = [
            `Alert Rule: ${message.ruleName}`,
            `Metric: ${message.metricName}`,
            `Current Value: ${message.value}`,
            `Condition: ${JSON.stringify(message.condition)}`,
            `Time: ${message.timestamp.toISOString()}`,
            `Dashboard: ${message.dashboardUrl}`,
        ].join('\n');
        await this.transporter.sendMail({
            from: process.env.SMTP_FROM || 'alert@biz-monitor.com',
            to: this.target,
            subject,
            text: body,
        });
    }
}
exports.EmailNotifier = EmailNotifier;
//# sourceMappingURL=email.notifier.js.map