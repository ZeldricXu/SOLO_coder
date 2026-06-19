"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const client_1 = require("@prisma/client");
const detector_factory_1 = require("./detector-factory");
const mysql_binlog_detector_1 = require("./mysql-binlog-detector");
const clickhouse_version_detector_1 = require("./clickhouse-version-detector");
const base_detector_1 = require("./base-detector");
describe('Change Detector 模块', () => {
    describe('BaseChangeDetector', () => {
        class TestDetector extends base_detector_1.BaseChangeDetector {
            async start() { this.isRunning = true; }
            async stop() { this.isRunning = false; this.listeners.clear(); }
            trigger(e) { this.emit(e); }
        }
        let detector;
        beforeEach(() => {
            detector = new TestDetector();
        });
        it('onEvent 注册监听器并返回取消订阅函数', () => {
            const listener = jest.fn();
            const unsub = detector.onEvent(listener);
            detector.trigger({
                dataSourceId: 'ds_1',
                tableName: 'orders',
                operation: 'INSERT',
                timestamp: new Date(),
                afterData: { id: 1, amount: 100 },
            });
            expect(listener).toHaveBeenCalledTimes(1);
            expect(listener.mock.calls[0][0].tableName).toBe('orders');
            unsub();
            detector.trigger({
                dataSourceId: 'ds_1',
                tableName: 'users',
                operation: 'INSERT',
                timestamp: new Date(),
            });
            expect(listener).toHaveBeenCalledTimes(1);
        });
        it('emit 时单个 listener 抛出异常不应影响其他 listener', () => {
            const good = jest.fn();
            const bad = jest.fn(() => { throw new Error('boom'); });
            detector.onEvent(bad);
            detector.onEvent(good);
            detector.trigger({
                dataSourceId: 'ds_1',
                tableName: 't',
                operation: 'INSERT',
                timestamp: new Date(),
            });
            expect(bad).toHaveBeenCalled();
            expect(good).toHaveBeenCalled();
        });
        it('start 后 isRunning=true，stop 后为 false', async () => {
            expect(detector['isRunning']).toBe(false);
            await detector.start();
            expect(detector['isRunning']).toBe(true);
            await detector.stop();
            expect(detector['isRunning']).toBe(false);
        });
    });
    describe('DetectorFactory', () => {
        it('MYSQL → MysqlBinlogDetector', () => {
            const d = detector_factory_1.DetectorFactory.create(client_1.DataSourceType.MYSQL, 'ds_1', {
                host: 'localhost', port: 3306, database: 'test',
                username: 'root', password: '123',
            });
            expect(d).toBeInstanceOf(mysql_binlog_detector_1.MysqlBinlogDetector);
        });
        it('CLICKHOUSE → ClickHouseVersionDetector', () => {
            const d = detector_factory_1.DetectorFactory.create(client_1.DataSourceType.CLICKHOUSE, 'ds_ch', {
                host: 'localhost', port: 8123, database: 'test',
            });
            expect(d).toBeInstanceOf(clickhouse_version_detector_1.ClickHouseVersionDetector);
        });
        it('POSTGRESQL / HTTP_API → Error', () => {
            expect(() => detector_factory_1.DetectorFactory.create(client_1.DataSourceType.POSTGRESQL, 'ds_pg', {}))
                .toThrow(/Change detection not supported/);
            expect(() => detector_factory_1.DetectorFactory.create(client_1.DataSourceType.HTTP_API, 'ds_http', {}))
                .toThrow(/Change detection not supported/);
        });
    });
    describe('MysqlBinlogDetector', () => {
        let detector;
        const config = {
            host: 'localhost', port: 3306, database: 'test',
            username: 'root', password: '123',
        };
        beforeEach(() => {
            detector = new mysql_binlog_detector_1.MysqlBinlogDetector('ds_mysql', config);
        });
        it('start 后接收 WRITE_ROWS_EVENT_V2 并映射为 INSERT ChangeEvent', async () => {
            const listener = jest.fn();
            detector.onEvent(listener);
            await detector.start();
            const zongji = detector.zongji;
            zongji.emit('binlog', {
                getEventName: () => 'WRITE_ROWS_EVENT_V2',
                tableMap: { tableName: 'orders' },
                rows: [
                    { id: 1, amount: 299, channel: 'taobao', before: null, after: { id: 1, amount: 299 } },
                    { id: 2, amount: 199, before: null, after: { id: 2, amount: 199 } },
                ],
            });
            expect(listener).toHaveBeenCalledTimes(2);
            const first = listener.mock.calls[0][0];
            expect(first.operation).toBe('INSERT');
            expect(first.tableName).toBe('orders');
            expect(first.afterData.amount).toBe(299);
            expect(first.pk).toEqual({ id: 1 });
            await detector.stop();
        });
        it('start 后接收 UPDATE_ROWS_EVENT_V2 并映射为 UPDATE ChangeEvent', async () => {
            const listener = jest.fn();
            detector.onEvent(listener);
            await detector.start();
            const zongji = detector.zongji;
            zongji.emit('binlog', {
                getEventName: () => 'UPDATE_ROWS_EVENT_V2',
                tableMap: { tableName: 'orders' },
                rows: [
                    { before: { id: 1, status: 'pending' }, after: { id: 1, status: 'paid' } },
                ],
            });
            expect(listener).toHaveBeenCalledTimes(1);
            const ev = listener.mock.calls[0][0];
            expect(ev.operation).toBe('UPDATE');
            expect(ev.beforeData.status).toBe('pending');
            expect(ev.afterData.status).toBe('paid');
            await detector.stop();
        });
        it('start 后接收 DELETE_ROWS_EVENT_V2 并映射为 DELETE ChangeEvent', async () => {
            const listener = jest.fn();
            detector.onEvent(listener);
            await detector.start();
            const zongji = detector.zongji;
            zongji.emit('binlog', {
                getEventName: () => 'DELETE_ROWS_EVENT_V2',
                tableMap: { tableName: 'users' },
                rows: [
                    { before: { id: 99, email: 'spam@test.com' } },
                ],
            });
            expect(listener).toHaveBeenCalledTimes(1);
            const ev = listener.mock.calls[0][0];
            expect(ev.operation).toBe('DELETE');
            expect(ev.beforeData.id).toBe(99);
            await detector.stop();
        });
        it('连接错误触发指数退避重连，调用重试方法', async () => {
            await detector.start();
            const zongji = detector.zongji;
            const retrySpy = jest.spyOn(detector, 'scheduleReconnect');
            zongji.emit('error', new Error('MySQL server has gone away'));
            expect(retrySpy).toHaveBeenCalled();
            expect(detector.retryCount).toBeGreaterThanOrEqual(1);
            await detector.stop();
            retrySpy.mockRestore();
        });
    });
    describe('ClickHouseVersionDetector', () => {
        let detector;
        const config = {
            host: 'localhost', port: 8123, database: 'test',
            username: 'default', password: '', versionField: '__version',
            watchedTables: ['orders', 'users'],
        };
        beforeEach(() => {
            detector = new clickhouse_version_detector_1.ClickHouseVersionDetector('ds_ch', config);
        });
        it('start 后初始化版本号，并按 checkInterval 轮询', async () => {
            jest.useFakeTimers();
            const listener = jest.fn();
            detector.onEvent(listener);
            await detector.start();
            const mockClient = detector.client;
            const initialQuery = mockClient.query.mock.calls.find((c) => c && c[0] && typeof c[0] === 'string' && c[0].includes('max('));
            expect(initialQuery).toBeDefined();
            const newerVersion = 1000;
            mockClient.query.mockReset();
            mockClient.query.mockResolvedValueOnce([{ table: 'orders', max_v: newerVersion }]);
            mockClient.query.mockResolvedValueOnce([
                { __version: newerVersion, id: 1, amount: 300 },
                { __version: newerVersion, id: 2, amount: 150 },
            ]);
            jest.advanceTimersByTime(5000);
            await Promise.resolve();
            expect(listener).toHaveBeenCalledTimes(2);
            expect(listener.mock.calls[0][0].operation).toBe('INSERT');
            expect(listener.mock.calls[0][0].afterData.amount).toBe(300);
            jest.useRealTimers();
            await detector.stop();
        });
        it('无变更时 listener 不被调用', async () => {
            jest.useFakeTimers();
            const listener = jest.fn();
            detector.onEvent(listener);
            await detector.start();
            const mockClient = detector.client;
            mockClient.query.mockReset();
            mockClient.query.mockResolvedValue([]);
            jest.advanceTimersByTime(20000);
            await Promise.resolve();
            expect(listener).not.toHaveBeenCalled();
            jest.useRealTimers();
            await detector.stop();
        });
        it('stop 清除定时器并清空监听器', async () => {
            const listener = jest.fn();
            detector.onEvent(listener);
            await detector.start();
            expect(detector['timer']).not.toBeNull();
            await detector.stop();
            expect(detector['timer']).toBeNull();
            expect(detector['listeners'].size).toBe(0);
        });
    });
});
//# sourceMappingURL=change-detector.spec.js.map