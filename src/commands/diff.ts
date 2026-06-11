import { Command, Flags, Args } from '@oclif/core'
import { loadContext } from './env/list'
import { DiffEngine } from '../engine/DiffEngine'
import { formatDiffReport, formatCascadeDiffReport } from '../utils/formatters'
import { HistoryStorage } from '../storage/HistoryStorage'
import chalk from 'chalk'
import * as fs from 'fs'

export default class DiffCommand extends Command {
  static description = 'Show configuration differences between two environments'
  static aliases = ['compare', 'drift']

  static args = {
    envA: Args.string({ description: 'First environment name (or comma-separated chain with --cascade)', required: true }),
    envB: Args.string({ description: 'Second environment name', required: false }),
  }

  static flags = {
    config: Flags.string({ char: 'c', description: 'Path to config file' }),
    type: Flags.string({ char: 't', description: 'Filter by type: added|removed|changed', options: ['added', 'removed', 'changed'] }),
    key: Flags.string({ char: 'k', description: 'Filter keys by regex pattern' }),
    ignore: Flags.string({ char: 'i', description: 'Ignore keys by pattern (comma-separated, supports *)', multiple: true }),
    json: Flags.boolean({ description: 'Output as JSON' }),
    noColor: Flags.boolean({ description: 'Disable colored output' }),
    noHistory: Flags.boolean({ description: 'Do not record diff history' }),
    output: Flags.string({ char: 'o', description: 'Write diff to file' }),
    failOnDrift: Flags.boolean({ description: 'Exit with error code if drift detected' }),
    cascade: Flags.boolean({ description: 'Cascade mode: compare environments in chain' }),
  }

  async run(): Promise<void> {
    const { args, flags } = await this.parse(DiffCommand)
    const ctx = await loadContext(flags.config)

    if (flags.cascade) {
      await this.runCascade(args.envA, ctx, flags)
    } else {
      if (!args.envB) this.error('Second environment is required in non-cascade mode. Provide envB or use --cascade.')
      await this.runStandard(args.envA, args.envB, ctx, flags)
    }
  }

  private async runCascade(envChain: string, ctx: any, flags: any): Promise<void> {
    const envNames = envChain.split(',').map((s: string) => s.trim()).filter(Boolean)
    if (envNames.length < 2) this.error('Cascade mode requires at least 2 environments (comma-separated), e.g., dev,staging,prod')

    const envsData = new Map<string, any>()
    for (const name of envNames) {
      const env = ctx.configManager.getEnvironment(name)
      if (!env) this.error(`Environment not found: ${name}`)
      const data = await env.loadAll()
      envsData.set(name, data)
    }

    const diffEngine = new DiffEngine()
    const report = diffEngine.cascadeCompare(envsData)

    let output: string
    if (flags.json) {
      output = JSON.stringify(report, null, 2)
    } else {
      output = formatCascadeDiffReport(report, !flags.noColor)
    }

    this.log(output)

    if (flags.output) {
      fs.writeFileSync(flags.output, output)
      if (!flags.json) {
        this.log(`\n${chalk.blue('ℹ')} Cascade diff written to: ${flags.output}`)
      }
    }

    if (!flags.json) {
      if (report.summary.driftRisk > 0 || report.summary.changed > 0) {
        this.log(`\n${chalk.yellow('⚠')} Cascade drift: ${report.summary.driftRisk} drift-risk, ${report.summary.changed} changed`)
      } else {
        this.log(`\n${chalk.green('✓')} All keys consistent across cascade chain`)
      }
    }

    if (flags.failOnDrift && (report.summary.driftRisk > 0 || report.summary.changed > 0)) {
      this.exit(1)
    }
  }

  private async runStandard(envAName: string, envBName: string, ctx: any, flags: any): Promise<void> {
    const envA = ctx.configManager.getEnvironment(envAName)
    const envB = ctx.configManager.getEnvironment(envBName)

    if (!envA) this.error(`Environment not found: ${envAName}`)
    if (!envB) this.error(`Environment not found: ${envBName}`)

    const dataA = await envA.loadAll()
    const dataB = await envB.loadAll()

    const diffEngine = new DiffEngine()
    const report = diffEngine.compare(dataA, dataB, envAName, envBName)

    if (flags.type) {
      report.diffs = diffEngine.filterDiffs(report.diffs, { type: flags.type as any })
    }

    if (flags.key) {
      report.diffs = diffEngine.filterDiffs(report.diffs, { keyPattern: flags.key })
    }

    if (flags.ignore && flags.ignore.length > 0) {
      const ignoreList = flags.ignore.join(',').split(',').map((s: any) => s.trim()).filter(Boolean)
      const driftInfo = diffEngine.generateDriftReport(report, ignoreList)
      report.diffs = driftInfo.criticalDiffs
      report.summary = {
        added: driftInfo.criticalDiffs.filter((d) => d.type === 'added').length,
        removed: driftInfo.criticalDiffs.filter((d) => d.type === 'removed').length,
        changed: driftInfo.criticalDiffs.filter((d) => d.type === 'changed').length,
        total: driftInfo.criticalDiffs.length,
      }
    }

    const storage = flags.noHistory ? null : new HistoryStorage(ctx.config.storagePath)
    if (storage) {
      await storage.recordDiff(report)
      storage.close()
    }

    let output: string
    if (flags.json) {
      output = JSON.stringify(report, null, 2)
    } else {
      output = formatDiffReport(report, !flags.noColor)
    }

    this.log(output)

    if (flags.output) {
      fs.writeFileSync(flags.output, output)
      if (!flags.json) {
        this.log(`\n${chalk.blue('ℹ')} Diff written to: ${flags.output}`)
      }
    }

    if (!flags.json) {
      const hasDrift = report.diffs.length > 0
      if (hasDrift) {
        this.log(`\n${chalk.yellow('⚠')} Drift detected: ${report.diffs.length} difference(s)`)
      } else {
        this.log(`\n${chalk.green('✓')} No drift detected between ${envAName} and ${envBName}`)
      }

      if (flags.ignore && flags.ignore.length > 0) {
        this.log(`${chalk.blue('ℹ')} Ignored patterns: ${flags.ignore.join(', ')}`)
      }
    }

    if (flags.failOnDrift && report.diffs.length > 0) {
      this.exit(1)
    }
  }
}
