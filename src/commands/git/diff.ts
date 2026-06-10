import { Command, Flags, Args } from '@oclif/core'
import { loadContext } from '../env/list'
import { GitTracker } from '../../git/GitTracker'
import chalk from 'chalk'
import { DiffEngine } from '../../engine/DiffEngine'
import { ConfigData } from '../../types'
import { formatDiffReport } from '../../utils/formatters'

export default class GitDiffCommand extends Command {
  static description = 'Show diff between two commits'
  static aliases = ['git:compare']

  static args = {
    commitA: Args.string({ description: 'First commit hash (defaults to previous)', required: false }),
    commitB: Args.string({ description: 'Second commit hash (defaults to HEAD)', required: false }),
  }

  static flags = {
    config: Flags.string({ char: 'c', description: 'Path to config file' }),
    environment: Flags.string({ char: 'e', description: 'Filter by environment' }),
    key: Flags.string({ char: 'k', description: 'Filter by key' }),
    noColor: Flags.boolean({ description: 'No colored output' }),
    json: Flags.boolean({ description: 'Output as JSON' }),
  }

  async run(): Promise<void> {
    const { args, flags } = await this.parse(GitDiffCommand)
    const ctx = await loadContext(flags.config)

    const git = new GitTracker(ctx.config.gitRepoPath)
    await git.ensureInitialized()

    const log = await git.log({ environment: flags.environment, limit: 2 })

    if (log.length < 2 && !args.commitA) {
      this.log('Need at least 2 commits to compare.')
      return
    }

    const commitA = args.commitA || log[1]?.hash
    const commitB = args.commitB || log[0]?.hash

    if (!commitA || !commitB) {
      this.error('Could not determine commits to compare')
    }

    const environments = flags.environment
      ? [flags.environment]
      : ctx.configManager.listEnvironments()

    const allDiffs: { environment: string; diffReport: ReturnType<DiffEngine['compare']> }[] = []
    const diffEngine = new DiffEngine()

    for (const envName of environments) {
      const dataA = (await git.loadEnvironmentSnapshot(envName, commitA)) as ConfigData | null
      const dataB = (await git.loadEnvironmentSnapshot(envName, commitB)) as ConfigData | null

      if (!dataA || !dataB) continue

      const report = diffEngine.compare(dataA, dataB, `${envName}@${commitA.slice(0, 7)}`, `${envName}@${commitB.slice(0, 7)}`)

      if (flags.key) {
        report.diffs = diffEngine.filterDiffs(report.diffs, { keyPattern: flags.key })
      }

      allDiffs.push({ environment: envName, diffReport: report })
    }

    if (flags.json) {
      this.log(JSON.stringify(allDiffs, null, 2))
      return
    }

    this.log(`🔍 Diff: ${chalk.yellow(commitA.slice(0, 7))} → ${chalk.yellow(commitB.slice(0, 7))}`)
    this.log('═'.repeat(90))

    for (const { environment, diffReport } of allDiffs) {
      this.log(`\n${chalk.magenta('Environment:')} ${environment}`)
      this.log('─'.repeat(90))
      this.log(formatDiffReport(diffReport, !flags.noColor))
    }

    const gitDiffs = await git.diffCommits(commitA, commitB, flags.environment)
    if (gitDiffs.length > 0) {
      this.log(`\n${chalk.gray('Raw git diff:')}`)
      for (const gd of gitDiffs) {
        this.log(`  ${chalk.cyan(gd.file)}`)
      }
    }
  }
}
