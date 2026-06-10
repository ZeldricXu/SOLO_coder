import { Command, Flags, Args } from '@oclif/core'
import { loadContext } from './env/list'
import { DiffEngine } from '../engine/DiffEngine'
import { formatDiffReport } from '../utils/formatters'
import { HistoryStorage } from '../storage/HistoryStorage'
import chalk from 'chalk'
import * as fs from 'fs'

export default class DiffCommand extends Command {
  static description = 'Show configuration differences between two environments'
  static aliases = ['compare', 'drift']

  static args = {
    envA: Args.string({ description: 'First environment name', required: true }),
    envB: Args.string({ description: 'Second environment name', required: true }),
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
  }

  async run(): Promise<void> {
    const { args, flags } = await this.parse(DiffCommand)
    const ctx = await loadContext(flags.config)

    const envA = ctx.configManager.getEnvironment(args.envA)
    const envB = ctx.configManager.getEnvironment(args.envB)

    if (!envA) this.error(`Environment not found: ${args.envA}`)
    if (!envB) this.error(`Environment not found: ${args.envB}`)

    const dataA = await envA.loadAll()
    const dataB = await envB.loadAll()

    const diffEngine = new DiffEngine()
    const report = diffEngine.compare(dataA, dataB, args.envA, args.envB)

    if (flags.type) {
      report.diffs = diffEngine.filterDiffs(report.diffs, { type: flags.type as any })
    }

    if (flags.key) {
      report.diffs = diffEngine.filterDiffs(report.diffs, { keyPattern: flags.key })
    }

    if (flags.ignore && flags.ignore.length > 0) {
      const ignoreList = flags.ignore.join(',').split(',').map((s) => s.trim()).filter(Boolean)
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
        this.log(`\n${chalk.green('✓')} No drift detected between ${args.envA} and ${args.envB}`)
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
