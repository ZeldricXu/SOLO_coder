import { Command, Flags, Args, ux } from '@oclif/core'
import { loadContext } from './env/list'
import { SyncPipeline, BatchSyncResult } from '../sync/SyncPipeline'
import { SchemaValidator, SchemaConfig } from '../schemas/SchemaValidator'
import { HistoryStorage } from '../storage/HistoryStorage'
import { NotificationDispatcher } from '../notifications/NotificationDispatcher'
import { GitTracker } from '../git/GitTracker'
import { DiffItem, SyncItem, NotificationMessage } from '../types'
import chalk from 'chalk'
import * as fs from 'fs'

export default class SyncCommand extends Command {
  static description = 'Sync configuration between environments'
  static aliases = ['sync:push']

  static args = {
    key: Args.string({ description: 'Configuration key to sync (dot notation)', required: true }),
    source: Args.string({ description: 'Source environment', required: true }),
    targets: Args.string({ description: 'Target environments (comma-separated)', required: true }),
  }

  static flags = {
    config: Flags.string({ char: 'c', description: 'Path to config file' }),
    dryRun: Flags.boolean({ char: 'n', default: false, description: 'Preview changes without applying' }),
    validate: Flags.boolean({ description: 'Validate against schema before syncing' }),
    verify: Flags.boolean({ default: true, description: 'Verify values after syncing' }),
    schema: Flags.string({ description: 'Path to schema (defaults to configured path)' }),
    gitCommit: Flags.boolean({ default: true, description: 'Commit changes to git' }),
    notify: Flags.boolean({ description: 'Send notifications about the change' }),
    operator: Flags.string({ char: 'u', description: 'Operator name (for audit log)' }),
    json: Flags.boolean({ description: 'Output as JSON' }),
    force: Flags.boolean({ char: 'f', description: 'Skip confirmation prompt' }),
  }

  async run(): Promise<void> {
    const { args, flags } = await this.parse(SyncCommand)
    const ctx = await loadContext(flags.config)
    const operator = flags.operator || ctx.config.defaultOperator || 'unknown'

    const targets = args.targets.split(',').map((s: any) => s.trim()).filter(Boolean)
    const syncItem: SyncItem = {
      key: args.key,
      sourceEnvironment: args.source,
      targetEnvironments: targets,
    }

    const syncPipeline = new SyncPipeline(ctx.configManager)

    let validator: SchemaValidator | undefined
    if (flags.validate) {
      const schemaPath = flags.schema || ctx.config.schemaPath
      if (fs.existsSync(schemaPath)) {
        const rawSchema = JSON.parse(fs.readFileSync(schemaPath, 'utf-8')) as SchemaConfig
        validator = new SchemaValidator(rawSchema)
      } else {
        this.warn(`Schema not found at ${schemaPath}, skipping validation`)
      }
    }

    const previews = await syncPipeline.previewSync(syncItem)
    const nonSkip = previews.filter((p: any) => p.action !== 'skip')

    if (!flags.json) {
      this.log(syncPipeline.formatPreviews(previews))
      this.log('')
    }

    if (nonSkip.length === 0) {
      if (!flags.json) this.log(chalk.green('✓ No changes needed - all targets are in sync'))
      return
    }

    if (!flags.dryRun && !flags.force && !flags.json) {
      let yesno = false
      try {
        await ux.prompt(`Apply ${nonSkip.length} change(s) to ${targets.length} environment(s)? (y/n)`)
        yesno = true
      } catch {
        yesno = false
      }
      if (!yesno) {
        this.log('Aborted.')
        return
      }
    }

    const sourceEnv = ctx.configManager.getEnvironment(args.source)
    if (!sourceEnv) this.error(`Source environment not found: ${args.source}`)
    const oldValue = await sourceEnv.get(args.key)

    const result: BatchSyncResult = await syncPipeline.executeBatch([syncItem], {
      dryRun: flags.dryRun,
      validateBefore: flags.validate,
      verifyAfter: flags.verify,
      validator,
    })

    const storage = new HistoryStorage(ctx.config.storagePath)
    try {
      for (const r of result.results) {
        await storage.recordSync(syncItem, [r], flags.dryRun, operator)
      }

      if (!flags.dryRun) {
        const targetEnvConfigs = ctx.config.environments.filter((e: any) => targets.includes(e.name))
        for (const targetConfig of targetEnvConfigs) {
          const targetEnv = ctx.configManager.getEnvironment(targetConfig.name)
          if (targetEnv) {
            const newValue = await targetEnv.get(args.key)
            await storage.recordKeyValueChange(targetConfig.name, args.key, oldValue, newValue, operator)
          }
        }
      }
    } finally {
      storage.close()
    }

    if (flags.gitCommit && !flags.dryRun) {
      try {
        const git = new GitTracker(ctx.config.gitRepoPath)
        await git.ensureInitialized({ operator })

        const snapshot: Record<string, any> = {}
        for (const envName of [args.source, ...targets]) {
          const env = ctx.configManager.getEnvironment(envName)
          if (env) {
            snapshot[envName] = await env.loadAll()
          }
        }
        git.saveAllSnapshots(snapshot)

        const changes: DiffItem[] = []
        for (const target of targets) {
          const newVal = await ctx.configManager.getEnvironment(target)?.get(args.key)
          changes.push({
            type: oldValue === undefined ? 'added' : 'changed',
            key: args.key,
            path: `${target}.${args.key}`,
            before: oldValue,
            after: newVal,
          })
        }

        const actionStr = flags.dryRun ? '(dry-run) ' : ''
        const commit = await git.commitChanges(
          `${actionStr}sync ${args.key} from ${args.source} to ${targets.join(',')}`,
          { operator }
        )

        if (commit) {
          if (!flags.json) {
            this.log(`\n${chalk.green('✓')} Committed to git: ${chalk.yellow(commit.hash.slice(0, 8))}`)
          }
        }
      } catch (error) {
        this.warn(`Git commit failed: ${(error as Error).message}`)
      }
    }

    if (flags.notify && !flags.dryRun && ctx.config.notifications) {
      try {
        const dispatcher = new NotificationDispatcher(ctx.config.notifications)

        const diffs: DiffItem[] = []
        for (const r of result.results) {
          if (r.status === 'success') {
            diffs.push({
              type: oldValue === undefined ? 'added' : 'changed',
              key: args.key,
              path: `${r.targetEnvironment}.${args.key}`,
              before: oldValue,
              after: await ctx.configManager.getEnvironment(r.targetEnvironment)?.get(args.key),
            })
          }
        }

        const message: NotificationMessage = {
          title: `Config Synced: ${args.key}`,
          summary: `${operator} synced ${args.key} from ${args.source} to ${targets.join(', ')}`,
          changes: diffs,
          operator,
          environment: args.source,
          timestamp: Date.now(),
        }

        const dispatchResults = await dispatcher.dispatch(message)
        if (!flags.json) {
          const success = dispatchResults.filter((r: any) => r.success).length
          this.log(`${chalk.blue('ℹ')} Notifications: ${success}/${dispatchResults.length} sent`)
        }
      } catch (error) {
        this.warn(`Notification failed: ${(error as Error).message}`)
      }
    }

    if (flags.json) {
      this.log(JSON.stringify(result, null, 2))
      return
    }

    this.log('')
    this.log(`Sync ${flags.dryRun ? 'Preview' : 'Results'}: ${chalk.green(result.summary.success)} success, ${chalk.red(result.summary.failed)} failed, ${chalk.gray(result.summary.skipped)} skipped`)
    if (flags.verify && !flags.dryRun) {
      this.log(`Verified: ${result.summary.verified}/${result.summary.total}`)
    }

    for (const r of result.results) {
      const icon = r.status === 'success' ? chalk.green('✓') : chalk.red('✗')
      const verifyStr = flags.verify && !flags.dryRun ? (r.verified ? ' [verified]' : ' [UNVERIFIED]') : ''
      this.log(`  ${icon} ${r.targetEnvironment}${verifyStr}${r.message ? ` - ${r.message}` : ''}`)
    }

    if (result.summary.failed > 0 || (flags.verify && !flags.dryRun && result.summary.verified < result.summary.total)) {
      this.exit(1)
    }
  }
}
