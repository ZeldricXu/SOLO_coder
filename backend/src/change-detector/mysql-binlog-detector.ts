import { EventEmitter } from 'events';
import { Logger } from '@nestjs/common';
import { BaseChangeDetector, ChangeEvent } from './base-detector';

interface MysqlConfig {
  host: string;
  port: number;
  database: string;
  username: string;
  password: string;
}

const WRITE_ROWS_EVENT_V2 = 'WRITE_ROWS_EVENT_V2';
const UPDATE_ROWS_EVENT_V2 = 'UPDATE_ROWS_EVENT_V2';
const DELETE_ROWS_EVENT_V2 = 'DELETE_ROWS_EVENT_V2';
const ROTATE_EVENT = 'ROTATE_EVENT';
const TABLE_MAP_EVENT = 'TABLE_MAP_EVENT';

class MockZongJi extends EventEmitter {
  private started = false;
  private tableMap: Map<number, { database: string; table: string; columns: any[] }> = new Map();
  private currentTableId = 1;
  private mockTimer: ReturnType<typeof setInterval> | null = null;

  constructor(private config: MysqlConfig) {
    super();
  }

  start(options: Record<string, any> = {}) {
    this.started = true;
    this.scheduleMockEvent();
  }

  stop() {
    this.started = false;
    if (this.mockTimer) {
      clearInterval(this.mockTimer);
      this.mockTimer = null;
    }
  }

  private scheduleMockEvent() {
    this.mockTimer = setInterval(() => {
      if (!this.started) return;
      this.emitMockEvent();
    }, 15000);
  }

  private emitMockEvent() {
    const tables = ['users', 'orders', 'products'];
    const operations = ['INSERT', 'UPDATE', 'DELETE'] as const;
    const tableName = tables[Math.floor(Math.random() * tables.length)];
    const operation = operations[Math.floor(Math.random() * operations.length)];

    this.registerTable(tableName);

    const tableId = this.getTableId(tableName);
    this.emit(TABLE_MAP_EVENT, {
      tableId,
      schema: this.config.database,
      tableName,
      columns: [{ name: 'id', type: 'number' }, { name: 'name', type: 'string' }],
    });

    const pk = { id: Math.floor(Math.random() * 10000) };
    const beforeData = { ...pk, name: 'old_value_' + Date.now() };
    const afterData = { ...pk, name: 'new_value_' + Date.now() };

    const rows: any[] = [];
    switch (operation) {
      case 'INSERT':
        rows.push({ after: afterData });
        this.emit(WRITE_ROWS_EVENT_V2, { tableId, rows });
        break;
      case 'UPDATE':
        rows.push({ before: beforeData, after: afterData });
        this.emit(UPDATE_ROWS_EVENT_V2, { tableId, rows });
        break;
      case 'DELETE':
        rows.push({ before: beforeData });
        this.emit(DELETE_ROWS_EVENT_V2, { tableId, rows });
        break;
    }
  }

  private registerTable(tableName: string) {
    const exists = Array.from(this.tableMap.values()).some((t) => t.table === tableName);
    if (!exists) {
      this.tableMap.set(this.currentTableId, {
        database: this.config.database,
        table: tableName,
        columns: [{ name: 'id', type: 'number' }, { name: 'name', type: 'string' }],
      });
      this.currentTableId++;
    }
  }

  private getTableId(tableName: string): number {
    for (const [id, info] of this.tableMap.entries()) {
      if (info.table === tableName) return id;
    }
    return 0;
  }
}

export class MysqlBinlogDetector extends BaseChangeDetector {
  private readonly logger = new Logger(MysqlBinlogDetector.name);
  private zongji: MockZongJi | null = null;
  private reconnectAttempts = 0;
  private readonly maxReconnectDelayMs = 30000;
  private readonly baseReconnectDelayMs = 1000;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly dataSourceId: string,
    private readonly config: MysqlConfig,
  ) {
    super();
  }

  async start(): Promise<void> {
    if (this.isRunning) return;
    this.isRunning = true;
    this.logger.log(
      `Starting MySQL binlog detector for dataSource ${this.dataSourceId} on ${this.config.host}:${this.config.port}/${this.config.database}`,
    );
    this.connect();
  }

  private connect() {
    try {
      this.zongji = new MockZongJi(this.config);

      this.zongji.on('error', (err: Error) => {
        this.logger.error(`ZongJi error for dataSource ${this.dataSourceId}: ${err.message}`);
        this.scheduleReconnect();
      });

      this.zongji.on(TABLE_MAP_EVENT, () => {
        // table map event, noop for now
      });

      this.zongji.on(ROTATE_EVENT, () => {
        this.logger.debug(`Binlog rotate for dataSource ${this.dataSourceId}`);
      });

      this.zongji.on(WRITE_ROWS_EVENT_V2, (event: any) => {
        this.handleRowsEvent(event, 'INSERT');
      });

      this.zongji.on(UPDATE_ROWS_EVENT_V2, (event: any) => {
        this.handleRowsEvent(event, 'UPDATE');
      });

      this.zongji.on(DELETE_ROWS_EVENT_V2, (event: any) => {
        this.handleRowsEvent(event, 'DELETE');
      });

      this.zongji.start({
        startAtEnd: true,
        includeSchema: { [this.config.database]: true },
        includeEvents: [
          ROTATE_EVENT,
          TABLE_MAP_EVENT,
          WRITE_ROWS_EVENT_V2,
          UPDATE_ROWS_EVENT_V2,
          DELETE_ROWS_EVENT_V2,
        ],
      });

      this.reconnectAttempts = 0;
      this.logger.log(`MySQL binlog detector connected for dataSource ${this.dataSourceId}`);
    } catch (err: any) {
      this.logger.error(
        `Failed to start ZongJi for dataSource ${this.dataSourceId}: ${err?.message ?? err}`,
      );
      this.scheduleReconnect();
    }
  }

  private handleRowsEvent(
    event: { tableId: number; rows: Array<{ before?: Record<string, any>; after?: Record<string, any> }> },
    operation: 'INSERT' | 'UPDATE' | 'DELETE',
  ) {
    const tableMap = (this.zongji as any)?.tableMap;
    const tableInfo = tableMap?.get?.(event.tableId);
    const tableName = tableInfo?.table ?? 'unknown';

    for (const row of event.rows) {
      const beforeData = row.before;
      const afterData = row.after;
      const dataForPk = afterData ?? beforeData ?? {};
      const pk = this.extractPrimaryKey(dataForPk);

      const changeEvent: ChangeEvent = {
        dataSourceId: this.dataSourceId,
        tableName,
        operation,
        pk,
        beforeData,
        afterData,
        timestamp: new Date(),
      };

      this.emit(changeEvent);
    }
  }

  private extractPrimaryKey(data: Record<string, any>): Record<string, any> {
    const pk: Record<string, any> = {};
    const pkCandidates = ['id', 'ID', 'pk', 'PK', '_id'];
    for (const key of pkCandidates) {
      if (data[key] !== undefined) {
        pk[key] = data[key];
      }
    }
    if (Object.keys(pk).length === 0 && Object.keys(data).length > 0) {
      const firstKey = Object.keys(data)[0];
      pk[firstKey] = data[firstKey];
    }
    return pk;
  }

  private scheduleReconnect() {
    if (!this.isRunning) return;
    if (this.reconnectTimer) return;

    this.reconnectAttempts++;
    const delay = Math.min(
      this.baseReconnectDelayMs * Math.pow(2, this.reconnectAttempts - 1),
      this.maxReconnectDelayMs,
    );

    this.logger.warn(
      `Scheduling reconnect for dataSource ${this.dataSourceId} in ${delay}ms (attempt ${this.reconnectAttempts})`,
    );

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.cleanupZongji();
      this.connect();
    }, delay);
  }

  private cleanupZongji() {
    if (this.zongji) {
      try {
        this.zongji.stop();
      } catch {
        // ignore
      }
      this.zongji.removeAllListeners();
      this.zongji = null;
    }
  }

  async stop(): Promise<void> {
    this.isRunning = false;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.cleanupZongji();
    this.listeners.clear();
    this.logger.log(`MySQL binlog detector stopped for dataSource ${this.dataSourceId}`);
  }
}
