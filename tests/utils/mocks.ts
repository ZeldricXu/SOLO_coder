import { vi } from 'vitest';
import { v4 as uuidv4 } from 'uuid';
import { IChannelAdapter, ChannelStatus, ChannelResult, NotificationRequest, Recipient } from '../../src/types';
import { NotificationQueue } from '../../src/queue/NotificationQueue';
import { DeliveryTracker } from '../../src/tracking/DeliveryTracker';
import { TemplateEngine } from '../../src/templates/TemplateEngine';
import { NotificationRouter } from '../../src/router/NotificationRouter';
import * as dbModule from '../../src/db';

export function createMockChannelAdapter(
  channel: string,
  available: boolean = true
): jest.Mocked<IChannelAdapter> {
  return {
    getName: vi.fn().mockReturnValue(channel),
    send: vi.fn().mockResolvedValue({
      channel,
      provider: 'test',
      status: 'sent',
      message_id: 'test-message-id',
      sent_at: new Date(),
    } as ChannelResult),
    healthCheck: vi.fn().mockResolvedValue(available),
    getStatus: vi.fn().mockResolvedValue({
      name: channel as any,
      available,
      last_checked: new Date(),
    } as ChannelStatus),
  };
}

export function createMockAdapterManager(
  adapters: Record<string, jest.Mocked<IChannelAdapter>>
) {
  return {
    getAdapter: vi.fn((channel: string) => adapters[channel]),
    getAvailableAdapters: vi.fn(() => Object.values(adapters)),
    healthCheckAll: vi.fn(async () => {
      const results = new Map();
      for (const [name, adapter] of Object.entries(adapters)) {
        results.set(name, await adapter.healthCheck());
      }
      return results;
    }),
    getAllStatuses: vi.fn(async () => {
      const statuses: ChannelStatus[] = [];
      for (const adapter of Object.values(adapters)) {
        statuses.push(await adapter.getStatus());
      }
      return statuses;
    }),
    isChannelAvailable: vi.fn((channel: string) => !!adapters[channel]),
  };
}

function normalizeSql(sql: string): string {
  return sql.replace(/\s+/g, ' ').trim();
}

export function createMockDatabase() {
  const mockDb = dbModule.db as any;
  const dataStore: Map<string, any[]> = new Map();

  mockDb.query = vi.fn(async (sql: string, params: any[]) => {
    const normalizedSql = normalizeSql(sql);
    if (normalizedSql.startsWith('INSERT INTO orchestration_sequences')) {
      const row = {
        id: params[0],
        tenant_id: params[1],
        name: params[2],
        description: params[3],
        steps: params[4],
        trigger_type: params[5],
        trigger_event: params[6],
        enabled: params[7],
        created_by: params[8],
        created_at: new Date(),
        updated_at: new Date(),
      };
      const store = dataStore.get('orchestration_sequences') || [];
      store.push(row);
      dataStore.set('orchestration_sequences', store);
      return { rows: [row], rowCount: 1 };
    }

    if (normalizedSql.startsWith('SELECT * FROM orchestration_sequences WHERE id = $1')) {
      const store = dataStore.get('orchestration_sequences') || [];
      const row = store.find((r: any) => r.id === params[0] && r.tenant_id === params[1]);
      return { rows: row ? [row] : [], rowCount: row ? 1 : 0 };
    }

    if (normalizedSql.startsWith('SELECT * FROM orchestration_sequences WHERE tenant_id = $1')) {
      const store = dataStore.get('orchestration_sequences') || [];
      const rows = store.filter((r: any) => r.tenant_id === params[0]);
      return { rows, rowCount: rows.length };
    }

    if (normalizedSql.startsWith('UPDATE orchestration_sequences')) {
      const store = dataStore.get('orchestration_sequences') || [];
      const idx = store.findIndex((r: any) => r.id === params[7] && r.tenant_id === params[8]);
      if (idx !== -1) {
        store[idx] = {
          ...store[idx],
          name: params[0] ?? store[idx].name,
          description: params[1] ?? store[idx].description,
          steps: params[2] ?? store[idx].steps,
          trigger_type: params[3] ?? store[idx].trigger_type,
          trigger_event: params[4] ?? store[idx].trigger_event,
          enabled: params[5] ?? store[idx].enabled,
          updated_at: new Date(),
        };
        return { rows: [store[idx]], rowCount: 1 };
      }
      return { rows: [], rowCount: 0 };
    }

    if (normalizedSql.startsWith('DELETE FROM orchestration_sequences')) {
      const store = dataStore.get('orchestration_sequences') || [];
      const idx = store.findIndex((r: any) => r.id === params[0] && r.tenant_id === params[1]);
      if (idx !== -1) {
        store.splice(idx, 1);
        return { rows: [], rowCount: 1 };
      }
      return { rows: [], rowCount: 0 };
    }

    if (normalizedSql.startsWith('INSERT INTO orchestration_instances')) {
      const row = {
        id: params[0],
        sequence_id: params[1],
        tenant_id: params[2],
        recipient: params[3],
        status: params[4],
        current_step_index: params[5],
        template_variables: params[6],
        started_at: params[7],
        metadata: params[8],
        ended_at: null,
        created_at: new Date(),
        updated_at: new Date(),
      };
      const store = dataStore.get('orchestration_instances') || [];
      store.push(row);
      dataStore.set('orchestration_instances', store);
      return { rows: [row], rowCount: 1 };
    }

    if (normalizedSql.startsWith('SELECT * FROM orchestration_instances WHERE id = $1')) {
      const store = dataStore.get('orchestration_instances') || [];
      const row = store.find((r: any) => r.id === params[0] && r.tenant_id === params[1]);
      return { rows: row ? [row] : [], rowCount: row ? 1 : 0 };
    }

    if (normalizedSql.startsWith('SELECT * FROM orchestration_instances WHERE sequence_id = $1')) {
      const store = dataStore.get('orchestration_instances') || [];
      const rows = store.filter((r: any) => r.sequence_id === params[0] && r.tenant_id === params[1]);
      return { rows, rowCount: rows.length };
    }

    if (normalizedSql.startsWith('UPDATE orchestration_instances SET status = $1')) {
      const store = dataStore.get('orchestration_instances') || [];
      const idx = store.findIndex((r: any) => r.id === params[2]);
      if (idx !== -1) {
        store[idx] = {
          ...store[idx],
          status: params[0],
          current_step_index: params[1] ?? store[idx].current_step_index,
          ended_at: params[0] === 'completed' || params[0] === 'failed' || params[0] === 'cancelled' ? new Date() : null,
          updated_at: new Date(),
        };
        return { rows: [], rowCount: 1 };
      }
      return { rows: [], rowCount: 0 };
    }

    if (normalizedSql.startsWith('INSERT INTO orchestration_step_executions')) {
      const row = {
        id: params[0],
        instance_id: params[1],
        step_id: params[2],
        status: params[3],
        scheduled_at: params[4],
        started_at: null,
        completed_at: null,
        result: null,
        delivery_id: null,
        error_message: null,
        created_at: new Date(),
        updated_at: new Date(),
      };
      const store = dataStore.get('orchestration_step_executions') || [];
      store.push(row);
      dataStore.set('orchestration_step_executions', store);
      return { rows: [row], rowCount: 1 };
    }

    if (normalizedSql.startsWith('SELECT * FROM orchestration_step_executions WHERE instance_id = $1')) {
      const store = dataStore.get('orchestration_step_executions') || [];
      const rows = store.filter((r: any) => r.instance_id === params[0]);
      return { rows, rowCount: rows.length };
    }

    if (normalizedSql.startsWith('SELECT * FROM orchestration_step_executions WHERE id = $1')) {
      const store = dataStore.get('orchestration_step_executions') || [];
      const row = store.find((r: any) => r.id === params[0]);
      return { rows: row ? [row] : [], rowCount: row ? 1 : 0 };
    }

    if (normalizedSql.startsWith('UPDATE orchestration_step_executions SET status = $1')) {
      const store = dataStore.get('orchestration_step_executions') || [];
      const idx = store.findIndex((r: any) => r.id === params[4]);
      if (idx !== -1) {
        store[idx] = {
          ...store[idx],
          status: params[0],
          started_at: params[1] ?? store[idx].started_at,
          completed_at: params[2] ?? store[idx].completed_at,
          result: params[3] ?? store[idx].result,
          error_message: params[5] ?? store[idx].error_message,
          updated_at: new Date(),
        };
        return { rows: [], rowCount: 1 };
      }
      return { rows: [], rowCount: 0 };
    }

    if (normalizedSql.startsWith('UPDATE orchestration_step_executions SET delivery_id = $1')) {
      const store = dataStore.get('orchestration_step_executions') || [];
      const idx = store.findIndex((r: any) => r.id === params[1]);
      if (idx !== -1) {
        store[idx].delivery_id = params[0];
        return { rows: [], rowCount: 1 };
      }
      return { rows: [], rowCount: 0 };
    }

    if (normalizedSql.startsWith('INSERT INTO templates (tenant_id, notification_type, locale, name, subject_template, body_template, html_template, variables, is_system_default)')) {
      let isSystemDefault = false;
      if (normalizedSql.includes("VALUES ($1, $2, $3, $4, $5, $6, $7, $8, true)")) {
        isSystemDefault = true;
      } else if (normalizedSql.includes("VALUES ($1, $2, $3, $4, $5, $6, $7, $8, false)")) {
        isSystemDefault = false;
      }
      
      const row = {
        id: uuidv4(),
        tenant_id: params[0],
        notification_type: params[1],
        locale: params[2],
        name: params[3],
        subject_template: params[4],
        body_template: params[5],
        html_template: params[6],
        variables: params[7],
        is_system_default: isSystemDefault,
        created_at: new Date(),
        updated_at: new Date(),
      };
      const store = dataStore.get('templates') || [];
      const existingIdx = store.findIndex(
        (r: any) => r.tenant_id === row.tenant_id && r.notification_type === row.notification_type && r.locale === row.locale && r.is_system_default === row.is_system_default
      );
      if (existingIdx !== -1) {
        store[existingIdx] = { ...row, id: store[existingIdx].id, created_at: store[existingIdx].created_at };
        return { rows: [store[existingIdx]], rowCount: 1 };
      }
      store.push(row);
      dataStore.set('templates', store);
      return { rows: [row], rowCount: 1 };
    }

    if (normalizedSql.startsWith('SELECT * FROM templates WHERE id = $1') && normalizedSql.includes('is_system_default = false')) {
      const store = dataStore.get('templates') || [];
      const row = store.find((r: any) => r.id === params[0] && r.tenant_id === params[1] && r.is_system_default === false);
      return { rows: row ? [row] : [], rowCount: row ? 1 : 0 };
    }

    if (normalizedSql.startsWith('SELECT * FROM templates WHERE id = $1') && normalizedSql.includes('is_system_default = true')) {
      const store = dataStore.get('templates') || [];
      const row = store.find((r: any) => r.id === params[0] && r.tenant_id === params[1] && r.is_system_default === true);
      return { rows: row ? [row] : [], rowCount: row ? 1 : 0 };
    }

    if (normalizedSql.startsWith('SELECT * FROM templates WHERE tenant_id = $1 AND notification_type = $2 AND locale = $3 AND is_system_default = false')) {
      const store = dataStore.get('templates') || [];
      const row = store.find(
        (r: any) => r.tenant_id === params[0] && r.notification_type === params[1] && r.locale === params[2] && r.is_system_default === false
      );
      return { rows: row ? [row] : [], rowCount: row ? 1 : 0 };
    }

    if (normalizedSql.startsWith("SELECT * FROM templates WHERE tenant_id = $1 AND notification_type = $2 AND locale = 'en' AND is_system_default = false")) {
      const store = dataStore.get('templates') || [];
      const row = store.find(
        (r: any) => r.tenant_id === params[0] && r.notification_type === params[1] && r.locale === 'en' && r.is_system_default === false
      );
      return { rows: row ? [row] : [], rowCount: row ? 1 : 0 };
    }

    if (normalizedSql.startsWith('SELECT * FROM templates WHERE tenant_id = $1 AND notification_type = $2 AND locale = $3 AND is_system_default = true')) {
      const store = dataStore.get('templates') || [];
      const row = store.find(
        (r: any) => r.tenant_id === params[0] && r.notification_type === params[1] && r.locale === params[2] && r.is_system_default === true
      );
      return { rows: row ? [row] : [], rowCount: row ? 1 : 0 };
    }

    if (normalizedSql.startsWith("SELECT * FROM templates WHERE tenant_id = $1 AND notification_type = $2 AND locale = 'en' AND is_system_default = true")) {
      const store = dataStore.get('templates') || [];
      const row = store.find(
        (r: any) => r.tenant_id === params[0] && r.notification_type === params[1] && r.locale === 'en' && r.is_system_default === true
      );
      return { rows: row ? [row] : [], rowCount: row ? 1 : 0 };
    }

    if (normalizedSql.startsWith('SELECT * FROM templates WHERE tenant_id = $1 AND is_system_default = false ORDER BY notification_type, locale')) {
      const store = dataStore.get('templates') || [];
      const rows = store.filter((r: any) => r.tenant_id === params[0] && r.is_system_default === false);
      return { rows, rowCount: rows.length };
    }

    if (normalizedSql.startsWith('SELECT * FROM templates WHERE tenant_id = $1 AND is_system_default = true ORDER BY notification_type, locale')) {
      const store = dataStore.get('templates') || [];
      const rows = store.filter((r: any) => r.tenant_id === params[0] && r.is_system_default === true);
      return { rows, rowCount: rows.length };
    }

    if (normalizedSql.startsWith('UPDATE templates SET name = COALESCE($1, name)')) {
      const store = dataStore.get('templates') || [];
      const idx = store.findIndex((r: any) => r.id === params[5] && r.tenant_id === params[6] && r.is_system_default === false);
      if (idx !== -1) {
        store[idx] = {
          ...store[idx],
          name: params[0] ?? store[idx].name,
          subject_template: params[1] ?? store[idx].subject_template,
          body_template: params[2] ?? store[idx].body_template,
          html_template: params[3] ?? store[idx].html_template,
          variables: params[4] ?? store[idx].variables,
          updated_at: new Date(),
        };
        return { rows: [store[idx]], rowCount: 1 };
      }
      return { rows: [], rowCount: 0 };
    }

    if (normalizedSql.startsWith('DELETE FROM templates WHERE id = $1 AND tenant_id = $2 AND is_system_default = false')) {
      const store = dataStore.get('templates') || [];
      const idx = store.findIndex((r: any) => r.id === params[0] && r.tenant_id === params[1] && r.is_system_default === false);
      if (idx !== -1) {
        store.splice(idx, 1);
        return { rows: [], rowCount: 1 };
      }
      return { rows: [], rowCount: 0 };
    }

    if (normalizedSql.startsWith('DELETE FROM templates WHERE tenant_id = $1 AND notification_type = $2 AND locale = $3 AND is_system_default = false')) {
      const store = dataStore.get('templates') || [];
      const idx = store.findIndex(
        (r: any) => r.tenant_id === params[0] && r.notification_type === params[1] && r.locale === params[2] && r.is_system_default === false
      );
      if (idx !== -1) {
        store.splice(idx, 1);
        return { rows: [], rowCount: 1 };
      }
      return { rows: [], rowCount: 0 };
    }

    if (normalizedSql.startsWith('INSERT INTO templates (tenant_id, notification_type, locale, name, subject_template, body_template, html_template, variables)')) {
      const row = {
        id: uuidv4(),
        tenant_id: params[0],
        notification_type: params[1],
        locale: params[2],
        name: params[3],
        subject_template: params[4],
        body_template: params[5],
        html_template: params[6],
        variables: params[7],
        is_system_default: false,
        created_at: new Date(),
        updated_at: new Date(),
      };
      const store = dataStore.get('templates') || [];
      store.push(row);
      dataStore.set('templates', store);
      return { rows: [row], rowCount: 1 };
    }

    if (normalizedSql.startsWith('UPDATE templates SET name = COALESCE($1, name), subject_template = COALESCE($2, subject_template)') && !normalizedSql.includes('is_system_default')) {
      const store = dataStore.get('templates') || [];
      const idx = store.findIndex((r: any) => r.id === params[5]);
      if (idx !== -1) {
        store[idx] = {
          ...store[idx],
          name: params[0] ?? store[idx].name,
          subject_template: params[1] ?? store[idx].subject_template,
          body_template: params[2] ?? store[idx].body_template,
          html_template: params[3] ?? store[idx].html_template,
          variables: params[4] ?? store[idx].variables,
          updated_at: new Date(),
        };
        return { rows: [store[idx]], rowCount: 1 };
      }
      return { rows: [], rowCount: 0 };
    }

    return { rows: [], rowCount: 0 };
  });

  mockDb.setTenantContext = vi.fn().mockResolvedValue(undefined);
  mockDb.clearTenantContext = vi.fn().mockResolvedValue(undefined);
  mockDb.withTenantContext = vi.fn(async (tenantId: string, fn: () => Promise<any>) => {
    return await fn();
  });
  mockDb.getClient = vi.fn();
  mockDb.close = vi.fn().mockResolvedValue(undefined);
  mockDb._dataStore = dataStore;

  return mockDb;
}

export function mockFetch(
  url: string,
  method: string,
  status: number,
  responseBody: any,
  headers?: Record<string, string>
) {
  const mockResponse = new Response(JSON.stringify(responseBody), {
    status,
    headers: {
      'Content-Type': 'application/json',
      ...headers,
    },
  });

  vi.spyOn(global, 'fetch').mockImplementation(async (input, init) => {
    if (input === url && init?.method?.toUpperCase() === method.toUpperCase()) {
      return mockResponse;
    }
    return new Response('Not found', { status: 404 });
  });

  return mockResponse;
}

export function mockSMTPServer() {
  const receivedEmails: any[] = [];
  
  const mockTransporter = {
    sendMail: vi.fn(async (mailOptions: any) => {
      receivedEmails.push(mailOptions);
      return {
        messageId: `smtp-test-${Date.now()}`,
        response: '250 OK',
      };
    }),
    verify: vi.fn().mockResolvedValue(true),
  };

  return {
    mockTransporter,
    receivedEmails,
    getLastEmail: () => receivedEmails[receivedEmails.length - 1],
    clear: () => {
      receivedEmails.length = 0;
      mockTransporter.sendMail.mockClear();
    },
  };
}

export function createMockQueue() {
  const mockInstance = {
    enqueue: vi.fn().mockResolvedValue('test-job-id'),
    scheduleJob: vi.fn().mockResolvedValue('test-job-id'),
    getDlqJobs: vi.fn().mockResolvedValue([]),
    retryDlqJob: vi.fn().mockResolvedValue(undefined),
    getQueueStats: vi.fn().mockResolvedValue({ waiting: 0, active: 0, completed: 0, failed: 0 }),
    close: vi.fn().mockResolvedValue(undefined),
  };

  vi.spyOn(NotificationQueue, 'getInstance').mockReturnValue(mockInstance as any);

  return mockInstance;
}

export function createMockTracker() {
  const mockInstance = {
    createDeliveryLog: vi.fn().mockResolvedValue(undefined),
    updateStatus: vi.fn().mockResolvedValue(true),
    getByDeliveryId: vi.fn().mockResolvedValue([]),
    getByTenant: vi.fn().mockResolvedValue([]),
    getDeliveryStatistics: vi.fn().mockResolvedValue({
      total_sent: 0,
      delivery_rate: 0,
      open_rate: 0,
      click_rate: 0,
      channel_stats: [],
      latency_distribution: { p50: 0, p95: 0, p99: 0 },
      failure_reasons: [],
    }),
    getGroupedStatistics: vi.fn().mockResolvedValue([]),
    getLatencyPercentiles: vi.fn().mockResolvedValue({}),
    getDailyTrend: vi.fn().mockResolvedValue([]),
  };

  vi.spyOn(DeliveryTracker, 'getInstance').mockReturnValue(mockInstance as any);

  return mockInstance;
}

export function createMockTemplateEngine() {
  const mockInstance = {
    getTemplate: vi.fn().mockResolvedValue(null),
    renderTemplate: vi.fn().mockResolvedValue({ body: 'rendered content' }),
    renderTemplateById: vi.fn().mockResolvedValue({ body: 'rendered content' }),
    render: vi.fn().mockResolvedValue(null),
    createTenantTemplate: vi.fn().mockResolvedValue({}),
    createSystemTemplate: vi.fn().mockResolvedValue({}),
    updateTenantTemplate: vi.fn().mockResolvedValue(null),
    deleteTenantTemplate: vi.fn().mockResolvedValue(false),
    listTemplates: vi.fn().mockResolvedValue([]),
    resetTenantTemplateToDefault: vi.fn().mockResolvedValue(false),
    preview: vi.fn().mockResolvedValue({ body: 'preview content' }),
    clearCache: vi.fn(),
  };

  vi.spyOn(TemplateEngine, 'getInstance').mockReturnValue(mockInstance as any);

  return mockInstance;
}

export function createMockRouter() {
  const mockInstance = {
    route: vi.fn().mockResolvedValue({ channel: 'email', provider: 'smtp' }),
    getChannelPriority: vi.fn().mockReturnValue(['email', 'sms', 'push']),
    checkChannelHealth: vi.fn().mockResolvedValue(true),
  };

  vi.spyOn(NotificationRouter, 'getInstance').mockReturnValue(mockInstance as any);

  return mockInstance;
}
