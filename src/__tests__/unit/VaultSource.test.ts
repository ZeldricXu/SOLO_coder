import { describe, it, expect, vi, beforeEach } from 'vitest'
import { VaultSource } from '../../sources/VaultSource'

function createMockVaultClient(readData: Record<string, unknown> = {}) {
  return {
    read: vi.fn().mockResolvedValue({
      data: { data: readData },
    }),
    write: vi.fn().mockResolvedValue({}),
    approleLogin: vi.fn().mockResolvedValue({
      auth: { client_token: 'test-token' },
    }),
    token: null,
  }
}

function setupSourceWithMock(source: VaultSource, mockClient: any): void {
  vi.spyOn(source as any, 'initClient').mockImplementation(async () => {
    ;(source as any).client = mockClient
  })
}

describe('VaultSource', () => {
  describe('normal path', () => {
    it('should load and flatten configuration from Vault', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        token: 'test-token',
      })

      const mockClient = createMockVaultClient({
        db: { host: 'vault-db.local', port: 5432 },
        app: { name: 'my-service', debug: true },
      })
      setupSourceWithMock(source, mockClient)

      const data = await source.load()

      expect(data['db.host']).toBe('vault-db.local')
      expect(data['db.port']).toBe(5432)
      expect(data['app.name']).toBe('my-service')
      expect(data['app.debug']).toBe(true)
    })

    it('should get a specific key after loading', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        token: 'test-token',
      })

      const mockClient = createMockVaultClient({ db: { host: 'vault-db.local' } })
      setupSourceWithMock(source, mockClient)

      await source.load()
      const value = await source.get('db.host')
      expect(value).toBe('vault-db.local')
    })

    it('should return undefined for non-existent key', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        token: 'test-token',
      })

      const mockClient = createMockVaultClient({ db: { host: 'vault-db.local' } })
      setupSourceWithMock(source, mockClient)

      await source.load()
      const value = await source.get('nonexistent.key')
      expect(value).toBeUndefined()
    })

    it('should list all keys after loading', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        token: 'test-token',
      })

      const mockClient = createMockVaultClient({
        db: { host: 'x', port: 5432 },
        app: { name: 'y' },
      })
      setupSourceWithMock(source, mockClient)

      await source.load()
      const keys = await source.listKeys()
      expect(keys).toContain('db.host')
      expect(keys).toContain('db.port')
      expect(keys).toContain('app.name')
    })

    it('should set a key value', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        token: 'test-token',
      })

      const existingData = { db: { host: 'old-host', port: 5432 } }
      const mockClient = createMockVaultClient(existingData)
      mockClient.read.mockResolvedValueOnce({ data: { data: existingData } })
      setupSourceWithMock(source, mockClient)

      await source.load()
      await source.set('db.host', 'new-host')

      expect(mockClient.write).toHaveBeenCalledWith('secret/data/myapp', {
        data: expect.objectContaining({ db: expect.objectContaining({ host: 'new-host' }) }),
      })
    })

    it('should delete a key', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        token: 'test-token',
      })

      const existingData = { db: { host: 'old-host', port: 5432 } }
      const mockClient = createMockVaultClient(existingData)
      mockClient.read.mockResolvedValueOnce({ data: { data: existingData } })
      setupSourceWithMock(source, mockClient)

      await source.load()
      await source.delete('db.host')

      expect(mockClient.write).toHaveBeenCalled()
    })

    it('should authenticate with AppRole', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        roleId: 'test-role-id',
        secretId: 'test-secret-id',
      })

      const mockClient = {
        read: vi.fn().mockResolvedValue({
          data: { data: { key: 'value' } },
        }),
        write: vi.fn().mockResolvedValue({}),
        approleLogin: vi.fn().mockResolvedValue({
          auth: { client_token: 'approle-token' },
        }),
        token: null,
      }

      vi.spyOn(source as any, 'initClient').mockImplementation(async () => {
        ;(source as any).client = mockClient
        await mockClient.approleLogin({
          role_id: 'test-role-id',
          secret_id: 'test-secret-id',
        })
        mockClient.token = 'approle-token'
      })

      await source.load()
      expect(mockClient.approleLogin).toHaveBeenCalledWith({
        role_id: 'test-role-id',
        secret_id: 'test-secret-id',
      })
    })
  })

  describe('exception path', () => {
    it('should throw descriptive error on Vault connection failure', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        token: 'bad-token',
      })

      const mockClient = {
        read: vi.fn().mockRejectedValue(new Error('connection refused')),
        write: vi.fn(),
        token: 'bad-token',
      }
      setupSourceWithMock(source, mockClient)

      await expect(source.load()).rejects.toThrow('Failed to load from Vault: connection refused')
    })

    it('should throw descriptive error on token expiry (permission denied)', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        token: 'expired-token',
      })

      const mockClient = {
        read: vi.fn().mockRejectedValue(new Error('permission denied')),
        write: vi.fn(),
        token: 'expired-token',
      }
      setupSourceWithMock(source, mockClient)

      await expect(source.load()).rejects.toThrow('permission denied')
    })

    it('should fallback to local cache when available after connection error', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        token: 'test-token',
      })

      const cachedData = { db: { host: 'cached-host' } }
      const mockClient = createMockVaultClient(cachedData)
      setupSourceWithMock(source, mockClient)

      const firstLoad = await source.load()
      expect(firstLoad['db.host']).toBe('cached-host')

      mockClient.read.mockRejectedValue(new Error('connection timeout'))

      const value = await source.get('db.host')
      expect(value).toBe('cached-host')
    })

    it('should throw error on write failure', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        token: 'test-token',
      })

      const existingData = { db: { host: 'old-host' } }
      const mockClient = createMockVaultClient(existingData)
      mockClient.read.mockResolvedValueOnce({ data: { data: existingData } })
      mockClient.write.mockRejectedValue(new Error('write failed'))
      setupSourceWithMock(source, mockClient)

      await source.load()
      await expect(source.set('db.host', 'new-host')).rejects.toThrow('Failed to write to Vault')
    })
  })

  describe('edge cases', () => {
    it('should handle empty Vault data', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        token: 'test-token',
      })

      const mockClient = createMockVaultClient({})
      setupSourceWithMock(source, mockClient)

      const data = await source.load()
      expect(data).toEqual({})
    })

    it('should handle deeply nested Vault data', async () => {
      const source = new VaultSource('test-vault', 80, {
        path: 'secret/data/myapp',
        token: 'test-token',
      })

      const mockClient = createMockVaultClient({
        db: { replica: { host: 'replica.local', port: 5433 } },
      })
      setupSourceWithMock(source, mockClient)

      const data = await source.load()
      expect(data['db.replica.host']).toBe('replica.local')
      expect(data['db.replica.port']).toBe(5433)
    })

    it('should have correct source type', () => {
      const source = new VaultSource('test', 80, { path: 'secret/x', token: 't' })
      expect(source.type).toBe('vault')
    })

    it('have correct priority', () => {
      const source = new VaultSource('test', 90, { path: 'secret/x', token: 't' })
      expect(source.priority).toBe(90)
    })
  })
})
