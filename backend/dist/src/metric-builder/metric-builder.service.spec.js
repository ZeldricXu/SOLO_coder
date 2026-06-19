"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const testing_1 = require("@nestjs/testing");
const client_1 = require("@prisma/client");
const metric_builder_service_1 = require("./metric-builder.service");
const data_source_service_1 = require("../data-source/data-source.service");
const metric_service_1 = require("../metric/metric.service");
const prisma_service_1 = require("../prisma/prisma.service");
jest.mock('../data-source/data-source.service');
jest.mock('../metric/metric.service');
const MOCK_SCHEMA = [
    {
        table: 'orders',
        columns: [
            { name: 'id', type: 'number', nullable: false },
            { name: 'amount', type: 'number', nullable: false },
            { name: 'user_id', type: 'number', nullable: true },
            { name: 'channel', type: 'string', nullable: true },
            { name: 'category', type: 'string', nullable: true },
            { name: 'created_at', type: 'Date', nullable: false },
            { name: 'status', type: 'string', nullable: false },
        ],
    },
    {
        table: 'users',
        columns: [
            { name: 'id', type: 'number', nullable: false },
            { name: 'email', type: 'string', nullable: false },
            { name: 'level', type: 'string', nullable: true },
            { name: 'created_at', type: 'Date', nullable: false },
        ],
    },
];
describe('MetricBuilderService', () => {
    let service;
    let dataSourceService;
    let metricService;
    beforeEach(async () => {
        const module = await testing_1.Test.createTestingModule({
            imports: [],
            providers: [
                metric_builder_service_1.MetricBuilderService,
                {
                    provide: data_source_service_1.DataSourceService,
                    useValue: {
                        findOne: jest.fn(),
                        inferSchema: jest.fn(),
                        executeQuery: jest.fn(),
                    },
                },
                {
                    provide: metric_service_1.MetricService,
                    useValue: {
                        create: jest.fn(),
                    },
                },
                prisma_service_1.PrismaService,
            ],
        })
            .overrideProvider(prisma_service_1.PrismaService)
            .useValue({
            dataSource: {
                findUnique: jest.fn(),
            },
        })
            .compile();
        service = module.get(metric_builder_service_1.MetricBuilderService);
        dataSourceService = module.get(data_source_service_1.DataSourceService);
        metricService = module.get(metric_service_1.MetricService);
    });
    afterEach(() => {
        jest.clearAllMocks();
    });
    it('should be defined', () => {
        expect(service).toBeDefined();
    });
    describe('listTables', () => {
        it('应返回该数据源下的所有表和列', async () => {
            dataSourceService.inferSchema.mockResolvedValue(MOCK_SCHEMA);
            const tables = await service.listTables('ds_1');
            expect(tables).toHaveLength(2);
            expect(tables[0].table).toBe('orders');
            expect(tables[0].columns).toHaveLength(7);
            expect(dataSourceService.inferSchema).toHaveBeenCalledWith('ds_1');
        });
        it('推断失败时应抛出异常', async () => {
            dataSourceService.inferSchema.mockRejectedValue(new Error('Connection refused'));
            await expect(service.listTables('ds_invalid')).rejects.toThrow();
        });
    });
    describe('listColumns', () => {
        it('应返回指定表的所有列', async () => {
            dataSourceService.inferSchema.mockResolvedValue(MOCK_SCHEMA);
            const cols = await service.listColumns('ds_1', 'orders');
            expect(cols.map(c => c.name)).toEqual(['id', 'amount', 'user_id', 'channel', 'category', 'created_at', 'status']);
        });
        it('指定表不存在时应返回空数组', async () => {
            dataSourceService.inferSchema.mockResolvedValue(MOCK_SCHEMA);
            const cols = await service.listColumns('ds_1', 'non_existent');
            expect(cols).toEqual([]);
        });
    });
    describe('generateSql - MySQL 方言', () => {
        beforeEach(() => {
            dataSourceService.findOne.mockResolvedValue({
                id: 'ds_1',
                type: client_1.DataSourceType.MYSQL,
                config: {},
            });
        });
        it('最小配置: SUM(amount) FROM orders', async () => {
            const sql = await service.generateSql('ds_1', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                alias: 'gmv',
            });
            expect(sql).toContain('SUM(amount)');
            expect(sql).toContain('AS gmv');
            expect(sql).toContain('FROM orders');
        });
        it('COUNT(*) 不带字段', async () => {
            const sql = await service.generateSql('ds_1', {
                table: 'orders',
                metricField: '*',
                aggregation: 'COUNT',
                alias: 'order_count',
            });
            expect(sql).toContain('COUNT(*)');
        });
        it('DISTINCT_COUNT 转换为 COUNT(DISTINCT x)', async () => {
            const sql = await service.generateSql('ds_1', {
                table: 'orders',
                metricField: 'user_id',
                aggregation: 'DISTINCT_COUNT',
                alias: 'uv',
            });
            expect(sql).toContain('COUNT(DISTINCT user_id)');
        });
        it('时间粒度 DAY: DATE(created_at)', async () => {
            const sql = await service.generateSql('ds_1', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                alias: 'gmv',
                timeField: 'created_at',
                granularity: 'DAY',
            });
            expect(sql).toContain('DATE(created_at)');
            expect(sql).toContain('GROUP BY');
        });
        it('时间粒度 HOUR: DATE_FORMAT', async () => {
            const sql = await service.generateSql('ds_1', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                timeField: 'created_at',
                granularity: 'HOUR',
            });
            expect(sql).toContain('DATE_FORMAT');
        });
        it('维度分组: GROUP BY channel', async () => {
            const sql = await service.generateSql('ds_1', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                dimensions: ['channel', 'category'],
            });
            expect(sql).toContain('channel');
            expect(sql).toContain('category');
            expect(sql).toMatch(/GROUP BY\s+.*channel.*category/i);
        });
        it('时间过滤: BETWEEN start AND end', async () => {
            const sql = await service.generateSql('ds_1', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                timeField: 'created_at',
                startDate: '2025-01-01T00:00:00.000Z',
                endDate: '2025-01-07T23:59:59.999Z',
            });
            expect(sql).toContain('BETWEEN');
            expect(sql).toContain('2025-01-01');
            expect(sql).toContain('2025-01-07');
        });
        it('过滤条件: status = "paid"', async () => {
            const sql = await service.generateSql('ds_1', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                filters: [
                    { field: 'status', operator: 'eq', value: 'paid' },
                ],
            });
            expect(sql).toMatch(/status\s*=\s*'paid'/i);
        });
        it('过滤条件: amount > 100', async () => {
            const sql = await service.generateSql('ds_1', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                filters: [
                    { field: 'amount', operator: 'gt', value: 100 },
                ],
            });
            expect(sql).toMatch(/amount\s*>\s*100/);
        });
        it('过滤条件: IN (a,b,c)', async () => {
            const sql = await service.generateSql('ds_1', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                filters: [
                    { field: 'channel', operator: 'in', value: ['taobao', 'wechat', 'douyin'] },
                ],
            });
            expect(sql).toMatch(/channel\s+IN\s*\(/i);
            expect(sql).toContain('taobao');
            expect(sql).toContain('wechat');
        });
        it('过滤条件: channel LIKE "%taobao%"', async () => {
            const sql = await service.generateSql('ds_1', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                filters: [
                    { field: 'channel', operator: 'like', value: '%taobao%' },
                ],
            });
            expect(sql).toMatch(/channel\s+LIKE\s+'%taobao%'/i);
        });
        it('SQL校验: 表名注入检测应在 executeQuery 阶段拦截', async () => {
            expect(() => service.generateSql('ds_1', {
                table: 'orders; DROP TABLE users',
                metricField: 'amount',
                aggregation: 'SUM',
            })).rejects;
        });
    });
    describe('generateSql - ClickHouse 方言', () => {
        beforeEach(() => {
            dataSourceService.findOne.mockResolvedValue({
                id: 'ds_ch',
                type: client_1.DataSourceType.CLICKHOUSE,
                config: {},
            });
        });
        it('DAY 粒度使用 toDate', async () => {
            const sql = await service.generateSql('ds_ch', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                timeField: 'created_at',
                granularity: 'DAY',
            });
            expect(sql).toContain('toDate(created_at)');
        });
        it('MONTH 粒度使用 formatDateTime', async () => {
            const sql = await service.generateSql('ds_ch', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                timeField: 'created_at',
                granularity: 'MONTH',
            });
            expect(sql).toContain('formatDateTime');
        });
    });
    describe('generateSql - PostgreSQL 方言', () => {
        beforeEach(() => {
            dataSourceService.findOne.mockResolvedValue({
                id: 'ds_pg',
                type: client_1.DataSourceType.POSTGRESQL,
                config: {},
            });
        });
        it('DAY 粒度使用 date_trunc', async () => {
            const sql = await service.generateSql('ds_pg', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                timeField: 'created_at',
                granularity: 'DAY',
            });
            expect(sql).toContain('date_trunc');
        });
    });
    describe('buildMetric', () => {
        beforeEach(() => {
            dataSourceService.findOne.mockResolvedValue({
                id: 'ds_1',
                type: client_1.DataSourceType.MYSQL,
                config: {},
            });
        });
        it('应生成 SQL 并执行预览查询', async () => {
            dataSourceService.executeQuery.mockResolvedValue({
                fields: [{ name: 'gmv', type: 'number' }],
                rows: [
                    { gmv: 123456 },
                    { gmv: 789012 },
                ],
            });
            const result = await service.buildMetric('ds_1', {
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                alias: 'gmv',
            });
            expect(result.sql).toBeDefined();
            expect(result.data).toHaveProperty('rows');
            expect(dataSourceService.executeQuery).toHaveBeenCalled();
        });
    });
    describe('createMetricFromVisual', () => {
        beforeEach(() => {
            dataSourceService.findOne.mockResolvedValue({
                id: 'ds_1',
                type: client_1.DataSourceType.MYSQL,
                config: {},
            });
            metricService.create.mockResolvedValue({
                id: 'm_new',
                name: 'GMV可视化',
                sqlTemplate: 'SELECT ...',
            });
        });
        it('应根据可视化配置生成 SQL 并持久化指标', async () => {
            const metric = await service.createMetricFromVisual('user_1', 'bl_1', 'ds_1', {
                name: 'GMV可视化',
                description: '按天GMV',
                table: 'orders',
                metricField: 'amount',
                aggregation: 'SUM',
                alias: 'gmv',
                timeField: 'created_at',
                granularity: 'DAY',
                dimensions: ['channel'],
            });
            expect(metric.id).toBe('m_new');
            expect(metricService.create).toHaveBeenCalledWith(expect.objectContaining({
                name: 'GMV可视化',
                type: 'SQL',
                aggregation: 'SUM',
                dataSourceId: 'ds_1',
            }));
        });
    });
});
//# sourceMappingURL=metric-builder.service.spec.js.map