"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ClickHouseVersionDetector = void 0;
const common_1 = require("@nestjs/common");
const base_detector_1 = require("./base-detector");
class MockClickHouseClient {
    constructor(config) {
        this.config = config;
        this.tables = new Map();
        this.versionCounter = new Map();
        this.insertTimer = null;
    }
    async query(sql) {
        sql = sql.trim();
        if (sql.includes('system.tables') || sql.includes('information_schema.tables')) {
            const tables = this.config.watchedTables ?? ['events', 'metrics', 'logs'];
            return {
                data: tables.map((name) => ({
                    name,
                    table: name,
                    database: this.config.database,
                    engine: 'MergeTree',
                })),
            };
        }
        const maxVersionMatch = sql.match(/^SELECT\s+max\((\w+)\)\s+AS\s+(\w+)\s+FROM\s+(\w+)/i);
        if (maxVersionMatch) {
            const tableName = maxVersionMatch[3];
            const table = this.tables.get(tableName) ?? [];
            const maxV = table.reduce((m, r) => Math.max(m, r.__version), 0);
            return { data: [{ max_v: maxV, v: maxV }] };
        }
        const selectAllMatch = sql.match(/^SELECT\s+\*\s+FROM\s+(\w+)\s+WHERE\s+(\w+)\s*>\s*(\d+)/i);
        if (selectAllMatch) {
            const tableName = selectAllMatch[1];
            const threshold = parseInt(selectAllMatch[3], 10);
            const table = this.tables.get(tableName) ?? [];
            const rows = table.filter((r) => r.__version > threshold);
            return { data: rows };
        }
        return { data: [] };
    }
    startMockInserts() {
        const tables = this.config.watchedTables ?? ['events', 'metrics', 'logs'];
        for (const t of tables) {
            if (!this.tables.has(t)) {
                this.tables.set(t, []);
                this.versionCounter.set(t, 0);
            }
        }
        this.insertTimer = setInterval(() => {
            const tableName = tables[Math.floor(Math.random() * tables.length)];
            this.insertMockRow(tableName);
        }, 12000);
    }
    insertMockRow(tableName) {
        const table = this.tables.get(tableName) ?? [];
        const currentV = (this.versionCounter.get(tableName) ?? 0) + 1;
        this.versionCounter.set(tableName, currentV);
        const row = {
            id: Math.floor(Math.random() * 1000000),
            name: `row_${currentV}`,
            value: Math.random() * 100,
            created_at: new Date().toISOString(),
            __version: currentV,
        };
        table.push(row);
    }
    close() {
        if (this.insertTimer) {
            clearInterval(this.insertTimer);
            this.insertTimer = null;
        }
    }
}
class ClickHouseVersionDetector extends base_detector_1.BaseChangeDetector {
    constructor(dataSourceId, config) {
        super();
        this.dataSourceId = dataSourceId;
        this.config = config;
        this.logger = new common_1.Logger(ClickHouseVersionDetector.name);
        this.checkIntervalMs = 5000;
        this.timer = null;
        this.lastVersions = new Map();
        this.client = null;
        this.versionField = config.versionField ?? '__version';
    }
    async start() {
        if (this.isRunning)
            return;
        this.isRunning = true;
        this.logger.log(`Starting ClickHouse version detector for dataSource ${this.dataSourceId} on ${this.config.host}:${this.config.port}/${this.config.database}`);
        this.client = new MockClickHouseClient(this.config);
        this.client.startMockInserts?.();
        try {
            await this.initializeTables();
            await this.initializeVersions();
        }
        catch (err) {
            this.logger.warn(`Initialization warning for dataSource ${this.dataSourceId}: ${err?.message ?? err}`);
        }
        this.timer = setInterval(() => {
            this.checkForChanges().catch((err) => {
                this.logger.error(`Error checking ClickHouse changes for dataSource ${this.dataSourceId}: ${err?.message ?? err}`);
            });
        }, this.checkIntervalMs);
        this.logger.log(`ClickHouse version detector started for dataSource ${this.dataSourceId}`);
    }
    async initializeTables() {
        const result = await this.client.query(`SELECT table FROM system.tables WHERE database = '${this.config.database}' AND engine LIKE '%MergeTree%'`);
        const tablesFromConfig = this.config.watchedTables;
        const tablesFromSystem = result.data.map((r) => r.table ?? r.name);
        const tables = tablesFromConfig && tablesFromConfig.length > 0
            ? tablesFromConfig
            : tablesFromSystem;
        if (tables.length === 0) {
            this.logger.warn(`No tables found for ClickHouse dataSource ${this.dataSourceId}`);
            return;
        }
        this.logger.log(`Watching ${tables.length} tables for dataSource ${this.dataSourceId}: ${tables.join(', ')}`);
    }
    async initializeVersions() {
        const tables = this.config.watchedTables ?? ['events', 'metrics', 'logs'];
        for (const table of tables) {
            try {
                const result = await this.client.query(`SELECT max(${this.versionField}) AS max_v FROM ${table}`);
                const maxV = Number(result.data?.[0]?.max_v ?? result.data?.[0]?.v ?? 0);
                this.lastVersions.set(table, maxV);
                this.logger.debug(`Initialized table ${table} for dataSource ${this.dataSourceId} at version ${maxV}`);
            }
            catch (err) {
                this.logger.debug(`Could not initialize version for table ${table} (dataSource ${this.dataSourceId}): ${err?.message ?? err}`);
                this.lastVersions.set(table, 0);
            }
        }
    }
    async checkForChanges() {
        if (!this.isRunning || !this.client)
            return;
        const tables = this.config.watchedTables ?? ['events', 'metrics', 'logs'];
        for (const table of tables) {
            try {
                const lastV = this.lastVersions.get(table) ?? 0;
                const rows = await this.fetchChangedRows(table, lastV);
                if (rows.length > 0) {
                    let newMaxV = lastV;
                    for (const row of rows) {
                        newMaxV = Math.max(newMaxV, Number(row[this.versionField] ?? 0));
                        this.emitRowChange(table, row);
                    }
                    this.lastVersions.set(table, newMaxV);
                    this.logger.debug(`Table ${table} (dataSource ${this.dataSourceId}): ${rows.length} new rows, version ${lastV} -> ${newMaxV}`);
                }
            }
            catch (err) {
                this.logger.warn(`Error checking table ${table} (dataSource ${this.dataSourceId}): ${err?.message ?? err}`);
            }
        }
    }
    async fetchChangedRows(table, lastV) {
        const result = await this.client.query(`SELECT * FROM ${table} WHERE ${this.versionField} > ${lastV}`);
        return result.data ?? [];
    }
    emitRowChange(tableName, row) {
        const pk = this.extractPrimaryKey(row);
        const cleanRow = { ...row };
        delete cleanRow[this.versionField];
        const event = {
            dataSourceId: this.dataSourceId,
            tableName,
            operation: 'INSERT',
            pk,
            afterData: cleanRow,
            timestamp: new Date(),
        };
        this.emit(event);
    }
    extractPrimaryKey(data) {
        const pk = {};
        const pkCandidates = ['id', 'ID', 'pk', 'PK', '_id', 'uuid'];
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
    async stop() {
        this.isRunning = false;
        if (this.timer) {
            clearInterval(this.timer);
            this.timer = null;
        }
        if (this.client) {
            this.client.close?.();
            this.client = null;
        }
        this.lastVersions.clear();
        this.listeners.clear();
        this.logger.log(`ClickHouse version detector stopped for dataSource ${this.dataSourceId}`);
    }
}
exports.ClickHouseVersionDetector = ClickHouseVersionDetector;
//# sourceMappingURL=clickhouse-version-detector.js.map