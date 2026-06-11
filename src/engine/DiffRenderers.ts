import { DiffItem, DiffReport, ConfigValue } from '../types'
import chalk from 'chalk'
import Table = require('cli-table3')

export interface DiffRenderOptions {
  useColors?: boolean
  truncateLongValues?: boolean
  maxValueLength?: number
  includeSummary?: boolean
}

const DEFAULT_RENDER_OPTIONS: Required<DiffRenderOptions> = {
  useColors: true,
  truncateLongValues: true,
  maxValueLength: 100,
  includeSummary: true,
}

export function renderTerminalTable(
  diffs: DiffItem[],
  options: DiffRenderOptions = {},
): string {
  const opts = { ...DEFAULT_RENDER_OPTIONS, ...options }
  const table = new Table({
    head: [
      opts.useColors ? chalk.cyan('Type') : 'Type',
      opts.useColors ? chalk.cyan('Key') : 'Key',
      opts.useColors ? chalk.cyan('Before') : 'Before',
      opts.useColors ? chalk.cyan('After') : 'After',
      opts.useColors ? chalk.cyan('Δ%') : 'Δ%',
    ],
    style: { head: [], border: [] },
    wordWrap: false,
  })

  for (const diff of diffs) {
    const typeCol = formatType(diff.type, opts.useColors)
    const beforeCol = formatValue(diff.before, opts)
    const afterCol = formatValue(diff.after, opts)
    const deltaCol = diff.changePercent
      ? `${diff.changePercent >= 0 ? '+' : ''}${diff.changePercent.toFixed(1)}%`
      : '—'

    table.push([typeCol, diff.key, beforeCol, afterCol, deltaCol])
  }

  let output = ''
  if (opts.includeSummary) {
    const added = diffs.filter((d) => d.type === 'added').length
    const removed = diffs.filter((d) => d.type === 'removed').length
    const changed = diffs.filter((d) => d.type === 'changed').length
    output += `Summary: ${added} added, ${removed} removed, ${changed} changed (${diffs.length} total)\n`
  }
  output += table.toString()
  return output
}

function formatType(type: string, colors: boolean): string {
  if (!colors) return type.toUpperCase()
  switch (type) {
    case 'added': return chalk.green('+ ADDED')
    case 'removed': return chalk.red('- REMOVED')
    case 'changed': return chalk.yellow('~ CHANGED')
    default: return type
  }
}

function formatValue(v: ConfigValue | undefined, opts: Required<DiffRenderOptions>): string {
  if (v === undefined) return '—'
  let str = typeof v === 'string' ? v : JSON.stringify(v)
  if (opts.truncateLongValues && str.length > opts.maxValueLength) {
    str = str.slice(0, opts.maxValueLength - 3) + '...'
  }
  return str
}

export function renderJson(
  diffs: DiffItem[],
  pretty: boolean = true,
): string {
  const json = diffs.map((d) => ({
    type: d.type,
    key: d.key,
    path: d.path,
    ...(d.before !== undefined ? { before: d.before } : {}),
    ...(d.after !== undefined ? { after: d.after } : {}),
    ...(d.changePercent !== undefined ? { changePercent: d.changePercent } : {}),
  }))
  return pretty ? JSON.stringify(json, null, 2) : JSON.stringify(json)
}

export function renderCsv(diffs: DiffItem[]): string {
  const escapeCsv = (v: any): string => {
    if (v === undefined || v === null) return ''
    const str = typeof v === 'string' ? v : JSON.stringify(v)
    if (str.includes(',') || str.includes('"') || str.includes('\n')) {
      return `"${str.replace(/"/g, '""')}"`
    }
    return str
  }

  const header = ['type', 'key', 'path', 'before', 'after', 'change_percent']
  const lines = [header.join(',')]

  for (const d of diffs) {
    lines.push([
      d.type,
      escapeCsv(d.key),
      escapeCsv(d.path),
      escapeCsv(d.before),
      escapeCsv(d.after),
      d.changePercent !== undefined ? d.changePercent.toFixed(2) : '',
    ].join(','))
  }

  return lines.join('\n')
}

export type DiffFormat = 'terminal' | 'json' | 'csv'

export function renderDiffReport(
  report: DiffReport | DiffItem[],
  format: DiffFormat = 'terminal',
  options?: DiffRenderOptions,
): string {
  const diffs: DiffItem[] = Array.isArray(report) ? report : report.diffs

  switch (format) {
    case 'json':
      return renderJson(diffs)
    case 'csv':
      return renderCsv(diffs)
    case 'terminal':
    default:
      return renderTerminalTable(diffs, options)
  }
}
