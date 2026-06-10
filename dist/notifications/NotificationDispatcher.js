"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.NotificationDispatcher = exports.CustomWebhookChannel = exports.EmailChannel = exports.SlackWebhookChannel = void 0;
const dayjs_1 = __importDefault(require("dayjs"));
const crypto = __importStar(require("crypto"));
class SlackWebhookChannel {
    type = 'slack';
    webhookUrl;
    username;
    channel;
    constructor(config) {
        this.webhookUrl = config.webhookUrl;
        this.username = config.username;
        this.channel = config.channel;
    }
    async send(message) {
        try {
            const { default: axios } = await Promise.resolve().then(() => __importStar(require('axios')));
            const blocks = this.buildBlocks(message);
            const payload = {
                text: message.title,
                blocks,
            };
            if (this.username)
                payload.username = this.username;
            if (this.channel)
                payload.channel = this.channel;
            await axios.post(this.webhookUrl, payload);
            return { success: true };
        }
        catch (error) {
            return { success: false, error: error.message };
        }
    }
    buildBlocks(message) {
        const blocks = [];
        blocks.push({
            type: 'header',
            text: {
                type: 'plain_text',
                text: message.title,
            },
        });
        blocks.push({
            type: 'section',
            fields: [
                { type: 'mrkdwn', text: `*环境:*\n${message.environment}` },
                { type: 'mrkdwn', text: `*操作人:*\n${message.operator}` },
                { type: 'mrkdwn', text: `*时间:*\n${(0, dayjs_1.default)(message.timestamp).format('YYYY-MM-DD HH:mm:ss')}` },
                { type: 'mrkdwn', text: `*变更数:*\n${message.changes.length}` },
            ],
        });
        blocks.push({ type: 'divider' });
        if (message.summary) {
            blocks.push({
                type: 'section',
                text: {
                    type: 'mrkdwn',
                    text: `*摘要:*\n${message.summary}`,
                },
            });
        }
        const changeBlocks = this.formatChangesAsBlocks(message.changes);
        blocks.push(...changeBlocks);
        return blocks;
    }
    formatChangesAsBlocks(changes) {
        const blocks = [];
        const maxDisplay = 10;
        for (let i = 0; i < Math.min(changes.length, maxDisplay); i++) {
            const change = changes[i];
            const icon = change.type === 'added' ? '✅' : change.type === 'removed' ? '❌' : '🔄';
            const action = change.type === 'added' ? '新增' : change.type === 'removed' ? '删除' : '变更';
            let detail = '';
            if (change.type === 'added') {
                detail = `\`${change.path}\` = \`${this.formatValue(change.after)}\``;
            }
            else if (change.type === 'removed') {
                detail = `\`${change.path}\` (was: \`${this.formatValue(change.before)}\`)`;
            }
            else {
                const pct = change.changePercent !== undefined ? ` (${change.changePercent > 0 ? '+' : ''}${change.changePercent}%)` : '';
                detail = `\`${change.path}\`: \`${this.formatValue(change.before)}\` → \`${this.formatValue(change.after)}\`${pct}`;
            }
            blocks.push({
                type: 'section',
                text: {
                    type: 'mrkdwn',
                    text: `${icon} *${action}*: ${detail}`,
                },
            });
        }
        if (changes.length > maxDisplay) {
            blocks.push({
                type: 'context',
                elements: [
                    {
                        type: 'mrkdwn',
                        text: `... and ${changes.length - maxDisplay} more changes`,
                    },
                ],
            });
        }
        return blocks;
    }
    formatValue(v) {
        if (v === undefined || v === null)
            return 'null';
        if (typeof v === 'string')
            return v;
        if (typeof v === 'object')
            return JSON.stringify(v);
        return String(v);
    }
}
exports.SlackWebhookChannel = SlackWebhookChannel;
class EmailChannel {
    type = 'email';
    config;
    constructor(config) {
        this.config = config;
    }
    async send(message) {
        try {
            const nodemailer = await Promise.resolve().then(() => __importStar(require('nodemailer')));
            const transporter = nodemailer.createTransport({
                host: this.config.host,
                port: this.config.port,
                secure: this.config.secure,
                auth: this.config.auth,
            });
            const htmlBody = this.buildHtmlBody(message);
            const textBody = this.buildTextBody(message);
            const subject = `${this.config.subjectPrefix || '[ConfigFlow]'} ${message.title}`;
            const info = await transporter.sendMail({
                from: this.config.from,
                to: this.config.to.join(', '),
                subject,
                text: textBody,
                html: htmlBody,
            });
            return { success: !!info.messageId };
        }
        catch (error) {
            return { success: false, error: error.message };
        }
    }
    buildHtmlBody(message) {
        const changesHtml = message.changes.map((c) => {
            const bg = c.type === 'added' ? '#d4edda' : c.type === 'removed' ? '#f8d7da' : '#fff3cd';
            const action = c.type === 'added' ? '新增' : c.type === 'removed' ? '删除' : '变更';
            let detail = '';
            if (c.type === 'added') {
                detail = `<code>${c.path}</code> = <code>${this.escapeHtml(this.formatValue(c.after))}</code>`;
            }
            else if (c.type === 'removed') {
                detail = `<code>${c.path}</code> (was: <code>${this.escapeHtml(this.formatValue(c.before))}</code>)`;
            }
            else {
                const pct = c.changePercent !== undefined ? ` (${c.changePercent > 0 ? '+' : ''}${c.changePercent}%)` : '';
                detail = `<code>${c.path}</code>: <code>${this.escapeHtml(this.formatValue(c.before))}</code> → <code>${this.escapeHtml(this.formatValue(c.after))}</code>${pct}`;
            }
            return `<div style="padding:8px;margin:4px 0;background:${bg};border-radius:4px;">
        <strong>[${action}]</strong> ${detail}
      </div>`;
        }).join('');
        return `
      <div style="font-family:Arial,sans-serif;max-width:800px;margin:0 auto;">
        <h2 style="color:#333;">${this.escapeHtml(message.title)}</h2>
        <table style="width:100%;border-collapse:collapse;margin:16px 0;">
          <tr><td style="padding:8px;border:1px solid #ddd;"><strong>环境</strong></td><td style="padding:8px;border:1px solid #ddd;">${message.environment}</td></tr>
          <tr><td style="padding:8px;border:1px solid #ddd;"><strong>操作人</strong></td><td style="padding:8px;border:1px solid #ddd;">${message.operator}</td></tr>
          <tr><td style="padding:8px;border:1px solid #ddd;"><strong>时间</strong></td><td style="padding:8px;border:1px solid #ddd;">${(0, dayjs_1.default)(message.timestamp).format('YYYY-MM-DD HH:mm:ss')}</td></tr>
          <tr><td style="padding:8px;border:1px solid #ddd;"><strong>变更数</strong></td><td style="padding:8px;border:1px solid #ddd;">${message.changes.length}</td></tr>
        </table>
        ${message.summary ? `<p><strong>摘要:</strong> ${this.escapeHtml(message.summary)}</p>` : ''}
        <h3>变更明细</h3>
        ${changesHtml || '<p>无变更</p>'}
      </div>
    `;
    }
    buildTextBody(message) {
        let text = `${message.title}\n${'='.repeat(40)}\n\n`;
        text += `环境: ${message.environment}\n`;
        text += `操作人: ${message.operator}\n`;
        text += `时间: ${(0, dayjs_1.default)(message.timestamp).format('YYYY-MM-DD HH:mm:ss')}\n`;
        text += `变更数: ${message.changes.length}\n\n`;
        if (message.summary)
            text += `摘要: ${message.summary}\n\n`;
        text += '变更明细:\n' + '-'.repeat(40) + '\n';
        for (const c of message.changes) {
            const action = c.type === 'added' ? '+' : c.type === 'removed' ? '-' : '~';
            if (c.type === 'added') {
                text += `${action} ${c.path} = ${this.formatValue(c.after)}\n`;
            }
            else if (c.type === 'removed') {
                text += `${action} ${c.path} (was: ${this.formatValue(c.before)})\n`;
            }
            else {
                const pct = c.changePercent !== undefined ? ` (${c.changePercent > 0 ? '+' : ''}${c.changePercent}%)` : '';
                text += `${action} ${c.path}: ${this.formatValue(c.before)} -> ${this.formatValue(c.after)}${pct}\n`;
            }
        }
        return text;
    }
    formatValue(v) {
        if (v === undefined || v === null)
            return 'null';
        if (typeof v === 'string')
            return v;
        if (typeof v === 'object')
            return JSON.stringify(v);
        return String(v);
    }
    escapeHtml(str) {
        return str
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }
}
exports.EmailChannel = EmailChannel;
class CustomWebhookChannel {
    type = 'webhook';
    config;
    constructor(config) {
        this.config = {
            method: 'POST',
            ...config,
        };
    }
    async send(message) {
        try {
            const { default: axios } = await Promise.resolve().then(() => __importStar(require('axios')));
            const payload = this.buildPayload(message);
            const headers = {
                'Content-Type': 'application/json',
                ...(this.config.headers || {}),
            };
            if (this.config.secret) {
                const signature = crypto
                    .createHmac('sha256', this.config.secret)
                    .update(JSON.stringify(payload))
                    .digest('hex');
                headers['X-ConfigFlow-Signature'] = signature;
            }
            await axios({
                url: this.config.url,
                method: this.config.method,
                headers,
                data: payload,
            });
            return { success: true };
        }
        catch (error) {
            return { success: false, error: error.message };
        }
    }
    buildPayload(message) {
        return {
            event: 'config.change',
            title: message.title,
            summary: message.summary,
            operator: message.operator,
            environment: message.environment,
            timestamp: message.timestamp,
            timestampISO: new Date(message.timestamp).toISOString(),
            changes: message.changes.map((c) => ({
                type: c.type,
                key: c.key,
                path: c.path,
                before: c.before,
                after: c.after,
                changePercent: c.changePercent,
            })),
        };
    }
}
exports.CustomWebhookChannel = CustomWebhookChannel;
class NotificationDispatcher {
    channels = new Map();
    constructor(configs = []) {
        for (const config of configs) {
            this.addChannel(config);
        }
    }
    addChannel(config) {
        const id = `${config.type}-${this.channels.size}`;
        switch (config.type) {
            case 'slack':
                this.channels.set(id, new SlackWebhookChannel(config.config));
                break;
            case 'email':
                this.channels.set(id, new EmailChannel(config.config));
                break;
            case 'webhook':
                this.channels.set(id, new CustomWebhookChannel(config.config));
                break;
            default:
                throw new Error(`Unsupported notification type: ${config.type}`);
        }
    }
    addCustomChannel(id, channel) {
        this.channels.set(id, channel);
    }
    async dispatch(message) {
        const results = [];
        for (const [id, channel] of this.channels) {
            try {
                const result = await channel.send(message);
                results.push({ channelId: id, ...result });
            }
            catch (error) {
                results.push({
                    channelId: id,
                    success: false,
                    error: error.message,
                });
            }
        }
        return results;
    }
    async dispatchTo(channelIds, message) {
        const results = [];
        for (const id of channelIds) {
            const channel = this.channels.get(id);
            if (!channel) {
                results.push({ channelId: id, success: false, error: 'Channel not found' });
                continue;
            }
            try {
                const result = await channel.send(message);
                results.push({ channelId: id, ...result });
            }
            catch (error) {
                results.push({
                    channelId: id,
                    success: false,
                    error: error.message,
                });
            }
        }
        return results;
    }
    listChannels() {
        const result = [];
        for (const [id, channel] of this.channels) {
            result.push({ id, type: channel.type });
        }
        return result;
    }
}
exports.NotificationDispatcher = NotificationDispatcher;
//# sourceMappingURL=NotificationDispatcher.js.map