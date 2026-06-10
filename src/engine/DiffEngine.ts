import { ConfigData, ConfigValue, DiffItem, DiffReport, DiffType } from '../types'
import chalk from 'chalk'

export class DiffEngine {
  compare(dataA: ConfigData, dataB: ConfigData, environmentA: string, environmentB: string): DiffReport {
    const diffs: DiffItem[] = []

    this.traverseAndCompare(dataA, dataB, '', diffs)

    const summary = {
      added: diffs.filter((d) => d.type === 'added').length,
      removed: diffs.filter((d) => d.type === 'removed').length,
      changed: diffs.filter((d) => d.type === 'changed').length,
      total: diffs.length,
    }

    return {
      environmentA,
      environmentB,
      diffs,
      summary,
      timestamp: Date.now(),
    }
  }

  private traverseAndCompare(
    a: ConfigValue,
    b: ConfigValue,
    path: string,
    diffs: DiffItem[]
  ): void {
    if (a === undefined && b === undefined) return

    if (a === undefined && b !== undefined) {
      diffs.push(this.createDiff('added', path, b, undefined))
      return
    }

    if (a !== undefined && b === undefined) {
      diffs.push(this.createDiff('removed', path, undefined, a))
      return
    }

    if (typeof a !== typeof b) {
      diffs.push(this.createDiff('changed', path, b, a))
      return
    }

    if (Array.isArray(a) && Array.isArray(b)) {
      this.compareArrays(a, b, path, diffs)
      return
    }

    if (
      a !== null &&
      typeof a === 'object' &&
      !Array.isArray(a) &&
      b !== null &&
      typeof b === 'object' &&
      !Array.isArray(b)
    ) {
      this.compareObjects(a as ConfigData, b as ConfigData, path, diffs)
      return
    }

    if (a !== b) {
      diffs.push(this.createDiff('changed', path, b, a))
    }
  }

  private compareObjects(
    a: ConfigData,
    b: ConfigData,
    path: string,
    diffs: DiffItem[]
  ): void {
    const allKeys = new Set([...Object.keys(a), ...Object.keys(b)])

    for (const key of allKeys) {
      const newPath = path ? `${path}.${key}` : key
      this.traverseAndCompare(a[key], b[key], newPath, diffs)
    }
  }

  private compareArrays(
    a: ConfigValue[],
    b: ConfigValue[],
    path: string,
    diffs: DiffItem[]
  ): void {
    const maxLength = Math.max(a.length, b.length)

    for (let i = 0; i < maxLength; i++) {
      const newPath = `${path}[${i}]`
      this.traverseAndCompare(a[i], b[i], newPath, diffs)
    }
  }

  private createDiff(type: DiffType, path: string, after?: ConfigValue, before?: ConfigValue): DiffItem {
    const key = path.split('.').pop() || path
    const diff: DiffItem = {
      type,
      key,
      path,
    }

    if (type === 'added') {
      diff.after = after
    } else if (type === 'removed') {
      diff.before = before
    } else {
      diff.before = before
      diff.after = after
      diff.changePercent = this.calculateChangePercent(before, after)
    }

    return diff
  }

  private calculateChangePercent(before?: ConfigValue, after?: ConfigValue): number | undefined {
    if (typeof before === 'number' && typeof after === 'number') {
      if (before === 0) return after === 0 ? 0 : 100
      return Math.round(((after - before) / Math.abs(before)) * 10000) / 100
    }

    if (typeof before === 'string' && typeof after === 'string') {
      const longer = Math.max(before.length, after.length)
      if (longer === 0) return 0
      let differences = 0
      for (let i = 0; i < longer; i++) {
        if (before[i] !== after[i]) differences++
      }
      return Math.round((differences / longer) * 10000) / 100
    }

    return undefined
  }

  formatDiff(report: DiffReport, useColors = true): string {
    const lines: string[] = []

    lines.push(`Diff between ${report.environmentA} and ${report.environmentB}`)
    lines.push(`Summary: ${report.summary.added} added, ${report.summary.removed} removed, ${report.summary.changed} changed`)
    lines.push('')

    for (const diff of report.diffs) {
      const prefix = diff.type === 'added' ? '+' : diff.type === 'removed' ? '-' : '~'
      const color = diff.type === 'added' ? chalk.green : diff.type === 'removed' ? chalk.red : chalk.yellow

      let line = `${prefix} ${diff.path}`

      if (diff.type === 'added') {
        line += ` = ${this.formatValue(diff.after)}`
      } else if (diff.type === 'removed') {
        line += ` = ${this.formatValue(diff.before)}`
      } else {
        line += `: ${this.formatValue(diff.before)} -> ${this.formatValue(diff.after)}`
        if (diff.changePercent !== undefined) {
          line += ` (${diff.changePercent > 0 ? '+' : ''}${diff.changePercent}%)`
        }
      }

      lines.push(useColors ? color(line) : line)
    }

    return lines.join('\n')
  }

  private formatValue(value: ConfigValue | undefined): string {
    if (value === undefined) return 'undefined'
    if (value === null) return 'null'
    if (typeof value === 'string') return `"${value}"`
    if (typeof value === 'object') return JSON.stringify(value)
    return String(value)
  }

  filterDiffs(diffs: DiffItem[], options: { type?: DiffType; keyPattern?: string }): DiffItem[] {
    return diffs.filter((diff) => {
      if (options.type && diff.type !== options.type) return false
      if (options.keyPattern && !new RegExp(options.keyPattern).test(diff.path)) return false
      return true
    })
  }

  hasDrift(report: DiffReport, ignoreKeys?: string[]): boolean {
    if (!ignoreKeys || ignoreKeys.length === 0) {
      return report.diffs.length > 0
    }

    const ignoreRegexes = ignoreKeys.map((k) => new RegExp(`^${k.replace(/\*/g, '.*')}$`))

    return report.diffs.some((diff) => {
      return !ignoreRegexes.some((regex) => regex.test(diff.path))
    })
  }

  generateDriftReport(report: DiffReport, ignoreKeys?: string[]): { drift: boolean; criticalDiffs: DiffItem[]; ignoredDiffs: DiffItem[] } {
    const ignoreRegexes = ignoreKeys?.map((k) => new RegExp(`^${k.replace(/\*/g, '.*')}$`)) || []

    const criticalDiffs: DiffItem[] = []
    const ignoredDiffs: DiffItem[] = []

    for (const diff of report.diffs) {
      const isIgnored = ignoreRegexes.some((regex) => regex.test(diff.path))
      if (isIgnored) {
        ignoredDiffs.push(diff)
      } else {
        criticalDiffs.push(diff)
      }
    }

    return {
      drift: criticalDiffs.length > 0,
      criticalDiffs,
      ignoredDiffs,
    }
  }
}
