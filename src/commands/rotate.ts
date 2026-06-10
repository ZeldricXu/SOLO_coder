import { Command, Flags, Args, ux } from '@oclif/core'
import { loadContext } from './env/list'
import { RotationScheduler } from '../rotation/RotationScheduler'
import { HistoryStorage } from '../storage/HistoryStorage'
import { NotificationDispatcher } from '../notifications/NotificationDispatcher'
import { NotificationMessage, DiffItem } from '../types'
import { formatTimestamp, formatDuration } from '../utils/formatters'
import chalk from 'chalk'

export default class RotateCommand extends Command {
  static description = 'Rotate secrets in Vault or SSM and track history'

  static args = {
    key: Args.string({ description: 'Secret key to rotate', required: true }),
    environment: Args.string({ description: 'Environment name', required: true }),
  }

  static flags = {
    config: Flags.string({ char: 'c', description: 'Path to config file' }),
    batch: Flags.string({ char: 'b', description: 'Comma-separated list of keys to rotate' }),
    list: Flags.boolean({ char: 'l', description: 'List rotation history' }),
    check: Flags.boolean({ description: 'Check if a key needs rotation' }),
    maxAge: Flags.string({ description: 'Max age for --check (e.g., 24h, 7d, 30d)' }),
    schedule: Flags.string({ description: 'Schedule interval (e.g., 24h, 7d)' }),
    operator: Flags.string({ char: 'u', description: 'Operator name' }),
    notify: Flags.boolean({ description: 'Send notifications' }),
    verify: Flags.boolean({ default: true, description: 'Verify value after rotation' }),
    json: Flags.boolean({ description: 'Output as JSON' }),
    force: Flags.boolean({ char: 'f', description: 'Force rotation without prompt' }),
  }

  async run(): Promise<void> {
    const { args, flags } = await this.parse(RotateCommand)
    const ctx = await loadContext(flags.config)
    const operator = flags.operator || ctx.config.defaultOperator || 'unknown'

    const scheduler = new RotationScheduler(operator)

    const storage = new HistoryStorage(ctx.config.storagePath)
    const existingRecords = await storage.getRotationHistory({
      environment: args.environment,
      key: args.key,
    })
    scheduler.setRecords(existingRecords)

    if (flags.list) {
      await this.listHistory(scheduler, args.environment, args.key, storage, flags.json)
      storage.close()
      return
    }

    if (flags.check) {
      const maxAgeMs = this.parseDuration(flags.maxAge || '30d')
      const needs = scheduler.needsRotation(args.environment, args.key, maxAgeMs)
      const age = scheduler.getRotationAge(args.environment, args.key)

      if (flags.json) {
        this.log(JSON.stringify({
          key: args.key,
          environment: args.environment,
          needsRotation: needs,
          ageMs: age,
          ageFormatted: age ? formatDuration(age) : 'never',
          maxAgeMs,
          maxAgeFormatted: formatDuration(maxAgeMs),
        }))
      } else {
        const ageStr = age ? formatDuration(age) + ' ago' : 'never rotated'
        if (needs) {
          this.log(`${chalk.yellow('⚠')} ${args.key} in ${args.environment} ${chalk.yellow('needs rotation')} (last: ${ageStr})`)
        } else {
          this.log(`${chalk.green('✓')} ${args.key} in ${args.environment} is fine (last: ${ageStr})`)
        }
      }
      storage.close()
      return
    }

    const env = ctx.configManager.getEnvironment(args.environment)
    if (!env) {
      this.error(`Environment not found: ${args.environment}`)
    }

    const keys = flags.batch
      ? flags.batch.split(',').map((s: any) => s.trim()).filter(Boolean)
      : [args.key]

    if (!flags.force && !flags.json) {
      this.log(`Preparing to rotate ${keys.length} key(s) in ${chalk.magenta(args.environment)}:`)
      for (const k of keys) this.log(`  - ${k}`)
      let yes = false
      try {
        await ux.prompt('Proceed with rotation? (y/n)')
        yes = true
      } catch {
        yes = false
      }
      if (!yes) {
        this.log('Aborted.')
        storage.close()
        return
      }
    }

    const records = await scheduler.rotateBatch(env, keys, {
      operator,
      verify: flags.verify,
      onNotify: flags.notify && ctx.config.notifications ? async (_msg) => {
        // handled below for consolidated notification
      } : undefined,
    })

    for (const r of records) {
      await storage.recordRotation(r)
    }

    const newRecords = scheduler.getAllRecords()
    for (const r of newRecords.slice(-records.length)) {
      const targetEnv = ctx.config.environments.find((e: any) => e.name === r.environment)
      if (targetEnv) {
        await storage.recordKeyValueChange(r.environment, r.key, undefined, undefined, r.operator, undefined)
      }
    }
    storage.close()

    if (flags.notify && ctx.config.notifications) {
      try {
        const dispatcher = new NotificationDispatcher(ctx.config.notifications)
        const successRecords = records.filter((r: any) => r.status === 'success')
        const changes: DiffItem[] = successRecords.map((r: any) => ({
          type: 'changed',
          key: r.key,
          path: `${r.environment}.${r.key}`,
        }))

        const message: NotificationMessage = {
          title: `Secrets Rotated (${successRecords.length}/${records.length})`,
          summary: `${operator} rotated ${keys.join(', ')} in ${args.environment}`,
          changes,
          operator,
          environment: args.environment,
          timestamp: Date.now(),
        }

        const results = await dispatcher.dispatch(message)
        if (!flags.json) {
          const success = results.filter((r: any) => r.success).length
          this.log(`${chalk.blue('ℹ')} Notifications: ${success}/${results.length} sent`)
        }
      } catch (error) {
        this.warn(`Notification failed: ${(error as Error).message}`)
      }
    }

    if (flags.json) {
      this.log(JSON.stringify(records, null, 2))
      return
    }

    const success = records.filter((r: any) => r.status === 'success').length
    const failed = records.filter((r: any) => r.status === 'failed').length
    this.log(`\nRotation complete: ${chalk.green(success)} success, ${chalk.red(failed)} failed`)

    for (const r of records) {
      const icon = r.status === 'success' ? chalk.green('✓') : chalk.red('✗')
      this.log(`  ${icon} ${r.key}${r.status === 'failed' && r.message ? ` - ${r.message}` : ''}`)
    }

    if (failed > 0) this.exit(1)
  }

  private async listHistory(
    scheduler: RotationScheduler,
    environment: string,
    key: string,
    storage: HistoryStorage,
    json: boolean
  ): Promise<void> {
    const history = await storage.getRotationHistory({ environment, key, limit: 50 })

    if (json) {
      this.log(JSON.stringify(history, null, 2))
      return
    }

    if (history.length === 0) {
      this.log('No rotation history found.')
      return
    }

    this.log(`📜 Rotation history for ${chalk.yellow(key)} in ${chalk.magenta(environment)} (last 50)`)
    this.log('─'.repeat(90))

    for (const r of history) {
      const icon = r.status === 'success' ? chalk.green('✓') : chalk.red('✗')
      this.log(
        `${icon} [${formatTimestamp(r.timestamp)}] ${chalk.gray(r.sourceType.padStart(10))} ${r.operator.padEnd(16)} ${r.status.toUpperCase()}` +
        (r.message ? ` - ${r.message}` : '')
      )
    }
  }

  private parseDuration(s: string): number {
    const match = s.match(/^(\d+)([smhdw])$/)
    if (!match) {
      try { return parseInt(s, 10) } catch { return 30 * 24 * 60 * 60 * 1000 }
    }
    const n = parseInt(match[1], 10)
    const unit = match[2]
    switch (unit) {
      case 's': return n * 1000
      case 'm': return n * 60 * 1000
      case 'h': return n * 60 * 60 * 1000
      case 'd': return n * 24 * 60 * 60 * 1000
      case 'w': return n * 7 * 24 * 60 * 60 * 1000
      default: return n * 1000
    }
  }
}
