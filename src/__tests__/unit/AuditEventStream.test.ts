import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { KafkaChannel, ElasticsearchChannel, NotificationDispatcher } from '../../notifications/NotificationDispatcher'
import { NotificationMessage, AuditEvent } from '../../types'

function createTestMessage(): NotificationMessage {
  return {
    title: 'Config Changed',
    summary: 'Configuration was updated',
    operator: 'test-operator',
    environment: 'staging',
    timestamp: Date.now(),
    changes: [
      { type: 'changed', key: 'app.port', path: 'app.port', before: 3000, after: 8080, changePercent: 169.33 },
      { type: 'added', key: 'app.debug', path: 'app.debug', after: true },
    ],
  }
}

describe('Audit Event Stream', () => {
  describe('NotificationDispatcher.buildAuditEvent', () => {
    it('should build an AuditEvent from NotificationMessage', () => {
      const message = createTestMessage()
      const event = NotificationDispatcher.buildAuditEvent(message)

      expect(event.timestamp).toBe(new Date(message.timestamp).toISOString())
      expect(event.operator).toBe('test-operator')
      expect(event.sourceEnvironment).toBe('staging')
      expect(event.changedKeys).toEqual(['app.port', 'app.debug'])
      expect(event.eventType).toBe('config.change')
    })

    it('should compute SHA-256 beforeHash and afterHash', () => {
      const message = createTestMessage()
      const event = NotificationDispatcher.buildAuditEvent(message)

      expect(event.beforeHash).toMatch(/^[a-f0-9]{64}$/)
      expect(event.afterHash).toMatch(/^[a-f0-9]{64}$/)
      expect(event.beforeHash).not.toBe(event.afterHash)
    })

    it('should produce same hash for same before values', () => {
      const msg1: NotificationMessage = {
        title: 't', summary: 's', operator: 'o', environment: 'e',
        timestamp: 1000,
        changes: [{ type: 'changed', key: 'k', path: 'k', before: 42, after: 43 }],
      }
      const msg2: NotificationMessage = {
        title: 't2', summary: 's2', operator: 'o2', environment: 'e2',
        timestamp: 2000,
        changes: [{ type: 'changed', key: 'k', path: 'k', before: 42, after: 99 }],
      }

      const event1 = NotificationDispatcher.buildAuditEvent(msg1)
      const event2 = NotificationDispatcher.buildAuditEvent(msg2)

      expect(event1.beforeHash).toBe(event2.beforeHash)
      expect(event1.afterHash).not.toBe(event2.afterHash)
    })

    it('should handle empty changes', () => {
      const message: NotificationMessage = {
        title: 't', summary: 's', operator: 'o', environment: 'e',
        timestamp: 1000,
        changes: [],
      }

      const event = NotificationDispatcher.buildAuditEvent(message)

      expect(event.changedKeys).toEqual([])
      expect(event.beforeHash).toMatch(/^[a-f0-9]{64}$/)
      expect(event.afterHash).toMatch(/^[a-f0-9]{64}$/)
    })
  })

  describe('KafkaChannel', () => {
    it('should have correct type', () => {
      const channel = new KafkaChannel({ brokers: 'localhost:9092', topic: 'test' })
      expect(channel.type).toBe('kafka')
    })

    it('should fall back to env vars for config', () => {
      process.env.KAFKA_BROKERS = 'env-broker:9092'
      process.env.KAFKA_TOPIC = 'env-topic'
      process.env.KAFKA_CLIENT_ID = 'env-client'

      const channel = new KafkaChannel({ brokers: '', topic: '' })
      const channelAny = channel as any

      expect(channelAny.brokers).toBe('env-broker:9092')
      expect(channelAny.topic).toBe('env-topic')
      expect(channelAny.clientId).toBe('env-client')

      delete process.env.KAFKA_BROKERS
      delete process.env.KAFKA_TOPIC
      delete process.env.KAFKA_CLIENT_ID
    })

    it('should fall back to env vars for SASL auth', () => {
      process.env.KAFKA_SASL_USERNAME = 'user'
      process.env.KAFKA_SASL_PASSWORD = 'pass'

      const channel = new KafkaChannel({ brokers: 'b:9092', topic: 't' })
      const channelAny = channel as any

      expect(channelAny.auth).toEqual({ mechanism: 'plain', username: 'user', password: 'pass' })

      delete process.env.KAFKA_SASL_USERNAME
      delete process.env.KAFKA_SASL_PASSWORD
    })

    it('should return error when kafkajs connection fails', async () => {
      const channel = new KafkaChannel({ brokers: 'invalid-host:9999', topic: 'test' })
      const message = createTestMessage()

      const result = await Promise.race([
        channel.send(message),
        new Promise<{ success: boolean; error?: string }>((resolve) =>
          setTimeout(() => resolve({ success: false, error: 'timeout' }), 3000)
        ),
      ])

      expect(result.success).toBe(false)
      expect(result.error).toBeDefined()
    }, 5000)
  })

  describe('ElasticsearchChannel', () => {
    it('should have correct type', () => {
      const channel = new ElasticsearchChannel({ nodeUrl: 'http://localhost:9200', index: 'audit' })
      expect(channel.type).toBe('elasticsearch')
    })

    it('should fall back to env vars for config', () => {
      process.env.ES_NODE_URL = 'http://env-es:9200'
      process.env.ES_INDEX = 'env-audit'

      const channel = new ElasticsearchChannel({ nodeUrl: '', index: '' })
      const channelAny = channel as any

      expect(channelAny.nodeUrl).toBe('http://env-es:9200')
      expect(channelAny.index).toBe('env-audit')

      delete process.env.ES_NODE_URL
      delete process.env.ES_INDEX
    })

    it('should fall back to env vars for auth', () => {
      process.env.ES_USERNAME = 'es-user'
      process.env.ES_PASSWORD = 'es-pass'

      const channel = new ElasticsearchChannel({ nodeUrl: 'http://es:9200', index: 'audit' })
      const channelAny = channel as any

      expect(channelAny.auth).toEqual({ username: 'es-user', password: 'es-pass' })

      delete process.env.ES_USERNAME
      delete process.env.ES_PASSWORD
    })

    it('should return error when ES is not reachable', async () => {
      const channel = new ElasticsearchChannel({ nodeUrl: 'http://localhost:99999', index: 'audit' })
      const message = createTestMessage()
      const result = await channel.send(message)

      expect(result.success).toBe(false)
      expect(result.error).toBeDefined()
    })
  })

  describe('NotificationDispatcher with audit channels', () => {
    it('should add kafka channel', () => {
      const dispatcher = new NotificationDispatcher()
      dispatcher.addChannel({
        type: 'kafka',
        config: { brokers: 'localhost:9092', topic: 'audit' },
      })

      const channels = dispatcher.listChannels()
      expect(channels.some((c) => c.type === 'kafka')).toBe(true)
    })

    it('should add elasticsearch channel', () => {
      const dispatcher = new NotificationDispatcher()
      dispatcher.addChannel({
        type: 'elasticsearch',
        config: { nodeUrl: 'http://localhost:9200', index: 'audit' },
      })

      const channels = dispatcher.listChannels()
      expect(channels.some((c) => c.type === 'elasticsearch')).toBe(true)
    })

    it('should still support existing channel types', () => {
      const dispatcher = new NotificationDispatcher()
      dispatcher.addChannel({ type: 'slack', config: { webhookUrl: 'https://hooks.slack.com/test' } })
      dispatcher.addChannel({ type: 'webhook', config: { url: 'https://example.com/hook' } })

      const channels = dispatcher.listChannels()
      expect(channels.some((c) => c.type === 'slack')).toBe(true)
      expect(channels.some((c) => c.type === 'webhook')).toBe(true)
    })
  })
})
