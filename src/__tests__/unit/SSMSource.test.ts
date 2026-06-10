import { describe, it, expect, vi } from 'vitest'
import { SSMSource } from '../../sources/SSMSource'

function createMockSSMClient(parameters: { Name: string; Value: string }[] = []) {
  return {
    send: vi.fn().mockResolvedValue({
      Parameters: parameters,
      NextToken: undefined,
    }),
  }
}

function setupSourceWithMock(source: SSMSource, mockClient: any): void {
  vi.spyOn(source as any, 'initClient').mockImplementation(async () => {
    ;(source as any).client = mockClient
  })
}

describe('SSMSource', () => {
  describe('normal path', () => {
    it('should load parameters from SSM by path prefix', async () => {
      const source = new SSMSource('test-ssm', 70, {
        pathPrefix: '/myapp/',
        region: 'us-east-1',
        accessKeyId: 'test',
        secretAccessKey: 'test',
      })

      const mockClient = createMockSSMClient([
        { Name: '/myapp/db/host', Value: 'ssm-db.local' },
        { Name: '/myapp/db/port', Value: '5432' },
        { Name: '/myapp/app/name', Value: 'my-service' },
      ])
      setupSourceWithMock(source, mockClient)

      const data = await source.load()
      expect(data['db.host']).toBe('ssm-db.local')
      expect(data['db.port']).toBe(5432)
      expect(data['app.name']).toBe('my-service')
    })

    it('should strip path prefix and normalize keys', async () => {
      const source = new SSMSource('test-ssm', 70, {
        pathPrefix: '/myapp/config/',
        region: 'us-east-1',
        accessKeyId: 'test',
        secretAccessKey: 'test',
      })

      const mockClient = createMockSSMClient([
        { Name: '/myapp/config/db/host', Value: 'db.local' },
      ])
      setupSourceWithMock(source, mockClient)

      const data = await source.load()
      expect(data['db.host']).toBe('db.local')
    })

    it('should convert boolean values correctly', async () => {
      const source = new SSMSource('test-ssm', 70, {
        pathPrefix: '/myapp/',
        region: 'us-east-1',
        accessKeyId: 'test',
        secretAccessKey: 'test',
      })

      const mockClient = createMockSSMClient([
        { Name: '/myapp/debug', Value: 'true' },
        { Name: '/myapp/prod', Value: 'false' },
      ])
      setupSourceWithMock(source, mockClient)

      const data = await source.load()
      expect(data['debug']).toBe(true)
      expect(data['prod']).toBe(false)
    })

    it('should get a specific key after loading', async () => {
      const source = new SSMSource('test-ssm', 70, {
        pathPrefix: '/myapp/',
        region: 'us-east-1',
        accessKeyId: 'test',
        secretAccessKey: 'test',
      })

      const mockClient = createMockSSMClient([
        { Name: '/myapp/db/host', Value: 'ssm-db.local' },
      ])
      setupSourceWithMock(source, mockClient)

      await source.load()
      const value = await source.get('db.host')
      expect(value).toBe('ssm-db.local')
    })

    it('should return undefined for non-existent key', async () => {
      const source = new SSMSource('test-ssm', 70, {
        pathPrefix: '/myapp/',
        region: 'us-east-1',
        accessKeyId: 'test',
        secretAccessKey: 'test',
      })

      const mockClient = createMockSSMClient([])
      setupSourceWithMock(source, mockClient)

      await source.load()
      const value = await source.get('nonexistent')
      expect(value).toBeUndefined()
    })

    it('should list all keys', async () => {
      const source = new SSMSource('test-ssm', 70, {
        pathPrefix: '/myapp/',
        region: 'us-east-1',
        accessKeyId: 'test',
        secretAccessKey: 'test',
      })

      const mockClient = createMockSSMClient([
        { Name: '/myapp/a', Value: '1' },
        { Name: '/myapp/b', Value: '2' },
      ])
      setupSourceWithMock(source, mockClient)

      await source.load()
      const keys = await source.listKeys()
      expect(keys).toEqual(['a', 'b'])
    })

    it('should set a parameter value', async () => {
      const source = new SSMSource('test-ssm', 70, {
        pathPrefix: '/myapp/',
        region: 'us-east-1',
        accessKeyId: 'test',
        secretAccessKey: 'test',
      })

      const mockClient = createMockSSMClient([
        { Name: '/myapp/key', Value: 'old' },
      ])
      mockClient.send.mockResolvedValueOnce({
        Parameters: [{ Name: '/myapp/key', Value: 'old' }],
        NextToken: undefined,
      }).mockResolvedValueOnce({})
      setupSourceWithMock(source, mockClient)

      await source.load()
      await source.set('key', 'new-value')

      expect(mockClient.send).toHaveBeenCalledTimes(2)
    })

    it('should handle pagination with NextToken', async () => {
      const source = new SSMSource('test-ssm', 70, {
        pathPrefix: '/myapp/',
        region: 'us-east-1',
        accessKeyId: 'test',
        secretAccessKey: 'test',
      })

      const mockClient = {
        send: vi.fn()
          .mockResolvedValueOnce({
            Parameters: [{ Name: '/myapp/a', Value: '1' }],
            NextToken: 'token123',
          })
          .mockResolvedValueOnce({
            Parameters: [{ Name: '/myapp/b', Value: '2' }],
            NextToken: undefined,
          }),
      }
      setupSourceWithMock(source, mockClient)

      const data = await source.load()
      expect(data['a']).toBe(1)
      expect(data['b']).toBe(2)
      expect(mockClient.send).toHaveBeenCalledTimes(2)
    })
  })

  describe('exception path', () => {
    it('should throw descriptive error on SSM access failure', async () => {
      const source = new SSMSource('test-ssm', 70, {
        pathPrefix: '/myapp/',
        region: 'us-east-1',
        accessKeyId: 'test',
        secretAccessKey: 'test',
      })

      const mockClient = {
        send: vi.fn().mockRejectedValue(new Error('AccessDenied')),
      }
      setupSourceWithMock(source, mockClient)

      await expect(source.load()).rejects.toThrow('Failed to load from SSM: AccessDenied')
    })

    it('should throw error on delete failure', async () => {
      const source = new SSMSource('test-ssm', 70, {
        pathPrefix: '/myapp/',
        region: 'us-east-1',
        accessKeyId: 'test',
        secretAccessKey: 'test',
      })

      const mockClient = createMockSSMClient([{ Name: '/myapp/key', Value: 'val' }])
      mockClient.send
        .mockResolvedValueOnce({
          Parameters: [{ Name: '/myapp/key', Value: 'val' }],
          NextToken: undefined,
        })
        .mockRejectedValueOnce(new Error('ParameterNotFound'))
      setupSourceWithMock(source, mockClient)

      await source.load()
      await expect(source.delete('key')).rejects.toThrow('Failed to delete from SSM')
    })
  })

  describe('edge cases', () => {
    it('should handle empty parameter list', async () => {
      const source = new SSMSource('test-ssm', 70, {
        pathPrefix: '/myapp/',
        region: 'us-east-1',
        accessKeyId: 'test',
        secretAccessKey: 'test',
      })

      const mockClient = createMockSSMClient([])
      setupSourceWithMock(source, mockClient)

      const data = await source.load()
      expect(data).toEqual({})
    })

    it('should have correct source type', () => {
      const source = new SSMSource('test', 70, { pathPrefix: '/x/', accessKeyId: 'a', secretAccessKey: 's' })
      expect(source.type).toBe('ssm')
    })

    it('should have correct priority', () => {
      const source = new SSMSource('test', 70, { pathPrefix: '/x/', accessKeyId: 'a', secretAccessKey: 's' })
      expect(source.priority).toBe(70)
    })
  })
})
