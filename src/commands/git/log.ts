import { Command, Flags, Args } from '@oclif/core'
import { loadContext } from '../env/list'
import { GitTracker } from '../../git/GitTracker'
import { formatTimestamp } from '../../utils/formatters'
import chalk from 'chalk'

export default class GitLogCommand extends Command {
  static description = 'Show configuration git history'
  static aliases = ['git:log']

  static args = {
    key: Args.string({ description: 'Filter by key (dot notation)' }),
  }

  static flags = {
    config: Flags.string({ char: 'c', description: 'Path to config file' }),
    environment: Flags.string({ char: 'e', description: 'Filter by environment' }),
    since: Flags.string({ description: 'Show commits since (ISO date or relative like 7d)' }),
    until: Flags.string({ description: 'Show commits until' }),
    limit: Flags.integer({ char: 'n', default: 20, description: 'Number of commits to show' }),
    json: Flags.boolean({ description: 'Output as JSON' }),
  }

  async run(): Promise<void> {
    const { args, flags } = await this.parse(GitLogCommand)
    const ctx = await loadContext(flags.config)

    const git = new GitTracker(ctx.config.gitRepoPath)
    await git.ensureInitialized()

    if (args.key) {
      const envName = flags.environment || ctx.configManager.listEnvironments()[0]
      if (!envName) {
        this.error('No environment configured. Specify --environment')
      }
      const history = await git.getKeyHistory(envName, args.key, flags.limit)

      if (flags.json) {
        this.log(JSON.stringify(history, null, 2))
      } else {
        this.log(await git.formatKeyHistory(history, args.key, envName))
      }
      return
    }

    const log = await git.log({
      environment: flags.environment,
      limit: flags.limit,
    })

    if (flags.json) {
      this.log(JSON.stringify(log, null, 2))
      return
    }

    if (log.length === 0) {
      this.log('No commits yet.')
      return
    }

    this.log(`📜 Commit History (last ${log.length})`)
    this.log('─'.repeat(90))

    for (const entry of log) {
      this.log(`\n${chalk.yellow('commit')} ${entry.hash}`)
      this.log(`${chalk.blue('Author:')} ${entry.author}`)
      this.log(`${chalk.blue('Date:')}   ${formatTimestamp(entry.timestamp)}`)
      this.log('')
      this.log(`    ${entry.message}`)
      if (entry.changes.length > 0) {
        this.log('')
        for (const c of entry.changes) {
          this.log(`    ${chalk.magenta('M')} ${c}`)
        }
      }
    }
  }
}
