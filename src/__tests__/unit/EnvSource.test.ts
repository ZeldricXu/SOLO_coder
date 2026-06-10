import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import { EnvSource } from '../../sources/EnvSource'
import { createTempDir, removeTempDir, writeEnvFile } from '../factories/TestHelper'
import { createDevEnvContent, createEmptyConfig } from '../factories/TestDataFactory'

describe('EnvSource', () => {
  let tempDir: string

  beforeEach(() => {
    tempDir = createTempDir()
  })

  afterEach(() => {
    removeTempDir(tempDir)
  })

  describe('normal path', () => {
    it('should load configuration from .env file', async () => {
      const envPath = writeEnvFile(tempDir, '.env', createDevEnvContent())
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      const data = await source.load()

      expect(data['app.name']).toBe('my-service')
      expect(data['app.port']).toBe(3000)
      expect(data['app.debug']).toBe(true)
      expect(data['db.host']).toBe('localhost')
      expect(data['db.port']).toBe(5432)
      expect(data['loglevel']).toBe('debug')
    })

    it('should parse boolean values correctly', async () => {
      const envPath = writeEnvFile(tempDir, '.env', 'DEBUG=true\nPROD=false')
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      const data = await source.load()
      expect(data['debug']).toBe(true)
      expect(data['prod']).toBe(false)
    })

    it('should parse numeric values correctly', async () => {
      const envPath = writeEnvFile(tempDir, '.env', 'PORT=8080\nTIMEOUT=30.5')
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      const data = await source.load()
      expect(data['port']).toBe(8080)
      expect(data['timeout']).toBe(30.5)
    })

    it('should support key prefix filtering', async () => {
      const envPath = writeEnvFile(tempDir, '.env', 'APP_NAME=test\nOTHER_KEY=value')
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
        prefix: 'APP_',
      })

      const data = await source.load()
      expect(data['name']).toBe('test')
      expect(Object.keys(data)).not.toContain('other.key')
    })

    it('should convert underscores to dots for nested paths', async () => {
      const envPath = writeEnvFile(tempDir, '.env', 'DB_REPLICA_HOST=replica.local\nDB_REPLICA_PORT=5433')
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      const data = await source.load()
      expect(data['db.replica.host']).toBe('replica.local')
      expect(data['db.replica.port']).toBe(5433)
    })

    it('should get a specific key after loading', async () => {
      const envPath = writeEnvFile(tempDir, '.env', 'APP_NAME=myapp\nAPP_PORT=3000')
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      await source.load()
      const value = await source.get('app.name')
      expect(value).toBe('myapp')
    })

    it('should return undefined for non-existent key', async () => {
      const envPath = writeEnvFile(tempDir, '.env', 'APP_NAME=myapp')
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      await source.load()
      const value = await source.get('nonexistent')
      expect(value).toBeUndefined()
    })

    it('should list all keys', async () => {
      const envPath = writeEnvFile(tempDir, '.env', 'A=1\nB=2\nC=3')
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      await source.load()
      const keys = await source.listKeys()
      expect(keys).toEqual(['a', 'b', 'c'])
    })

    it('should check key existence', async () => {
      const envPath = writeEnvFile(tempDir, '.env', 'APP_NAME=myapp')
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      await source.load()
      expect(await source.exists('app.name')).toBe(true)
      expect(await source.exists('nonexistent')).toBe(false)
    })

    it('should auto-load on get if not yet loaded', async () => {
      const envPath = writeEnvFile(tempDir, '.env', 'APP_NAME=autoload')
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      const value = await source.get('app.name')
      expect(value).toBe('autoload')
    })
  })

  describe('exception path', () => {
    it('should handle missing .env file gracefully', async () => {
      const source = new EnvSource('test-env', 100, {
        filePath: path.join(tempDir, 'nonexistent.env'),
        useProcessEnv: false,
      })

      const data = await source.load()
      expect(data).toEqual({})
    })

    it('should handle .env file with comments and blank lines', async () => {
      const content = `# This is a comment
APP_NAME=myapp

# Another comment
APP_PORT=3000
`
      const envPath = writeEnvFile(tempDir, '.env', content)
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      const data = await source.load()
      expect(data['app.name']).toBe('myapp')
      expect(data['app.port']).toBe(3000)
    })

    it('should handle quoted values in .env', async () => {
      const content = 'APP_NAME="my app"\nAPP_DESC=\'single quoted\''
      const envPath = writeEnvFile(tempDir, '.env', content)
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      const data = await source.load()
      expect(data['app.name']).toBe('my app')
      expect(data['app.desc']).toBe('single quoted')
    })
  })

  describe('edge cases', () => {
    it('should handle empty config source without error', async () => {
      const envPath = writeEnvFile(tempDir, '.env', '')
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      const data = await source.load()
      expect(data).toEqual({})
    })

    it('should handle large values (cert PEM)', async () => {
      const certPem = '-----BEGIN CERTIFICATE-----' + 'A'.repeat(10240) + '-----END CERTIFICATE-----'
      const envPath = writeEnvFile(tempDir, '.env', `TLS_CERT=${certPem}`)
      const source = new EnvSource('test-env', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      const data = await source.load()
      expect(data['tls.cert']).toBe(certPem)
      expect(String(data['tls.cert']).length).toBeGreaterThan(10240)
    })

    it('should have correct source type', () => {
      const source = new EnvSource('test', 100, { useProcessEnv: false })
      expect(source.type).toBe('env')
    })

    it('should have correct priority', () => {
      const source = new EnvSource('test', 200, { useProcessEnv: false })
      expect(source.priority).toBe(200)
    })

    it('should have correct name', () => {
      const source = new EnvSource('my-env-source', 100, { useProcessEnv: false })
      expect(source.name).toBe('my-env-source')
    })
  })
})
