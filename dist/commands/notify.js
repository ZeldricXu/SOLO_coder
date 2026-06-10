"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const core_1 = require("@oclif/core");
const list_1 = require("./env/list");
const NotificationDispatcher_1 = require("../notifications/NotificationDispatcher");
const HistoryStorage_1 = require("../storage/HistoryStorage");
const chalk_1 = __importDefault(require("chalk"));
class NotifyCommand extends core_1.Command {
    static description = 'Send test notifications via configured channels';
    static aliases = ['notify:test'];
    static flags = {
        config: core_1.Flags.string({ char: 'c', description: 'Path to config file' }),
        list: core_1.Flags.boolean({ char: 'l', description: 'List configured channels' }),
        type: core_1.Flags.string({
            char: 't',
            description: 'Channel type: slack|email|webhook',
            options: ['slack', 'email', 'webhook'],
        }),
        slack: core_1.Flags.string({ description: 'Slack webhook URL (ad-hoc)' }),
        email: core_1.Flags.string({ description: 'Email config JSON (ad-hoc): {"host":"","port":587,"from":"","to":[""]}' }),
        webhook: core_1.Flags.string({ description: 'Custom webhook URL (ad-hoc)' }),
        title: core_1.Flags.string({ default: 'ConfigFlow Test Notification', description: 'Notification title' }),
        summary: core_1.Flags.string({ default: 'This is a test notification from ConfigFlow CLI', description: 'Summary text' }),
        operator: core_1.Flags.string({ char: 'u', default: 'test-user' }),
        json: core_1.Flags.boolean({ description: 'Output as JSON' }),
    };
    async run() {
        const { flags } = await this.parse(NotifyCommand);
        const ctx = await (0, list_1.loadContext)(flags.config);
        const dispatcher = new NotificationDispatcher_1.NotificationDispatcher();
        if (ctx.config.notifications && (!flags.type)) {
            for (const n of ctx.config.notifications) {
                dispatcher.addChannel(n);
            }
        }
        if (flags.slack) {
            dispatcher.addCustomChannel('adhoc-slack', new NotificationDispatcher_1.SlackWebhookChannel({ webhookUrl: flags.slack }));
        }
        if (flags.email) {
            try {
                const cfg = JSON.parse(flags.email);
                dispatcher.addCustomChannel('adhoc-email', new NotificationDispatcher_1.EmailChannel(cfg));
            }
            catch {
                this.error('Invalid --email JSON config');
            }
        }
        if (flags.webhook) {
            dispatcher.addCustomChannel('adhoc-webhook', new NotificationDispatcher_1.CustomWebhookChannel({ url: flags.webhook }));
        }
        const channels = dispatcher.listChannels();
        if (flags.list) {
            if (flags.json) {
                this.log(JSON.stringify(channels, null, 2));
            }
            else {
                if (channels.length === 0) {
                    this.warn('No notification channels configured.');
                    this.log('Add channels to config-flow.yaml or use --slack, --email, or --webhook flags.');
                }
                else {
                    this.log(`📣 Configured notification channels (${channels.length})`);
                    for (const ch of channels) {
                        this.log(`  - ${chalk_1.default.cyan(ch.id)} (type: ${chalk_1.default.yellow(ch.type)})`);
                    }
                }
            }
            return;
        }
        if (channels.length === 0) {
            this.error('No notification channels available. Configure in config-flow.yaml or use --slack/--email/--webhook.');
        }
        const message = {
            title: flags.title,
            summary: flags.summary,
            operator: flags.operator,
            environment: 'test',
            timestamp: Date.now(),
            changes: [
                { type: 'changed', key: 'app.port', path: 'app.port', before: 3000, after: 3001, changePercent: 0.03 },
                { type: 'added', key: 'app.debug', path: 'app.debug', after: true },
            ],
        };
        const results = await dispatcher.dispatch(message);
        const storage = new HistoryStorage_1.HistoryStorage(ctx.config.storagePath);
        await storage.recordNotification(message, results);
        storage.close();
        if (flags.json) {
            this.log(JSON.stringify({ message, results }, null, 2));
            return;
        }
        const success = results.filter((r) => r.success).length;
        const failed = results.filter((r) => !r.success).length;
        this.log(`\nNotifications: ${chalk_1.default.green(success)} success, ${chalk_1.default.red(failed)} failed`);
        for (const r of results) {
            const icon = r.success ? chalk_1.default.green('✓') : chalk_1.default.red('✗');
            this.log(`  ${icon} ${r.channelId}${r.error ? ` - ${r.error}` : ''}`);
        }
        if (failed > 0)
            this.exit(1);
    }
}
exports.default = NotifyCommand;
//# sourceMappingURL=notify.js.map