import { ValidationReport, ValidationError, DiffReport, DiffItem } from '../types'
import chalk from 'chalk'
import Table = require('cli-table3')

export function formatValidationReport(report: ValidationReport, useColors = true): string {
  const lines: string[] = []
  const status = report.valid ? chalk.green('VALID') : chalk.red('INVALID')

  lines.push(`Validation Report for environment: ${report.environment}`)
  lines.push(`Status: ${useColors ? status : (report.valid ? 'VALID' : 'INVALID')}`)
  lines.push(`Timestamp: ${new Date(report.timestamp).toISOString()}`)

  if (report.valid) {
    lines.push(chalk.green('All configuration values pass schema validation.'))
    return lines.join('\n')
  }

  lines.push('')
  lines.push(`Found ${report.errors.length} validation error(s):`)
  lines.push('')

  try {
    const table = new Table({
      head: useColors
        ? [chalk.cyan('Key'), chalk.cyan('Expected'), chalk.cyan('Actual'), chalk.cyan('Message')]
        : ['Key', 'Expected', 'Actual', 'Message'],
      colWidths: [30, 25, 25, 40],
      wordWrap: true,
    })

    for (const err of report.errors) {
      table.push([
        err.key,
        err.expected,
        err.actual,
        err.message,
      ])
    }

    lines.push(table.toString())
  } catch {
    for (const err of report.errors) {
      lines.push(`  [${err.key}] Expected: ${err.expected}, Got: ${err.actual} - ${err.message}`)
    }
  }

  return lines.join('\n')
}

export function formatValidationErrors(errors: ValidationError[], useColors = true): string {
  if (errors.length === 0) return 'No errors'

  const lines: string[] = []

  for (const err of errors) {
    const env = useColors ? chalk.magenta(`[${err.environment}]`) : `[${err.environment}]`
    const key = useColors ? chalk.yellow(err.key) : err.key

    lines.push(`${env} ${key}: ${err.message}`)
    lines.push(`    Expected: ${err.expected}`)
    lines.push(`    Actual  : ${err.actual}`)
  }

  return lines.join('\n')
}

export function formatDiffReport(report: DiffReport, useColors = true): string {
  const lines: string[] = []

  lines.push(`Diff: ${report.environmentA} → ${report.environmentB}`)
  lines.push(`Added: ${report.summary.added} | Removed: ${report.summary.removed} | Changed: ${report.summary.changed}`)
  lines.push('')

  if (report.diffs.length === 0) {
    lines.push(useColors ? chalk.green('No differences found.') : 'No differences found.')
    return lines.join('\n')
  }

  for (const diff of report.diffs) {
    lines.push(formatDiffItem(diff, useColors))
  }

  return lines.join('\n')
}

export function formatDiffItem(diff: DiffItem, useColors = true): string {
  let line = ''
  const prefix = diff.type === 'added' ? '+' : diff.type === 'removed' ? '-' : '~'
  const colorFn = diff.type === 'added' ? chalk.green : diff.type === 'removed' ? chalk.red : chalk.yellow

  if (diff.type === 'added') {
    line = `${prefix} ${diff.path} = ${formatValue(diff.after)}`
  } else if (diff.type === 'removed') {
    line = `${prefix} ${diff.path} = ${formatValue(diff.before)}`
  } else {
    const before = formatValue(diff.before)
    const after = formatValue(diff.after)
    const pct = diff.changePercent !== undefined
      ? ` (${diff.changePercent > 0 ? '+' : ''}${diff.changePercent}%)`
      : ''
    line = `${prefix} ${diff.path}: ${before} → ${after}${pct}`
  }

  return useColors ? colorFn(line) : line
}

export function formatValue(v: unknown): string {
  if (v === undefined) return 'undefined'
  if (v === null) return 'null'
  if (typeof v === 'string') return `"${v}"`
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

export function formatByteSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toFixed(2) + ' ' + sizes[i]
}

export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  if (ms < 3600000) return `${Math.floor(ms / 60000)}m ${Math.floor((ms % 60000) / 1000)}s`
  return `${Math.floor(ms / 3600000)}h ${Math.floor((ms % 3600000) / 60000)}m`
}

export function formatTimestamp(ts: number): string {
  const date = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function truncate(str: string, maxLen: number): string {
  if (str.length <= maxLen) return str
  return str.slice(0, maxLen - 3) + '...'
}

export function formatKeyValueTable(rows: { key: string; value: string }[], useColors = true): string {
  try {
    const table = new Table({
      head: useColors ? [chalk.cyan('Key'), chalk.cyan('Value')] : ['Key', 'Value'],
      colWidths: [35, 65],
      wordWrap: true,
    })

    for (const row of rows) {
      table.push([row.key, row.value])
    }

    return table.toString()
  } catch {
    return rows.map((r) => `${r.key}: ${r.value}`).join('\n')
  }
}
