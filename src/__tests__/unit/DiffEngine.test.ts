import { describe, it, expect } from 'vitest'
import { DiffEngine } from '../../engine/DiffEngine'
import { ConfigData, DiffItem, DiffReport } from '../../types'
import {
  createDevConfig,
  createStagingConfig,
  createProdConfig,
  createEmptyConfig,
  createLargeConfig,
} from '../factories/TestDataFactory'

describe('DiffEngine', () => {
  let engine: DiffEngine

  beforeEach(() => {
    engine = new DiffEngine()
  })

  describe('normal path - diff types', () => {
    it('should detect added keys', () => {
      const dataA: ConfigData = { app: { name: 'svc' } }
      const dataB: ConfigData = { app: { name: 'svc', port: 3000 } }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const added = report.diffs.filter((d) => d.type === 'added')

      expect(added.length).toBe(1)
      expect(added[0].key).toBe('port')
      expect(added[0].path).toBe('app.port')
      expect(added[0].after).toBe(3000)
    })

    it('should detect removed keys', () => {
      const dataA: ConfigData = { app: { name: 'svc', port: 3000 } }
      const dataB: ConfigData = { app: { name: 'svc' } }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const removed = report.diffs.filter((d) => d.type === 'removed')

      expect(removed.length).toBe(1)
      expect(removed[0].key).toBe('port')
      expect(removed[0].path).toBe('app.port')
      expect(removed[0].before).toBe(3000)
    })

    it('should detect changed values', () => {
      const dataA: ConfigData = { app: { name: 'svc', port: 3000 } }
      const dataB: ConfigData = { app: { name: 'svc', port: 8080 } }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const changed = report.diffs.filter((d) => d.type === 'changed')

      expect(changed.length).toBe(1)
      expect(changed[0].key).toBe('port')
      expect(changed[0].path).toBe('app.port')
      expect(changed[0].before).toBe(3000)
      expect(changed[0].after).toBe(8080)
    })

    it('should detect no diff for identical configs', () => {
      const data = createDevConfig()
      const report = engine.compare(data, data, 'envA', 'envA')

      expect(report.diffs).toEqual([])
      expect(report.summary.total).toBe(0)
    })

    it('should detect all three diff types simultaneously', () => {
      const dataA: ConfigData = {
        common: 'same',
        removed_key: 'gone',
        changed_key: 'old',
      }
      const dataB: ConfigData = {
        common: 'same',
        changed_key: 'new',
        added_key: 'new',
      }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')

      expect(report.diffs.filter((d) => d.type === 'added')).toHaveLength(1)
      expect(report.diffs.filter((d) => d.type === 'removed')).toHaveLength(1)
      expect(report.diffs.filter((d) => d.type === 'changed')).toHaveLength(1)
    })

    it('should compute change percent for numeric values', () => {
      const dataA: ConfigData = { rate: { max: 100 } }
      const dataB: ConfigData = { rate: { max: 200 } }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const changed = report.diffs.find((d) => d.type === 'changed')

      expect(changed!.changePercent).toBe(100)
    })

    it('should compute negative change percent for decreasing values', () => {
      const dataA: ConfigData = { rate: { max: 1000 } }
      const dataB: ConfigData = { rate: { max: 100 } }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const changed = report.diffs.find((d) => d.type === 'changed')

      expect(changed!.changePercent).toBe(-90)
    })

    it('should compute change percent for string values', () => {
      const dataA: ConfigData = { host: 'old-host' }
      const dataB: ConfigData = { host: 'new-host' }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const changed = report.diffs.find((d) => d.type === 'changed')

      expect(changed!.changePercent).toBeDefined()
      expect(typeof changed!.changePercent).toBe('number')
    })
  })

  describe('normal path - real config comparison', () => {
    it('should detect differences between dev and prod configs', () => {
      const devConfig = createDevConfig()
      const prodConfig = createProdConfig()

      const report = engine.compare(devConfig, prodConfig, 'dev', 'prod')

      expect(report.summary.total).toBeGreaterThan(0)
      expect(report.summary.changed).toBeGreaterThan(0)

      const portDiff = report.diffs.find((d) => d.path === 'app.port')
      expect(portDiff).toBeDefined()
      expect(portDiff!.before).toBe(3000)
      expect(portDiff!.after).toBe(8080)
    })

    it('should detect differences between dev and staging configs', () => {
      const devConfig = createDevConfig()
      const stagingConfig = createStagingConfig()

      const report = engine.compare(devConfig, stagingConfig, 'dev', 'staging')

      expect(report.summary.total).toBeGreaterThan(0)
    })

    it('should include summary counts', () => {
      const dataA: ConfigData = { a: 1, b: 2 }
      const dataB: ConfigData = { a: 1, b: 3, c: 4 }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')

      expect(report.summary.added).toBe(1)
      expect(report.summary.changed).toBe(1)
      expect(report.summary.removed).toBe(0)
      expect(report.summary.total).toBe(2)
    })
  })

  describe('exception path - type changes', () => {
    it('should detect type change as a changed diff', () => {
      const dataA: ConfigData = { port: 3000 }
      const dataB: ConfigData = { port: '3000' }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const changed = report.diffs.find((d) => d.type === 'changed')

      expect(changed).toBeDefined()
      expect(changed!.before).toBe(3000)
      expect(changed!.after).toBe('3000')
    })

    it('should detect boolean to string change', () => {
      const dataA: ConfigData = { debug: true }
      const dataB: ConfigData = { debug: 'true' }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      expect(report.diffs.length).toBe(1)
      expect(report.diffs[0].type).toBe('changed')
    })
  })

  describe('edge cases', () => {
    it('should handle empty config on both sides', () => {
      const report = engine.compare({}, {}, 'emptyA', 'emptyB')
      expect(report.diffs).toEqual([])
      expect(report.summary.total).toBe(0)
    })

    it('should handle empty config on one side (all added)', () => {
      const report = engine.compare({}, { a: 1, b: 2 }, 'empty', 'withData')
      expect(report.summary.added).toBe(2)
      expect(report.summary.removed).toBe(0)
      expect(report.summary.changed).toBe(0)
    })

    it('should handle empty config on other side (all removed)', () => {
      const report = engine.compare({ a: 1, b: 2 }, {}, 'withData', 'empty')
      expect(report.summary.added).toBe(0)
      expect(report.summary.removed).toBe(2)
      expect(report.summary.changed).toBe(0)
    })

    it('should handle large config values (cert PEM) without memory issues', () => {
      const largeConfigA = createLargeConfig()
      const largeConfigB = createLargeConfig()

      const report = engine.compare(largeConfigA, largeConfigB, 'largeA', 'largeB')
      expect(report.summary.total).toBe(0)
    })

    it('should handle diff of large values that have changed', () => {
      const largeConfigA = createLargeConfig()
      const largeConfigB = createLargeConfig()
      largeConfigB.tls.cert = largeConfigB.tls.cert + '_CHANGED'

      const report = engine.compare(largeConfigA, largeConfigB, 'largeA', 'largeB')
      expect(report.summary.changed).toBeGreaterThan(0)
    })

    it('should handle null values in diff', () => {
      const dataA: ConfigData = { key: null }
      const dataB: ConfigData = { key: 'value' }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const changed = report.diffs.find((d) => d.key === 'key')
      expect(changed).toBeDefined()
      expect(changed!.before).toBeNull()
      expect(changed!.after).toBe('value')
    })

    it('should handle array comparisons', () => {
      const dataA: ConfigData = { items: ['a', 'b', 'c'] }
      const dataB: ConfigData = { items: ['a', 'x', 'c'] }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const arrDiff = report.diffs.find((d) => d.path === 'items[1]')
      expect(arrDiff).toBeDefined()
      expect(arrDiff!.before).toBe('b')
      expect(arrDiff!.after).toBe('x')
    })

    it('should handle arrays of different lengths', () => {
      const dataA: ConfigData = { items: ['a', 'b'] }
      const dataB: ConfigData = { items: ['a', 'b', 'c'] }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      expect(report.diffs.length).toBeGreaterThan(0)
    })
  })

  describe('formatDiff', () => {
    it('should format diff report as readable string', () => {
      const dataA: ConfigData = { a: 1, b: 'old' }
      const dataB: ConfigData = { a: 2, b: 'new', c: 3 }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const formatted = engine.formatDiff(report, false)

      expect(formatted).toContain('Diff between envA and envB')
      expect(formatted).toContain('Summary:')
      expect(formatted).toContain('+')
    })

    it('should format diff without colors when useColors is false', () => {
      const dataA: ConfigData = { a: 1 }
      const dataB: ConfigData = { a: 2 }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const formatted = engine.formatDiff(report, false)

      expect(typeof formatted).toBe('string')
      expect(formatted.length).toBeGreaterThan(0)
    })
  })

  describe('filterDiffs', () => {
    it('should filter diffs by type', () => {
      const dataA: ConfigData = { a: 1, b: 2 }
      const dataB: ConfigData = { a: 1, b: 3, c: 4 }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const addedOnly = engine.filterDiffs(report.diffs, { type: 'added' })

      expect(addedOnly.every((d) => d.type === 'added')).toBe(true)
    })

    it('should filter diffs by key pattern', () => {
      const dataA: ConfigData = { db_host: 'a', app_port: 1 }
      const dataB: ConfigData = { db_host: 'b', app_port: 2 }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const dbDiffs = engine.filterDiffs(report.diffs, { keyPattern: '^db' })

      expect(dbDiffs.length).toBe(1)
      expect(dbDiffs[0].key).toBe('db_host')
    })
  })

  describe('hasDrift / generateDriftReport', () => {
    it('should detect drift when diffs exist', () => {
      const dataA: ConfigData = { a: 1 }
      const dataB: ConfigData = { a: 2 }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      expect(engine.hasDrift(report)).toBe(true)
    })

    it('should not detect drift when configs match', () => {
      const data = { a: 1, b: 2 }
      const report = engine.compare(data, data, 'envA', 'envB')
      expect(engine.hasDrift(report)).toBe(false)
    })

    it('should ignore specified keys in drift detection', () => {
      const dataA: ConfigData = { a: 1, b: 2 }
      const dataB: ConfigData = { a: 1, b: 3 }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      expect(engine.hasDrift(report, ['b'])).toBe(false)
      expect(engine.hasDrift(report, ['a'])).toBe(true)
    })

    it('should generate drift report separating critical and ignored', () => {
      const dataA: ConfigData = { a: 1, b: 2, c: 3 }
      const dataB: ConfigData = { a: 1, b: 3, c: 4 }

      const report = engine.compare(dataA, dataB, 'envA', 'envB')
      const driftReport = engine.generateDriftReport(report, ['b'])

      expect(driftReport.drift).toBe(true)
      expect(driftReport.criticalDiffs.length).toBe(1)
      expect(driftReport.ignoredDiffs.length).toBe(1)
      expect(driftReport.criticalDiffs[0].key).toBe('c')
      expect(driftReport.ignoredDiffs[0].key).toBe('b')
    })
  })
})
