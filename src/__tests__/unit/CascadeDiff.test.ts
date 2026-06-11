import { describe, it, expect } from 'vitest'
import { DiffEngine } from '../../engine/DiffEngine'
import { ConfigData } from '../../types'

describe('Cascade Diff', () => {
  const diffEngine = new DiffEngine()

  const devConfig: ConfigData = {
    app: { name: 'my-service', port: 3000, debug: true },
    db: { host: 'localhost', port: 5432 },
    logLevel: 'debug',
  }

  const stagingConfig: ConfigData = {
    app: { name: 'my-service', port: 3000, debug: false },
    db: { host: 'staging-db.internal', port: 5432 },
    logLevel: 'info',
  }

  const prodConfig: ConfigData = {
    app: { name: 'my-service', port: 8080, debug: false },
    db: { host: 'prod-db.internal', port: 5432 },
    logLevel: 'warn',
  }

  describe('cascadeCompare', () => {
    it('should compare dev→staging→prod chain', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', devConfig)
      envs.set('staging', stagingConfig)
      envs.set('prod', prodConfig)

      const report = diffEngine.cascadeCompare(envs)

      expect(report.environmentChain).toEqual(['dev', 'staging', 'prod'])
      expect(report.rows.length).toBeGreaterThan(0)
      expect(report.summary.totalKeys).toBeGreaterThan(0)
      expect(report.timestamp).toBeGreaterThan(0)
    })

    it('should mark consistent keys as green', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', { app: { name: 'same' } })
      envs.set('staging', { app: { name: 'same' } })
      envs.set('prod', { app: { name: 'same' } })

      const report = diffEngine.cascadeCompare(envs)

      const nameRow = report.rows.find((r) => r.key === 'app.name')
      expect(nameRow).toBeDefined()
      expect(nameRow!.status).toBe('consistent')
      expect(nameRow!.transitions.every((t) => t.type === 'unchanged')).toBe(true)
    })

    it('should mark drift-risk keys as red when value changes then reverts', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', { port: 3000 })
      envs.set('staging', { port: 4000 })
      envs.set('prod', { port: 3000 })

      const report = diffEngine.cascadeCompare(envs)

      const portRow = report.rows.find((r) => r.key === 'port')
      expect(portRow).toBeDefined()
      expect(portRow!.status).toBe('drift-risk')
    })

    it('should mark changed keys as yellow when every step differs', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', { logLevel: 'debug' })
      envs.set('staging', { logLevel: 'info' })
      envs.set('prod', { logLevel: 'warn' })

      const report = diffEngine.cascadeCompare(envs)

      const row = report.rows.find((r) => r.key === 'logLevel')
      expect(row).toBeDefined()
      expect(row!.status).toBe('changed')
    })

    it('should detect added keys in later environments', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', { a: 1 })
      envs.set('staging', { a: 1, b: 2 })

      const report = diffEngine.cascadeCompare(envs)

      const bRow = report.rows.find((r) => r.key === 'b')
      expect(bRow).toBeDefined()
      expect(bRow!.transitions[0].type).toBe('added')
    })

    it('should detect removed keys in later environments', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', { a: 1, b: 2 })
      envs.set('staging', { a: 1 })

      const report = diffEngine.cascadeCompare(envs)

      const bRow = report.rows.find((r) => r.key === 'b')
      expect(bRow).toBeDefined()
      expect(bRow!.transitions[0].type).toBe('removed')
    })

    it('should work with just two environments', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', { x: 1 })
      envs.set('prod', { x: 2 })

      const report = diffEngine.cascadeCompare(envs)

      expect(report.environmentChain).toEqual(['dev', 'prod'])
      expect(report.rows.length).toBe(1)
      expect(report.rows[0].status).toBe('changed')
    })

    it('should handle empty environments', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', {})
      envs.set('staging', {})

      const report = diffEngine.cascadeCompare(envs)

      expect(report.rows).toEqual([])
      expect(report.summary.totalKeys).toBe(0)
      expect(report.summary.consistent).toBe(0)
    })

    it('should calculate changePercent for numeric transitions', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', { port: 3000 })
      envs.set('prod', { port: 8080 })

      const report = diffEngine.cascadeCompare(envs)

      const row = report.rows.find((r) => r.key === 'port')
      expect(row!.transitions[0].changePercent).toBeDefined()
    })

    it('should count summary correctly', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', { a: 1, b: 2, c: 'same' })
      envs.set('staging', { a: 1, b: 3, c: 'same' })
      envs.set('prod', { a: 1, b: 2, c: 'same' })

      const report = diffEngine.cascadeCompare(envs)

      expect(report.summary.consistent).toBe(2)
      expect(report.summary.driftRisk).toBe(1)
      expect(report.summary.changed).toBe(0)
    })
  })

  describe('formatCascadeDiff', () => {
    it('should produce formatted output with table', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', devConfig)
      envs.set('staging', stagingConfig)
      envs.set('prod', prodConfig)

      const report = diffEngine.cascadeCompare(envs)
      const formatted = diffEngine.formatCascadeDiff(report)

      expect(formatted).toContain('Cascade Diff')
      expect(formatted).toContain('dev')
      expect(formatted).toContain('prod')
    })

    it('should produce output without colors', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', { x: 1 })
      envs.set('prod', { x: 2 })

      const report = diffEngine.cascadeCompare(envs)
      const formatted = diffEngine.formatCascadeDiff(report, false)

      expect(formatted).toContain('Cascade Diff')
      expect(formatted).toContain('CHANGED')
    })

    it('should handle empty report', () => {
      const envs = new Map<string, ConfigData>()
      envs.set('dev', {})
      envs.set('prod', {})

      const report = diffEngine.cascadeCompare(envs)
      const formatted = diffEngine.formatCascadeDiff(report)

      expect(formatted).toContain('No keys found')
    })
  })
})
