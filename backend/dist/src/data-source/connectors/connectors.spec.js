"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const mysql_connector_1 = require("./mysql.connector");
const clickhouse_connector_1 = require("./clickhouse.connector");
const postgres_connector_1 = require("./postgres.connector");
const http_api_connector_1 = require("./http-api.connector");
const connector_factory_1 = require("./connector-factory");
const client_1 = require("@prisma/client");
jest.mock('mysql2/promise', () => ({
    createPool: jest.fn(() => ({
        query: jest.fn(),
        end: jest.fn().mockResolvedValue(undefined),
    })),
}));
jest.mock('@clickhouse/client', () => ({
    createClient: jest.fn(() => ({
        query: jest.fn(),
        close: jest.fn().mockResolvedValue(undefined),
    })),
}));
jest.mock('pg', () => ({
    Pool: jest.fn(() => ({
        query: jest.fn(),
        end: jest.fn().mockResolvedValue(undefined),
    })),
}));
jest.mock('axios', () => {
    const mockAxiosInstance = {
        request: jest.fn(),
        interceptors: { request: { use: jest.fn() }, response: { use: jest.fn() } },
    };
    return {
        default: {
            create: jest.fn(() => mockAxiosInstance),
            isAxiosError: jest.fn((e) => e?.isAxiosError),
        },
        __esModule: true,
    };
});
describe('数据源连接器 - 连接与重连逻辑', () => {
    describe('MysqlConnector', () => {
        const mysql = require('mysql2/promise');
        beforeEach(() => {
            jest.clearAllMocks();
        });
        it('应该成功创建连接池', async () => {
            const connector = new mysql_connector_1.MysqlConnector({
                host: 'localhost',
                port: 3306,
                database: 'test',
                username: 'root',
                password: 'password',
                poolSize: 5,
            });
            await connector.connect();
            expect(mysql.createPool).toHaveBeenCalledWith({
                host: 'localhost',
                port: 3306,
                user: 'root',
                password: 'password',
                database: 'test',
                connectionLimit: 5,
            });
        });
        it('应该执行查询并返回结果', async () => {
            const connector = new mysql_connector_1.MysqlConnector({
                host: 'localhost',
                port: 3306,
                database: 'test',
                username: 'root',
                password: 'password',
            });
            const mockRows = [{ id: 1, name: 'test' }];
            const mockFields = [{ name: 'id', type: 3 }, { name: 'name', type: 253 }];
            const pool = { query: jest.fn().mockResolvedValue([mockRows, mockFields]), end: jest.fn() };
            mysql.createPool.mockReturnValue(pool);
            await connector.connect();
            const result = await connector.query('SELECT * FROM users');
            expect(pool.query).toHaveBeenCalledWith('SELECT * FROM users', undefined);
            expect(result.rows).toEqual(mockRows);
            expect(result.fields).toHaveLength(2);
            expect(result.rowCount).toBe(1);
        });
        it('testConnection 成功应该返回 true', async () => {
            const connector = new mysql_connector_1.MysqlConnector({
                host: 'localhost',
                port: 3306,
                database: 'test',
                username: 'root',
                password: 'password',
            });
            const pool = { query: jest.fn().mockResolvedValue([[], []]), end: jest.fn() };
            mysql.createPool.mockReturnValue(pool);
            const success = await connector.testConnection();
            expect(success).toBe(true);
            expect(pool.query).toHaveBeenCalledWith('SELECT 1');
        });
        it('testConnection 失败应该返回 false', async () => {
            const connector = new mysql_connector_1.MysqlConnector({
                host: 'localhost',
                port: 3306,
                database: 'test',
                username: 'root',
                password: 'password',
            });
            const pool = { query: jest.fn().mockRejectedValue(new Error('Connection refused')), end: jest.fn() };
            mysql.createPool.mockReturnValue(pool);
            const success = await connector.testConnection();
            expect(success).toBe(false);
        });
        it('inferSchema 应该返回数据库表结构', async () => {
            const connector = new mysql_connector_1.MysqlConnector({
                host: 'localhost',
                port: 3306,
                database: 'test',
                username: 'root',
                password: 'password',
            });
            const pool = {
                query: jest.fn()
                    .mockResolvedValueOnce([[{ TABLE_NAME: 'users' }, { TABLE_NAME: 'orders' }], []])
                    .mockResolvedValueOnce([
                    [{ COLUMN_NAME: 'id', DATA_TYPE: 'int', IS_NULLABLE: 'NO' },
                        { COLUMN_NAME: 'name', DATA_TYPE: 'varchar', IS_NULLABLE: 'YES' }],
                    [],
                ])
                    .mockResolvedValueOnce([
                    [{ COLUMN_NAME: 'id', DATA_TYPE: 'int', IS_NULLABLE: 'NO' },
                        { COLUMN_NAME: 'user_id', DATA_TYPE: 'int', IS_NULLABLE: 'NO' }],
                    [],
                ]),
                end: jest.fn(),
            };
            mysql.createPool.mockReturnValue(pool);
            await connector.connect();
            const schema = await connector.inferSchema();
            expect(schema).toHaveLength(2);
            expect(schema[0].table).toBe('users');
            expect(schema[0].columns).toHaveLength(2);
            expect(schema[0].columns[0].name).toBe('id');
            expect(schema[0].columns[0].type).toBe('number');
        });
        it('disconnect 应该关闭连接池', async () => {
            const connector = new mysql_connector_1.MysqlConnector({
                host: 'localhost',
                port: 3306,
                database: 'test',
                username: 'root',
                password: 'password',
            });
            const pool = { end: jest.fn().mockResolvedValue(undefined) };
            mysql.createPool.mockReturnValue(pool);
            await connector.connect();
            await connector.disconnect();
            expect(pool.end).toHaveBeenCalled();
        });
        it('断开后重连应该可以恢复正常', async () => {
            const connector = new mysql_connector_1.MysqlConnector({
                host: 'localhost',
                port: 3306,
                database: 'test',
                username: 'root',
                password: 'password',
            });
            const pool1 = {
                query: jest.fn().mockResolvedValue([[{ id: 1 }], []]),
                end: jest.fn().mockResolvedValue(undefined),
            };
            const pool2 = {
                query: jest.fn().mockResolvedValue([[{ id: 2 }], []]),
                end: jest.fn().mockResolvedValue(undefined),
            };
            mysql.createPool
                .mockReturnValueOnce(pool1)
                .mockReturnValueOnce(pool2);
            await connector.connect();
            const result1 = await connector.query('SELECT 1');
            expect(result1.rows).toEqual([{ id: 1 }]);
            await connector.disconnect();
            expect(pool1.end).toHaveBeenCalled();
            await connector.connect();
            const result2 = await connector.query('SELECT 1');
            expect(result2.rows).toEqual([{ id: 2 }]);
        });
    });
    describe('ClickHouseConnector', () => {
        const clickhouse = require('@clickhouse/client');
        beforeEach(() => {
            jest.clearAllMocks();
        });
        it('应该成功创建 ClickHouse 客户端', async () => {
            const connector = new clickhouse_connector_1.ClickHouseConnector({
                host: 'localhost',
                port: 8123,
                database: 'default',
                username: 'default',
                password: 'password',
            });
            await connector.connect();
            expect(clickhouse.createClient).toHaveBeenCalledWith({
                host: 'http://localhost:8123',
                database: 'default',
                username: 'default',
                password: 'password',
            });
        });
        it('应该执行查询并返回 JSON 结果', async () => {
            const connector = new clickhouse_connector_1.ClickHouseConnector({
                host: 'localhost',
                port: 8123,
                database: 'default',
                username: 'default',
                password: '',
            });
            const mockRows = [{ id: 1, name: 'test' }];
            const mockResultSet = {
                json: jest.fn().mockResolvedValue(mockRows),
            };
            const client = {
                query: jest.fn().mockResolvedValue(mockResultSet),
                close: jest.fn().mockResolvedValue(undefined),
            };
            clickhouse.createClient.mockReturnValue(client);
            await connector.connect();
            const result = await connector.query('SELECT * FROM events');
            expect(client.query).toHaveBeenCalledWith({
                query: 'SELECT * FROM events',
                format: 'JSONEachRow',
            });
            expect(result.rows).toEqual(mockRows);
            expect(result.rowCount).toBe(1);
        });
        it('testConnection 成功返回 true', async () => {
            const connector = new clickhouse_connector_1.ClickHouseConnector({
                host: 'localhost',
                port: 8123,
                database: 'default',
                username: 'default',
                password: '',
            });
            const client = {
                query: jest.fn().mockResolvedValue({ json: jest.fn().mockResolvedValue([]) }),
                close: jest.fn(),
            };
            clickhouse.createClient.mockReturnValue(client);
            const success = await connector.testConnection();
            expect(success).toBe(true);
        });
        it('testConnection 失败返回 false', async () => {
            const connector = new clickhouse_connector_1.ClickHouseConnector({
                host: 'localhost',
                port: 8123,
                database: 'default',
                username: 'default',
                password: '',
            });
            const client = {
                query: jest.fn().mockRejectedValue(new Error('Connection refused')),
                close: jest.fn(),
            };
            clickhouse.createClient.mockReturnValue(client);
            const success = await connector.testConnection();
            expect(success).toBe(false);
        });
        it('inferSchema 应该返回 ClickHouse 表结构', async () => {
            const connector = new clickhouse_connector_1.ClickHouseConnector({
                host: 'localhost',
                port: 8123,
                database: 'default',
                username: 'default',
                password: '',
            });
            const mockTables = [{ name: 'events' }, { name: 'users' }];
            const mockColumns = [
                { name: 'id', type: 'UInt64', is_nullable: 0 },
                { name: 'name', type: 'String', is_nullable: 1 },
            ];
            const client = {
                query: jest.fn()
                    .mockResolvedValueOnce({ json: jest.fn().mockResolvedValue(mockTables) })
                    .mockResolvedValueOnce({ json: jest.fn().mockResolvedValue(mockColumns) })
                    .mockResolvedValueOnce({ json: jest.fn().mockResolvedValue(mockColumns) }),
                close: jest.fn(),
            };
            clickhouse.createClient.mockReturnValue(client);
            await connector.connect();
            const schema = await connector.inferSchema();
            expect(schema).toHaveLength(2);
            expect(schema[0].table).toBe('events');
            expect(schema[0].columns).toHaveLength(2);
            expect(schema[0].columns[0].type).toBe('number');
            expect(schema[0].columns[1].type).toBe('string');
            expect(schema[0].columns[1].nullable).toBe(true);
        });
        it('断开后重连应该可以恢复', async () => {
            const connector = new clickhouse_connector_1.ClickHouseConnector({
                host: 'localhost',
                port: 8123,
                database: 'default',
                username: 'default',
                password: '',
            });
            const client1 = { close: jest.fn(), query: jest.fn().mockResolvedValue({ json: () => Promise.resolve([{ v: 1 }]) }) };
            const client2 = { close: jest.fn(), query: jest.fn().mockResolvedValue({ json: () => Promise.resolve([{ v: 2 }]) }) };
            clickhouse.createClient
                .mockReturnValueOnce(client1)
                .mockReturnValueOnce(client2);
            await connector.connect();
            await connector.disconnect();
            expect(client1.close).toHaveBeenCalled();
            await connector.connect();
            const result = await connector.query('SELECT 1');
            expect(result.rowCount).toBe(1);
        });
    });
    describe('PostgresConnector', () => {
        const { Pool } = require('pg');
        beforeEach(() => {
            jest.clearAllMocks();
        });
        it('应该创建 PostgreSQL 连接池', async () => {
            const connector = new postgres_connector_1.PostgresConnector({
                host: 'localhost',
                port: 5432,
                database: 'test',
                username: 'postgres',
                password: 'password',
                poolSize: 10,
            });
            await connector.connect();
            expect(Pool).toHaveBeenCalledWith({
                host: 'localhost',
                port: 5432,
                database: 'test',
                user: 'postgres',
                password: 'password',
                max: 10,
            });
        });
        it('应该执行查询并返回结果', async () => {
            const connector = new postgres_connector_1.PostgresConnector({
                host: 'localhost',
                port: 5432,
                database: 'test',
                username: 'postgres',
                password: 'password',
            });
            const mockRows = [{ id: 1, name: 'test' }];
            const mockFields = [{ name: 'id', dataTypeID: 23 }, { name: 'name', dataTypeID: 1043 }];
            const pool = {
                query: jest.fn().mockResolvedValue({ rows: mockRows, fields: mockFields, rowCount: 1 }),
                end: jest.fn().mockResolvedValue(undefined),
            };
            Pool.mockImplementation(() => pool);
            await connector.connect();
            const result = await connector.query('SELECT * FROM users');
            expect(pool.query).toHaveBeenCalledWith('SELECT * FROM users', undefined);
            expect(result.rows).toEqual(mockRows);
            expect(result.rowCount).toBe(1);
        });
        it('testConnection 成功返回 true', async () => {
            const connector = new postgres_connector_1.PostgresConnector({
                host: 'localhost',
                port: 5432,
                database: 'test',
                username: 'postgres',
                password: 'password',
            });
            const pool = { query: jest.fn().mockResolvedValue({ rows: [] }), end: jest.fn() };
            Pool.mockImplementation(() => pool);
            const success = await connector.testConnection();
            expect(success).toBe(true);
        });
        it('testConnection 失败返回 false', async () => {
            const connector = new postgres_connector_1.PostgresConnector({
                host: 'localhost',
                port: 5432,
                database: 'test',
                username: 'postgres',
                password: 'password',
            });
            const pool = { query: jest.fn().mockRejectedValue(new Error('Connection refused')) };
            Pool.mockImplementation(() => pool);
            const success = await connector.testConnection();
            expect(success).toBe(false);
        });
        it('disconnect 关闭连接池', async () => {
            const connector = new postgres_connector_1.PostgresConnector({
                host: 'localhost',
                port: 5432,
                database: 'test',
                username: 'postgres',
                password: 'password',
            });
            const pool = { end: jest.fn().mockResolvedValue(undefined) };
            Pool.mockImplementation(() => pool);
            await connector.connect();
            await connector.disconnect();
            expect(pool.end).toHaveBeenCalled();
        });
        it('重连后可以正常查询', async () => {
            const connector = new postgres_connector_1.PostgresConnector({
                host: 'localhost',
                port: 5432,
                database: 'test',
                username: 'postgres',
                password: 'password',
            });
            const pool1 = { query: jest.fn().mockResolvedValue({ rows: [{ v: 1 }], fields: [] }), end: jest.fn() };
            const pool2 = { query: jest.fn().mockResolvedValue({ rows: [{ v: 2 }], fields: [] }), end: jest.fn() };
            Pool.mockImplementationOnce(() => pool1)
                .mockImplementationOnce(() => pool2);
            await connector.connect();
            await connector.disconnect();
            await connector.connect();
            const result = await connector.query('SELECT 1');
            expect(result.rows).toEqual([{ v: 2 }]);
        });
    });
    describe('HttpApiConnector', () => {
        const axios = require('axios').default;
        beforeEach(() => {
            jest.clearAllMocks();
        });
        it('应该创建 axios 实例', async () => {
            const connector = new http_api_connector_1.HttpApiConnector({
                url: 'https://api.example.com',
                method: 'GET',
                queryTimeout: 10000,
            });
            await connector.connect();
            expect(axios.create).toHaveBeenCalledWith({
                baseURL: 'https://api.example.com',
                headers: undefined,
                timeout: 10000,
            });
        });
        it('GET 请求应该返回数据', async () => {
            const connector = new http_api_connector_1.HttpApiConnector({
                url: 'https://api.example.com',
                method: 'GET',
            });
            const mockInstance = {
                request: jest.fn().mockResolvedValue({ data: [{ id: 1 }, { id: 2 }] }),
            };
            axios.create.mockReturnValue(mockInstance);
            await connector.connect();
            const result = await connector.query('/data');
            expect(mockInstance.request).toHaveBeenCalledWith({
                method: 'GET',
                url: '/data',
            });
            expect(result.rows).toHaveLength(2);
            expect(result.rowCount).toBe(2);
        });
        it('POST 请求应该带 body', async () => {
            const connector = new http_api_connector_1.HttpApiConnector({
                url: 'https://api.example.com',
                method: 'POST',
                body: { key: 'value' },
            });
            const mockInstance = {
                request: jest.fn().mockResolvedValue({ data: { success: true } }),
            };
            axios.create.mockReturnValue(mockInstance);
            await connector.connect();
            const result = await connector.query('/submit', [{ extra: 'data' }]);
            expect(mockInstance.request).toHaveBeenCalledWith(expect.objectContaining({
                method: 'POST',
                url: '/submit',
                data: { extra: 'data' },
            }));
            expect(result.rows).toHaveLength(1);
        });
        it('testConnection 成功时返回 true (2xx)', async () => {
            const connector = new http_api_connector_1.HttpApiConnector({
                url: 'https://api.example.com',
                method: 'GET',
            });
            const mockInstance = {
                request: jest.fn().mockResolvedValue({ status: 200 }),
            };
            axios.create.mockReturnValue(mockInstance);
            const success = await connector.testConnection();
            expect(success).toBe(true);
        });
        it('testConnection 3xx 重定向也返回 true（有响应即可连通）', async () => {
            const connector = new http_api_connector_1.HttpApiConnector({
                url: 'https://api.example.com',
                method: 'GET',
            });
            const mockInstance = {
                request: jest.fn().mockResolvedValue({ status: 302 }),
            };
            axios.create.mockReturnValue(mockInstance);
            const success = await connector.testConnection();
            expect(success).toBe(true);
        });
        it('testConnection 网络错误返回 false', async () => {
            const connector = new http_api_connector_1.HttpApiConnector({
                url: 'https://api.example.com',
                method: 'GET',
            });
            const mockInstance = {
                request: jest.fn().mockRejectedValue(new Error('Network error')),
            };
            axios.create.mockReturnValue(mockInstance);
            const success = await connector.testConnection();
            expect(success).toBe(false);
        });
        it('inferSchema 应该从返回数据推断字段类型', async () => {
            const connector = new http_api_connector_1.HttpApiConnector({
                url: 'https://api.example.com',
                method: 'GET',
            });
            const mockInstance = {
                request: jest.fn().mockResolvedValue({
                    data: [
                        { id: 1, name: 'test', active: true, tags: ['a', 'b'], meta: { x: 1 } },
                    ],
                }),
            };
            axios.create.mockReturnValue(mockInstance);
            await connector.connect();
            const schema = await connector.inferSchema();
            expect(schema).toHaveLength(1);
            expect(schema[0].columns).toHaveLength(5);
            const types = Object.fromEntries(schema[0].columns.map((c) => [c.name, c.type]));
            expect(types.id).toBe('number');
            expect(types.name).toBe('string');
            expect(types.active).toBe('boolean');
            expect(types.tags).toBe('array');
            expect(types.meta).toBe('object');
        });
        it('disconnect 应该清除 axios 实例', async () => {
            const connector = new http_api_connector_1.HttpApiConnector({
                url: 'https://api.example.com',
                method: 'GET',
            });
            await connector.connect();
            await connector.disconnect();
            const result = connector.query('/test').catch((e) => e.message);
            await expect(result).resolves.toMatch(/not connected/);
        });
        it('重连后可以正常请求', async () => {
            const connector = new http_api_connector_1.HttpApiConnector({
                url: 'https://api.example.com',
                method: 'GET',
            });
            const mock1 = { request: jest.fn().mockResolvedValue({ data: [{ v: 1 }] }) };
            const mock2 = { request: jest.fn().mockResolvedValue({ data: [{ v: 2 }] }) };
            axios.create
                .mockReturnValueOnce(mock1)
                .mockReturnValueOnce(mock2);
            await connector.connect();
            const r1 = await connector.query('/a');
            expect(r1.rows).toEqual([{ v: 1 }]);
            await connector.disconnect();
            await connector.connect();
            const r2 = await connector.query('/b');
            expect(r2.rows).toEqual([{ v: 2 }]);
        });
    });
    describe('ConnectorFactory', () => {
        beforeEach(() => {
            jest.clearAllMocks();
        });
        it('应该创建 MySQL 连接器', () => {
            const connector = connector_factory_1.ConnectorFactory.create(client_1.DataSourceType.MYSQL, { host: 'localhost', port: 3306, database: 'test', username: 'root', password: 'pass' }, 5, 30000);
            expect(connector).toBeInstanceOf(mysql_connector_1.MysqlConnector);
        });
        it('应该创建 ClickHouse 连接器', () => {
            const connector = connector_factory_1.ConnectorFactory.create(client_1.DataSourceType.CLICKHOUSE, { host: 'localhost', port: 8123, database: 'default', username: 'default', password: '' }, undefined, 30000);
            expect(connector).toBeInstanceOf(clickhouse_connector_1.ClickHouseConnector);
        });
        it('应该创建 PostgreSQL 连接器', () => {
            const connector = connector_factory_1.ConnectorFactory.create(client_1.DataSourceType.POSTGRESQL, { host: 'localhost', port: 5432, database: 'test', username: 'pg', password: 'pass' }, 10, 30000);
            expect(connector).toBeInstanceOf(postgres_connector_1.PostgresConnector);
        });
        it('应该创建 HTTP API 连接器', () => {
            const connector = connector_factory_1.ConnectorFactory.create(client_1.DataSourceType.HTTP_API, { url: 'https://api.example.com', method: 'GET' }, undefined, 10000);
            expect(connector).toBeInstanceOf(http_api_connector_1.HttpApiConnector);
        });
        it('不支持的类型应该抛出错误', () => {
            expect(() => {
                connector_factory_1.ConnectorFactory.create('INVALID_TYPE', {}, undefined, undefined);
            }).toThrow(/Unsupported data source type/);
        });
    });
});
//# sourceMappingURL=connectors.spec.js.map