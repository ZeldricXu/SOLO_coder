import { Command, Flags } from '@oclif/core'
import { loadContext } from './env/list'
import { NotificationDispatcher, SlackWebhookChannel, EmailChannel, CustomWebhookChannel } from '../notifications/NotificationDispatcher'
import { HistoryStorage } from '../storage/HistoryStorage'
import { NotificationMessage } from '../types'
import chalk from 'chalk'

export default class NotifyCommand extends Command {
  static description = 'Send test notifications via configured channels'
  static aliases = ['notify:test']

  static flags = {
    config: Flags.string({ char: 'c', description: 'Path to config file' }),
    list: Flags.boolean({ char: 'l', description: 'List configured channels' }),
    type: Flags.string({
      char: 't',
      description: 'Channel type: slack|email|webhook',
      options: ['slack', 'email', 'webhook'],
    }),
    slack: Flags.string({ description: 'Slack webhook URL (ad-hoc)' }),
    email: Flags.string({ description: 'Email config JSON (ad-hoc): {"host":"","port":587,"from":"","to":[""]}' }),
    webhook: Flags.string({ description: 'Custom webhook URL (ad-hoc)' }),
    title: Flags.string({ default: 'ConfigFlow Test Notification', description: 'Notification title' }),
    summary: Flags.string({ default: 'This is a test notification from ConfigFlow CLI', description: 'Summary text' }),
    operator: Flags.string({ char: 'u', default: 'test-user' }),
    json: Flags.boolean({ description: 'Output as JSON' }),
  }

  async run(): Promise<void> {
    const { flags } = await this.parse(NotifyCommand)
    const ctx = await loadContext(flags.config)

    const dispatcher = new NotificationDispatcher()

    if (ctx.config.notifications && (!flags.type)) {
      for (const n of ctx.config.notifications) {
        dispatcher.addChannel(n)
      }
    }

    if (flags.slack) {
      dispatcher.addCustomChannel('adhoc-slack', new SlackWebhookChannel({ webhookUrl: flags.slack }))
    }
    if (flags.email) {
      try {
        const cfg = JSON.parse(flags.email)
        dispatcher.addCustomChannel('adhoc-email', new EmailChannel(cfg))
      } catch {
        this.error('Invalid --email JSON config')
      }
    }
    if (flags.webhook) {
      dispatcher.addCustomChannel('adhoc-webhook', new CustomWebhookChannel({ url: flags.webhook }))
    }

    const channels = dispatcher.listChannels()

    if (flags.list) {
      if (flags.json) {
        this.log(JSON.stringify(channels, null, 2))
      } else {
        if (channels.length === 0) {
          this.warn('No notification channels configured.')
          this.log('Add channels to config-flow.yaml or use --slack, --email, or --webhook flags.')
        } else {
          this.log(`📣 Configured notification channels (${channels.length})`)
          for (const ch of channels) {
            this.log(`  - ${chalk.cyan(ch.id)} (type: ${chalk.yellow(ch.type)})`)
          }
        }
      }
      return
    }

    if (channels.length === 0) {
      this.error('No notification channels available. Configure in config-flow.yaml or use --slack/--email/--webhook.')
    }

    const message: NotificationMessage = {
      title: flags.title,
      summary: flags.summary,
      operator: flags.operator,
      environment: 'test',
      timestamp: Date.now(),
      changes: [
        { type: 'changed', key: 'app.port', path: 'app.port', before: 3000, after: 3001, changePercent: 0.03 },
        { type: 'added', key: 'app.debug', path: 'app.debug', after: true },
      ],
    }

    const results = await dispatcher.dispatch(message)

    const storage = new HistoryStorage(ctx.config.storagePath)
    await storage.recordNotification(message, results)
    storage.close()

    if (flags.json) {
      this.log(JSON.stringify({ message, results }, null, 2))
      return
    }

    const success = results.filter((r) => r.success).length
    const failed = results.filter((r) => !r.success).length
    this.log(`\nNotifications: ${chalk.green(success)} success, ${chalk.red(failed)} failed`)

    for (const r of results) {
      const icon = r.success ? chalk.green('✓') : chalk.red('✗')
      this.log(`  ${icon} ${r.channelId}${r.error ? ` - ${r.error}` : ''}`)
    }

    if (failed > 0) this.exit(1)
  }
}
