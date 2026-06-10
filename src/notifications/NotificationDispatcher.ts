import { NotificationConfig, NotificationMessage, DiffItem } from '../types'
import dayjs from 'dayjs'
import * as crypto from 'crypto'

export interface NotificationChannel {
  readonly type: string
  send(message: NotificationMessage): Promise<{ success: boolean; error?: string }>
}

export class SlackWebhookChannel implements NotificationChannel {
  readonly type = 'slack'
  private webhookUrl: string
  private username?: string
  private channel?: string

  constructor(config: { webhookUrl: string; username?: string; channel?: string }) {
    this.webhookUrl = config.webhookUrl
    this.username = config.username
    this.channel = config.channel
  }

  async send(message: NotificationMessage): Promise<{ success: boolean; error?: string }> {
    try {
      const { default: axios } = await import('axios')

      const blocks = this.buildBlocks(message)

      const payload: Record<string, unknown> = {
        text: message.title,
        blocks,
      }

      if (this.username) payload.username = this.username
      if (this.channel) payload.channel = this.channel

      await axios.post(this.webhookUrl, payload)
      return { success: true }
    } catch (error) {
      return { success: false, error: (error as Error).message }
    }
  }

  private buildBlocks(message: NotificationMessage): Record<string, unknown>[] {
    const blocks: Record<string, unknown>[] = []

    blocks.push({
      type: 'header',
      text: {
        type: 'plain_text',
        text: message.title,
      },
    })

    blocks.push({
      type: 'section',
      fields: [
        { type: 'mrkdwn', text: `*环境:*\n${message.environment}` },
        { type: 'mrkdwn', text: `*操作人:*\n${message.operator}` },
        { type: 'mrkdwn', text: `*时间:*\n${dayjs(message.timestamp).format('YYYY-MM-DD HH:mm:ss')}` },
        { type: 'mrkdwn', text: `*变更数:*\n${message.changes.length}` },
      ],
    })

    blocks.push({ type: 'divider' })

    if (message.summary) {
      blocks.push({
        type: 'section',
        text: {
          type: 'mrkdwn',
          text: `*摘要:*\n${message.summary}`,
        },
      })
    }

    const changeBlocks = this.formatChangesAsBlocks(message.changes)
    blocks.push(...changeBlocks)

    return blocks
  }

  private formatChangesAsBlocks(changes: DiffItem[]): Record<string, unknown>[] {
    const blocks: Record<string, unknown>[] = []
    const maxDisplay = 10

    for (let i = 0; i < Math.min(changes.length, maxDisplay); i++) {
      const change = changes[i]
      const icon = change.type === 'added' ? '✅' : change.type === 'removed' ? '❌' : '🔄'
      const action = change.type === 'added' ? '新增' : change.type === 'removed' ? '删除' : '变更'

      let detail = ''
      if (change.type === 'added') {
        detail = `\`${change.path}\` = \`${this.formatValue(change.after)}\``
      } else if (change.type === 'removed') {
        detail = `\`${change.path}\` (was: \`${this.formatValue(change.before)}\`)`
      } else {
        const pct = change.changePercent !== undefined ? ` (${change.changePercent > 0 ? '+' : ''}${change.changePercent}%)` : ''
        detail = `\`${change.path}\`: \`${this.formatValue(change.before)}\` → \`${this.formatValue(change.after)}\`${pct}`
      }

      blocks.push({
        type: 'section',
        text: {
          type: 'mrkdwn',
          text: `${icon} *${action}*: ${detail}`,
        },
      })
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
      })
    }

    return blocks
  }

  private formatValue(v: unknown): string {
    if (v === undefined || v === null) return 'null'
    if (typeof v === 'string') return v
    if (typeof v === 'object') return JSON.stringify(v)
    return String(v)
  }
}

export class EmailChannel implements NotificationChannel {
  readonly type = 'email'
  private config: {
    host: string
    port: number
    secure?: boolean
    auth?: { user: string; pass: string }
    from: string
    to: string[]
    subjectPrefix?: string
  }

  constructor(config: EmailChannel['config']) {
    this.config = config
  }

  async send(message: NotificationMessage): Promise<{ success: boolean; error?: string }> {
    try {
      const nodemailer = await import('nodemailer')

      const transporter = nodemailer.createTransport({
        host: this.config.host,
        port: this.config.port,
        secure: this.config.secure,
        auth: this.config.auth,
      })

      const htmlBody = this.buildHtmlBody(message)
      const textBody = this.buildTextBody(message)

      const subject = `${this.config.subjectPrefix || '[ConfigFlow]'} ${message.title}`

      const info = await transporter.sendMail({
        from: this.config.from,
        to: this.config.to.join(', '),
        subject,
        text: textBody,
        html: htmlBody,
      })

      return { success: !!info.messageId }
    } catch (error) {
      return { success: false, error: (error as Error).message }
    }
  }

  private buildHtmlBody(message: NotificationMessage): string {
    const changesHtml = message.changes.map((c: any) => {
      const bg = c.type === 'added' ? '#d4edda' : c.type === 'removed' ? '#f8d7da' : '#fff3cd'
      const action = c.type === 'added' ? '新增' : c.type === 'removed' ? '删除' : '变更'

      let detail = ''
      if (c.type === 'added') {
        detail = `<code>${c.path}</code> = <code>${this.escapeHtml(this.formatValue(c.after))}</code>`
      } else if (c.type === 'removed') {
        detail = `<code>${c.path}</code> (was: <code>${this.escapeHtml(this.formatValue(c.before))}</code>)`
      } else {
        const pct = c.changePercent !== undefined ? ` (${c.changePercent > 0 ? '+' : ''}${c.changePercent}%)` : ''
        detail = `<code>${c.path}</code>: <code>${this.escapeHtml(this.formatValue(c.before))}</code> → <code>${this.escapeHtml(this.formatValue(c.after))}</code>${pct}`
      }

      return `<div style="padding:8px;margin:4px 0;background:${bg};border-radius:4px;">
        <strong>[${action}]</strong> ${detail}
      </div>`
    }).join('')

    return `
      <div style="font-family:Arial,sans-serif;max-width:800px;margin:0 auto;">
        <h2 style="color:#333;">${this.escapeHtml(message.title)}</h2>
        <table style="width:100%;border-collapse:collapse;margin:16px 0;">
          <tr><td style="padding:8px;border:1px solid #ddd;"><strong>环境</strong></td><td style="padding:8px;border:1px solid #ddd;">${message.environment}</td></tr>
          <tr><td style="padding:8px;border:1px solid #ddd;"><strong>操作人</strong></td><td style="padding:8px;border:1px solid #ddd;">${message.operator}</td></tr>
          <tr><td style="padding:8px;border:1px solid #ddd;"><strong>时间</strong></td><td style="padding:8px;border:1px solid #ddd;">${dayjs(message.timestamp).format('YYYY-MM-DD HH:mm:ss')}</td></tr>
          <tr><td style="padding:8px;border:1px solid #ddd;"><strong>变更数</strong></td><td style="padding:8px;border:1px solid #ddd;">${message.changes.length}</td></tr>
        </table>
        ${message.summary ? `<p><strong>摘要:</strong> ${this.escapeHtml(message.summary)}</p>` : ''}
        <h3>变更明细</h3>
        ${changesHtml || '<p>无变更</p>'}
      </div>
    `
  }

  private buildTextBody(message: NotificationMessage): string {
    let text = `${message.title}\n${'='.repeat(40)}\n\n`
    text += `环境: ${message.environment}\n`
    text += `操作人: ${message.operator}\n`
    text += `时间: ${dayjs(message.timestamp).format('YYYY-MM-DD HH:mm:ss')}\n`
    text += `变更数: ${message.changes.length}\n\n`
    if (message.summary) text += `摘要: ${message.summary}\n\n`
    text += '变更明细:\n' + '-'.repeat(40) + '\n'

    for (const c of message.changes) {
      const action = c.type === 'added' ? '+' : c.type === 'removed' ? '-' : '~'
      if (c.type === 'added') {
        text += `${action} ${c.path} = ${this.formatValue(c.after)}\n`
      } else if (c.type === 'removed') {
        text += `${action} ${c.path} (was: ${this.formatValue(c.before)})\n`
      } else {
        const pct = c.changePercent !== undefined ? ` (${c.changePercent > 0 ? '+' : ''}${c.changePercent}%)` : ''
        text += `${action} ${c.path}: ${this.formatValue(c.before)} -> ${this.formatValue(c.after)}${pct}\n`
      }
    }

    return text
  }

  private formatValue(v: unknown): string {
    if (v === undefined || v === null) return 'null'
    if (typeof v === 'string') return v
    if (typeof v === 'object') return JSON.stringify(v)
    return String(v)
  }

  private escapeHtml(str: string): string {
    return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
  }
}

export class CustomWebhookChannel implements NotificationChannel {
  readonly type = 'webhook'
  private config: {
    url: string
    method?: 'POST' | 'PUT' | 'PATCH'
    headers?: Record<string, string>
    secret?: string
  }

  constructor(config: CustomWebhookChannel['config']) {
    this.config = {
      method: 'POST',
      ...config,
    }
  }

  async send(message: NotificationMessage): Promise<{ success: boolean; error?: string }> {
    try {
      const { default: axios } = await import('axios')

      const payload = this.buildPayload(message)

      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        ...(this.config.headers || {}),
      }

      if (this.config.secret) {
        const signature = crypto
          .createHmac('sha256', this.config.secret)
          .update(JSON.stringify(payload))
          .digest('hex')
        headers['X-ConfigFlow-Signature'] = signature
      }

      await axios({
        url: this.config.url,
        method: this.config.method,
        headers,
        data: payload,
      })

      return { success: true }
    } catch (error) {
      return { success: false, error: (error as Error).message }
    }
  }

  private buildPayload(message: NotificationMessage): Record<string, unknown> {
    return {
      event: 'config.change',
      title: message.title,
      summary: message.summary,
      operator: message.operator,
      environment: message.environment,
      timestamp: message.timestamp,
      timestampISO: new Date(message.timestamp).toISOString(),
      changes: message.changes.map((c: any) => ({
        type: c.type,
        key: c.key,
        path: c.path,
        before: c.before,
        after: c.after,
        changePercent: c.changePercent,
      })),
    }
  }
}

export class NotificationDispatcher {
  private channels: Map<string, NotificationChannel> = new Map()

  constructor(configs: NotificationConfig[] = []) {
    for (const config of configs) {
      this.addChannel(config)
    }
  }

  addChannel(config: NotificationConfig): void {
    const id = `${config.type}-${this.channels.size}`

    switch (config.type) {
      case 'slack':
        this.channels.set(id, new SlackWebhookChannel(config.config as any))
        break
      case 'email':
        this.channels.set(id, new EmailChannel(config.config as any))
        break
      case 'webhook':
        this.channels.set(id, new CustomWebhookChannel(config.config as any))
        break
      default:
        throw new Error(`Unsupported notification type: ${config.type}`)
    }
  }

  addCustomChannel(id: string, channel: NotificationChannel): void {
    this.channels.set(id, channel)
  }

  async dispatch(message: NotificationMessage): Promise<{ channelId: string; success: boolean; error?: string }[]> {
    const results: { channelId: string; success: boolean; error?: string }[] = []

    for (const [id, channel] of this.channels) {
      try {
        const result = await channel.send(message)
        results.push({ channelId: id, ...result })
      } catch (error) {
        results.push({
          channelId: id,
          success: false,
          error: (error as Error).message,
        })
      }
    }

    return results
  }

  async dispatchTo(
    channelIds: string[],
    message: NotificationMessage
  ): Promise<{ channelId: string; success: boolean; error?: string }[]> {
    const results: { channelId: string; success: boolean; error?: string }[] = []

    for (const id of channelIds) {
      const channel = this.channels.get(id)
      if (!channel) {
        results.push({ channelId: id, success: false, error: 'Channel not found' })
        continue
      }

      try {
        const result = await channel.send(message)
        results.push({ channelId: id, ...result })
      } catch (error) {
        results.push({
          channelId: id,
          success: false,
          error: (error as Error).message,
        })
      }
    }

    return results
  }

  listChannels(): { id: string; type: string }[] {
    const result: { id: string; type: string }[] = []
    for (const [id, channel] of this.channels) {
      result.push({ id, type: channel.type })
    }
    return result
  }
}
