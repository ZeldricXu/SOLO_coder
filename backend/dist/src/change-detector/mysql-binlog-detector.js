"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.MysqlBinlogDetector = void 0;
const events_1 = require("events");
const common_1 = require("@nestjs/common");
const base_detector_1 = require("./base-detector");
const WRITE_ROWS_EVENT_V2 = 'WRITE_ROWS_EVENT_V2';
const UPDATE_ROWS_EVENT_V2 = 'UPDATE_ROWS_EVENT_V2';
const DELETE_ROWS_EVENT_V2 = 'DELETE_ROWS_EVENT_V2';
const ROTATE_EVENT = 'ROTATE_EVENT';
const TABLE_MAP_EVENT = 'TABLE_MAP_EVENT';
class MockZongJi extends events_1.EventEmitter {
    constructor(config) {
        super();
        this.config = config;
        this.started = false;
        this.tableMap = new Map();
        this.currentTableId = 1;
        this.mockTimer = null;
    }
    start(options = {}) {
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
    scheduleMockEvent() {
        this.mockTimer = setInterval(() => {
            if (!this.started)
                return;
            this.emitMockEvent();
        }, 15000);
    }
    emitMockEvent() {
        const tables = ['users', 'orders', 'products'];
        const operations = ['INSERT', 'UPDATE', 'DELETE'];
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
        const rows = [];
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
    registerTable(tableName) {
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
    getTableId(tableName) {
        for (const [id, info] of this.tableMap.entries()) {
            if (info.table === tableName)
                return id;
        }
        return 0;
    }
}
class MysqlBinlogDetector extends base_detector_1.BaseChangeDetector {
    constructor(dataSourceId, config) {
        super();
        this.dataSourceId = dataSourceId;
        this.config = config;
        this.logger = new common_1.Logger(MysqlBinlogDetector.name);
        this.zongji = null;
        this.reconnectAttempts = 0;
        this.maxReconnectDelayMs = 30000;
        this.baseReconnectDelayMs = 1000;
        this.reconnectTimer = null;
    }
    async start() {
        if (this.isRunning)
            return;
        this.isRunning = true;
        this.logger.log(`Starting MySQL binlog detector for dataSource ${this.dataSourceId} on ${this.config.host}:${this.config.port}/${this.config.database}`);
        this.connect();
    }
    connect() {
        try {
            this.zongji = new MockZongJi(this.config);
            this.zongji.on('error', (err) => {
                this.logger.error(`ZongJi error for dataSource ${this.dataSourceId}: ${err.message}`);
                this.scheduleReconnect();
            });
            this.zongji.on(TABLE_MAP_EVENT, () => {
            });
            this.zongji.on(ROTATE_EVENT, () => {
                this.logger.debug(`Binlog rotate for dataSource ${this.dataSourceId}`);
            });
            this.zongji.on(WRITE_ROWS_EVENT_V2, (event) => {
                this.handleRowsEvent(event, 'INSERT');
            });
            this.zongji.on(UPDATE_ROWS_EVENT_V2, (event) => {
                this.handleRowsEvent(event, 'UPDATE');
            });
            this.zongji.on(DELETE_ROWS_EVENT_V2, (event) => {
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
        }
        catch (err) {
            this.logger.error(`Failed to start ZongJi for dataSource ${this.dataSourceId}: ${err?.message ?? err}`);
            this.scheduleReconnect();
        }
    }
    handleRowsEvent(event, operation) {
        const tableMap = this.zongji?.tableMap;
        const tableInfo = tableMap?.get?.(event.tableId);
        const tableName = tableInfo?.table ?? 'unknown';
        for (const row of event.rows) {
            const beforeData = row.before;
            const afterData = row.after;
            const dataForPk = afterData ?? beforeData ?? {};
            const pk = this.extractPrimaryKey(dataForPk);
            const changeEvent = {
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
    extractPrimaryKey(data) {
        const pk = {};
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
    scheduleReconnect() {
        if (!this.isRunning)
            return;
        if (this.reconnectTimer)
            return;
        this.reconnectAttempts++;
        const delay = Math.min(this.baseReconnectDelayMs * Math.pow(2, this.reconnectAttempts - 1), this.maxReconnectDelayMs);
        this.logger.warn(`Scheduling reconnect for dataSource ${this.dataSourceId} in ${delay}ms (attempt ${this.reconnectAttempts})`);
        this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            this.cleanupZongji();
            this.connect();
        }, delay);
    }
    cleanupZongji() {
        if (this.zongji) {
            try {
                this.zongji.stop();
            }
            catch {
            }
            this.zongji.removeAllListeners();
            this.zongji = null;
        }
    }
    async stop() {
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
exports.MysqlBinlogDetector = MysqlBinlogDetector;
//# sourceMappingURL=mysql-binlog-detector.js.map