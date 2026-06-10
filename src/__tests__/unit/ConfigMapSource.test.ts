import { describe, it, expect, vi } from 'vitest'
import { ConfigMapSource } from '../../sources/ConfigMapSource'

function createMockK8sApi(data: Record<string, string> = {}, statusCode?: number) {
  const error: any = new Error('Not Found')
  error.response = { statusCode: 404 }

  return {
    readNamespacedConfigMap: vi.fn().mockImplementation((name: string, ns: string) => {
      if (statusCode === 404) {
        return Promise.reject(error)
      }
      return Promise.resolve({
        body: { data },
      })
    }),
    patchNamespacedConfigMap: vi.fn().mockResolvedValue({
      body: { data },
    }),
  }
}

function setupSourceWithMock(source: ConfigMapSource, mockApi: any): void {
  vi.spyOn(source as any, 'initClient').mockImplementation(async () => {
    ;(source as any).k8sApi = mockApi
  })
}

describe('ConfigMapSource', () => {
  describe('normal path', () => {
    it('should load flat key-value data from ConfigMap', async () => {
      const source = new ConfigMapSource('test-cm', 60, {
        name: 'myapp-config',
        namespace: 'default',
      })

      const mockApi = createMockK8sApi({
        DB_HOST: 'cm-db.local',
        DB_PORT: '5432',
        APP_NAME: 'my-service',
      })
      setupSourceWithMock(source, mockApi)

      const data = await source.load()
      expect(data['DB_HOST']).toBe('cm-db.local')
      expect(data['DB_PORT']).toBe(5432)
      expect(data['APP_NAME']).toBe('my-service')
    })

    it('should load JSON data from a specific dataKey', async () => {
      const source = new ConfigMapSource('test-cm', 60, {
        name: 'myapp-config',
        namespace: 'default',
        dataKey: 'config.json',
      })

      const jsonData = JSON.stringify({
        db: { host: 'cm-db.local', port: 5432 },
        app: { name: 'my-service' },
      })
      const mockApi = createMockK8sApi({ 'config.json': jsonData })
      setupSourceWithMock(source, mockApi)

      const data = await source.load()
      expect(data['db.host']).toBe('cm-db.local')
      expect(data['db.port']).toBe(5432)
      expect(data['app.name']).toBe('my-service')
    })

    it('should load YAML data from a specific dataKey', async () => {
      const source = new ConfigMapSource('test-cm', 60, {
        name: 'myapp-config',
        namespace: 'default',
        dataKey: 'config.yaml',
      })

      const yamlData = 'db:\n  host: yaml-db.local\n  port: 3306\napp:\n  name: yaml-service\n'
      const mockApi = createMockK8sApi({ 'config.yaml': yamlData })
      setupSourceWithMock(source, mockApi)

      const data = await source.load()
      expect(data['db.host']).toBe('yaml-db.local')
      expect(data['db.port']).toBe(3306)
      expect(data['app.name']).toBe('yaml-service')
    })

    it('should load .env format from a specific dataKey', async () => {
      const source = new ConfigMapSource('test-cm', 60, {
        name: 'myapp-config',
        namespace: 'default',
        dataKey: 'app.env',
      })

      const envData = 'DB_HOST=env-db.local\nDB_PORT=5432\nAPP_DEBUG=true'
      const mockApi = createMockK8sApi({ 'app.env': envData })
      setupSourceWithMock(source, mockApi)

      const data = await source.load()
      expect(data['DB_HOST']).toBe('env-db.local')
      expect(data['DB_PORT']).toBe(5432)
      expect(data['APP_DEBUG']).toBe(true)
    })

    it('should get a specific key after loading', async () => {
      const source = new ConfigMapSource('test-cm', 60, {
        name: 'myapp-config',
        namespace: 'default',
      })

      const mockApi = createMockK8sApi({ DB_HOST: 'cm-db.local' })
      setupSourceWithMock(source, mockApi)

      await source.load()
      const value = await source.get('DB_HOST')
      expect(value).toBe('cm-db.local')
    })

    it('should list all keys', async () => {
      const source = new ConfigMapSource('test-cm', 60, {
        name: 'myapp-config',
        namespace: 'default',
      })

      const mockApi = createMockK8sApi({ A: '1', B: '2', C: '3' })
      setupSourceWithMock(source, mockApi)

      await source.load()
      const keys = await source.listKeys()
      expect(keys.sort()).toEqual(['A', 'B', 'C'])
    })

    it('should set a key value', async () => {
      const source = new ConfigMapSource('test-cm', 60, {
        name: 'myapp-config',
        namespace: 'default',
      })

      const existingData = { DB_HOST: 'old-host' }
      const mockApi = createMockK8sApi(existingData)
      setupSourceWithMock(source, mockApi)

      await source.load()
      await source.set('DB_HOST', 'new-host')

      expect(mockApi.patchNamespacedConfigMap).toHaveBeenCalledWith(
        'myapp-config',
        'default',
        { data: expect.objectContaining({ DB_HOST: 'new-host' }) },
        undefined,
        undefined,
        undefined,
        undefined,
        undefined,
        { headers: { 'Content-Type': 'application/merge-patch+json' } }
      )
    })
  })

  describe('exception path', () => {
    it('should return empty data when ConfigMap namespace does not exist (404)', async () => {
      const source = new ConfigMapSource('test-cm', 60, {
        name: 'myapp-config',
        namespace: 'nonexistent-ns',
      })

      const mockApi = createMockK8sApi({}, 404)
      setupSourceWithMock(source, mockApi)

      const data = await source.load()
      expect(data).toEqual({})
    })

    it('should throw descriptive error for other K8s API errors', async () => {
      const source = new ConfigMapSource('test-cm', 60, {
        name: 'myapp-config',
        namespace: 'default',
      })

      const mockApi = {
        readNamespacedConfigMap: vi.fn().mockRejectedValue(new Error('Forbidden: user cannot list configmaps')),
      }
      setupSourceWithMock(source, mockApi)

      await expect(source.load()).rejects.toThrow('Failed to load from ConfigMap')
    })

    it('should throw error on set failure', async () => {
      const source = new ConfigMapSource('test-cm', 60, {
        name: 'myapp-config',
        namespace: 'default',
      })

      const mockApi = createMockK8sApi({ KEY: 'value' })
      mockApi.patchNamespacedConfigMap.mockRejectedValue(new Error('patch failed'))
      setupSourceWithMock(source, mockApi)

      await source.load()
      await expect(source.set('KEY', 'new-value')).rejects.toThrow('Failed to write to ConfigMap')
    })
  })

  describe('edge cases', () => {
    it('should handle empty ConfigMap data', async () => {
      const source = new ConfigMapSource('test-cm', 60, {
        name: 'myapp-config',
        namespace: 'default',
      })

      const mockApi = createMockK8sApi({})
      setupSourceWithMock(source, mockApi)

      const data = await source.load()
      expect(data).toEqual({})
    })

    it('should have correct source type', () => {
      const source = new ConfigMapSource('test', 60, { name: 'cm' })
      expect(source.type).toBe('configmap')
    })

    it('should have correct priority', () => {
      const source = new ConfigMapSource('test', 60, { name: 'cm' })
      expect(source.priority).toBe(60)
    })

    it('should default namespace to "default"', () => {
      const source = new ConfigMapSource('test', 60, { name: 'cm' })
      expect((source as any).options.namespace).toBe('default')
    })
  })
})
