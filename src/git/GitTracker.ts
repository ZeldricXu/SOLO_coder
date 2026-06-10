import * as fs from 'fs'
import * as path from 'path'
import { ConfigData, GitCommitRecord, KeyHistoryEntry, ConfigValue } from '../types'
import dayjs from 'dayjs'

export interface CommitOptions {
  authorName?: string
  authorEmail?: string
  operator?: string
  autoInit?: boolean
}

export interface FileSnapshot {
  [environment: string]: ConfigData
}

export class GitTracker {
  private repoPath: string
  private configDir: string
  private git: any = null

  constructor(repoPath: string) {
    this.repoPath = path.resolve(repoPath)
    this.configDir = path.join(this.repoPath, 'configs')
  }

  private async initGit(): Promise<void> {
    if (this.git) return

    const { default: simpleGit } = await import('simple-git')
    this.git = simpleGit(this.repoPath)

    if (!fs.existsSync(this.repoPath)) {
      fs.mkdirSync(this.repoPath, { recursive: true })
    }

    if (!fs.existsSync(this.configDir)) {
      fs.mkdirSync(this.configDir, { recursive: true })
    }

    const gitDir = path.join(this.repoPath, '.git')
    if (!fs.existsSync(gitDir)) {
      await this.git.init()
    }
  }

  async ensureInitialized(options: CommitOptions = {}): Promise<void> {
    await this.initGit()

    const hasCommits = await this.hasCommits()
    if (!hasCommits) {
      const readme = '# ConfigFlow Configurations\n\nThis directory is managed by ConfigFlow CLI.\n'
      fs.writeFileSync(path.join(this.repoPath, 'README.md'), readme)
      await this.git.add('.')
      await this.git.commit('init: initial config repository', [], {
        '--author': this.formatAuthor(options),
      })
    }
  }

  private async hasCommits(): Promise<boolean> {
    try {
      const log = await this.git.log(['-1'])
      return !!log.latest
    } catch {
      return false
    }
  }

  private formatAuthor(options: CommitOptions): string {
    const name = options.authorName || options.operator || process.env.USER || 'config-flow'
    const email = options.authorEmail || 'config-flow@local'
    return `${name} <${email}>`
  }

  saveEnvironmentSnapshot(environment: string, data: ConfigData): string {
    const filePath = path.join(this.configDir, `${environment}.json`)
    const content = JSON.stringify(data, null, 2) + '\n'
    fs.writeFileSync(filePath, content)
    return filePath
  }

  saveAllSnapshots(snapshot: FileSnapshot): string[] {
    const paths: string[] = []
    for (const [env, data] of Object.entries(snapshot)) {
      paths.push(this.saveEnvironmentSnapshot(env, data))
    }
    return paths
  }

  async loadEnvironmentSnapshot(environment: string, commitHash?: string): Promise<ConfigData | null> {
    if (commitHash) {
      return this.loadSnapshotAtCommit(environment, commitHash)
    }

    const filePath = path.join(this.configDir, `${environment}.json`)
    if (!fs.existsSync(filePath)) return null

    try {
      return JSON.parse(fs.readFileSync(filePath, 'utf-8')) as ConfigData
    } catch {
      return null
    }
  }

  private async loadSnapshotAtCommit(environment: string, commitHash: string): Promise<ConfigData | null> {
    await this.initGit()
    const relativePath = path.relative(this.repoPath, path.join(this.configDir, `${environment}.json`))

    try {
      const content = await this.git.show([`${commitHash}:${relativePath}`])
      return JSON.parse(content) as ConfigData
    } catch {
      return null
    }
  }

  async commitChanges(
    message: string,
    options: CommitOptions = {}
  ): Promise<GitCommitRecord | null> {
    await this.initGit()
    await this.ensureInitialized(options)

    const status = await this.git.status()
    if (status.files.length === 0) {
      return null
    }

    await this.git.add('.')

    const author = this.formatAuthor(options)
    const fullMessage = options.operator
      ? `[${options.operator}] ${message}`
      : message

    const result = await this.git.commit(fullMessage, [], {
      '--author': author,
    })

    if (!result.commit) return null

    return {
      hash: result.commit,
      author: author,
      timestamp: Date.now(),
      message: fullMessage,
      changes: status.files.map((f: { path: string }) => f.path),
    }
  }

  async log(options: {
    environment?: string
    key?: string
    since?: number
    until?: number
    limit?: number
  } = {}): Promise<GitCommitRecord[]> {
    await this.initGit()

    const logArgs: string[] = []
    if (options.limit) logArgs.push(`-n${options.limit}`)
    if (options.since) logArgs.push(`--since=${new Date(options.since).toISOString()}`)
    if (options.until) logArgs.push(`--until=${new Date(options.until).toISOString()}`)

    const relConfigDir = path.relative(this.repoPath, this.configDir)
    const pathFilter = options.environment
      ? path.join(relConfigDir, `${options.environment}.json`)
      : relConfigDir

    try {
      const log = await this.git.log([...logArgs, '--', pathFilter])
      return log.all.map((entry: { hash: string; author_name: string; author_email: string; date: string; message: string; diff?: { files: { file: string }[] } }) => ({
        hash: entry.hash,
        author: `${entry.author_name} <${entry.author_email}>`,
        timestamp: new Date(entry.date).getTime(),
        message: entry.message,
        changes: entry.diff?.files?.map((f) => f.file) || [],
      }))
    } catch {
      return []
    }
  }

  async getKeyHistory(environment: string, key: string, limit = 20): Promise<KeyHistoryEntry[]> {
    await this.initGit()

    const commits = await this.log({ environment, limit })
    const history: KeyHistoryEntry[] = []

    for (const commit of commits) {
      const snapshot = await this.loadSnapshotAtCommit(environment, commit.hash)
      if (!snapshot) continue

      const value = this.getValueByPath(snapshot, key)
      if (value === undefined) continue

      const last = history[history.length - 1]
      if (last && JSON.stringify(last.value) === JSON.stringify(value)) {
        continue
      }

      history.push({
        commitHash: commit.hash,
        timestamp: commit.timestamp,
        author: commit.author,
        message: commit.message,
        value,
      })
    }

    return history.reverse()
  }

  async diffCommits(
    commitA: string,
    commitB: string,
    environment?: string
  ): Promise<{ file: string; changes: string }[]> {
    await this.initGit()

    const diffArgs = [`${commitA}..${commitB}`]
    const relConfigDir = path.relative(this.repoPath, this.configDir)
    if (environment) {
      diffArgs.push('--', path.join(relConfigDir, `${environment}.json`))
    } else {
      diffArgs.push('--', relConfigDir)
    }

    const diffOutput = await this.git.diff(diffArgs)

    const fileDiffs: { file: string; changes: string }[] = []
    const sections = diffOutput.split(/^diff --git /m).slice(1)

    for (const section of sections) {
      const lines = section.split('\n')
      const fileMatch = lines[0]?.match(/b\/(.+)$/)
      const file = fileMatch ? fileMatch[1] : 'unknown'
      fileDiffs.push({
        file,
        changes: section,
      })
    }

    return fileDiffs
  }

  async getLastCommitHash(environment?: string): Promise<string | null> {
    const commits = await this.log({ environment, limit: 1 })
    return commits[0]?.hash || null
  }

  async formatCommitRecord(record: GitCommitRecord): Promise<string> {
    const lines: string[] = []
    lines.push(`commit ${record.hash}`)
    lines.push(`Author: ${record.author}`)
    lines.push(`Date:   ${dayjs(record.timestamp).format('YYYY-MM-DD HH:mm:ss ZZ')}`)
    lines.push('')
    lines.push(`    ${record.message}`)
    if (record.changes.length > 0) {
      lines.push('')
      lines.push('    Changes:')
      for (const c of record.changes) {
        lines.push(`      - ${c}`)
      }
    }
    return lines.join('\n')
  }

  async formatKeyHistory(history: KeyHistoryEntry[], key: string, environment: string): Promise<string> {
    const lines: string[] = []
    lines.push(`History for key "${key}" in environment "${environment}"`)
    lines.push('='.repeat(80))

    if (history.length === 0) {
      lines.push('(no history found)')
      return lines.join('\n')
    }

    for (const entry of history) {
      lines.push('')
      lines.push(`commit ${entry.commitHash}`)
      lines.push(`Author: ${entry.author}`)
      lines.push(`Date:   ${dayjs(entry.timestamp).format('YYYY-MM-DD HH:mm:ss ZZ')}`)
      lines.push('')
      lines.push(`    ${entry.message}`)
      lines.push('')
      lines.push(`    Value: ${JSON.stringify(entry.value, null, 2).split('\n').join('\n    ')}`)
    }

    return lines.join('\n')
  }

  private getValueByPath(data: ConfigData, pathStr: string): ConfigValue | undefined {
    const parts = pathStr.split('.')
    let current: ConfigValue = data

    for (const part of parts) {
      if (current === null || current === undefined) return undefined
      if (typeof current === 'object' && !Array.isArray(current)) {
        current = (current as ConfigData)[part]
      } else {
        return undefined
      }
    }

    return current
  }

  getRepoPath(): string {
    return this.repoPath
  }

  getConfigDir(): string {
    return this.configDir
  }
}
