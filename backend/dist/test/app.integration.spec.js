"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const testing_1 = require("@nestjs/testing");
const app_module_1 = require("../src/app.module");
const data_source_service_1 = require("../src/data-source/data-source.service");
const metric_service_1 = require("../src/metric/metric.service");
const dashboard_service_1 = require("../src/dashboard/dashboard.service");
const alert_service_1 = require("../src/alert/alert.service");
const prisma_service_1 = require("../src/prisma/prisma.service");
const client_1 = require("@prisma/client");
const mysql_1 = require("@testcontainers/mysql");
const clickhouse_1 = require("@testcontainers/clickhouse");
const redis_1 = require("@testcontainers/redis");
const child_process_1 = require("child_process");
const path = require("path");
jest.setTimeout(300000);
describe('端到端集成测试 - 完整链路', () => {
    let mysqlContainer;
    let clickhouseContainer;
    let redisContainer;
    let app;
    let prisma;
    let dataSourceService;
    let metricService;
    let dashboardService;
    let alertService;
    let businessLineId;
    let tenantId;
    let userId;
    let dataSourceId;
    let gmvMetricId;
    let orderCountMetricId;
    let dashboardId;
    beforeAll(async () => {
        mysqlContainer = await new mysql_1.MySqlContainer('mysql:8.0')
            .withRootPassword('testpass')
            .withDatabase('biz_monitor_test')
            .withExposedPorts(3306)
            .start();
        clickhouseContainer = await new clickhouse_1.ClickHouseContainer('clickhouse/clickhouse-server:23.8')
            .withExposedPorts(8123)
            .start();
        redisContainer = await new redis_1.RedisContainer('redis:7-alpine')
            .withExposedPorts(6379)
            .start();
        process.env.DATABASE_URL = `mysql://root:testpass@localhost:${mysqlContainer.getMappedPort(3306)}/biz_monitor_test`;
        process.env.REDIS_HOST = 'localhost';
        process.env.REDIS_PORT = String(redisContainer.getMappedPort(6379));
        process.env.JWT_SECRET = 'test-secret';
        process.env.REALTIME_THROTTLE_MS = '500';
        (0, child_process_1.execSync)('npx prisma migrate deploy', {
            cwd: path.resolve(__dirname, '..'),
            stdio: 'inherit',
            env: { ...process.env },
        });
        await initClickHouseData(clickhouseContainer);
        const moduleRef = await testing_1.Test.createTestingModule({
            imports: [app_module_1.AppModule],
        }).compile();
        app = moduleRef.createNestApplication();
        await app.init();
        prisma = moduleRef.get(prisma_service_1.PrismaService);
        dataSourceService = moduleRef.get(data_source_service_1.DataSourceService);
        metricService = moduleRef.get(metric_service_1.MetricService);
        dashboardService = moduleRef.get(dashboard_service_1.DashboardService);
        alertService = moduleRef.get(alert_service_1.AlertService);
        const tenant = await prisma.tenant.create({
            data: { name: '默认租户', slug: 'default' },
        });
        tenantId = tenant.id;
        const bl = await prisma.businessLine.create({
            data: { name: '电商业务线', code: 'ecommerce', tenantId },
        });
        businessLineId = bl.id;
        const user = await prisma.user.create({
            data: {
                email: 'test@example.com',
                password: '$2b$10$dummy',
                name: 'Test User',
                role: client_1.Role.TENANT_ADMIN,
                tenantId,
            },
        });
        userId = user.id;
    }, 300000);
    afterAll(async () => {
        if (app)
            await app.close();
        if (mysqlContainer)
            await mysqlContainer.stop();
        if (clickhouseContainer)
            await clickhouseContainer.stop();
        if (redisContainer)
            await redisContainer.stop();
    });
    async function initClickHouseData(container) {
        const port = container.getMappedPort(8123);
        const baseUrl = `http://localhost:${port}`;
        const createTableSql = `
      CREATE TABLE IF NOT EXISTS orders (
        id UInt64,
        user_id UInt64,
        product_category String,
        payment_amount Decimal(18,2),
        order_status String,
        created_at DateTime
      ) ENGINE = MergeTree()
      ORDER BY (created_at, id)
    `;
        await fetch(`${baseUrl}/?query=${encodeURIComponent(createTableSql)}`, { method: 'POST' });
        const insertSql = `
      INSERT INTO orders FORMAT Values
        ${generateOrderValues()}
    `;
        await fetch(`${baseUrl}/?query=${encodeURIComponent(insertSql)}`, { method: 'POST' });
    }
    function generateOrderValues() {
        const values = [];
        const now = new Date();
        const categories = ['电子产品', '服装', '食品', '家居'];
        const statuses = ['completed', 'completed', 'completed', 'pending', 'cancelled'];
        for (let i = 0; i < 1000; i++) {
            const daysAgo = Math.floor(Math.random() * 7);
            const hoursAgo = Math.floor(Math.random() * 24);
            const date = new Date(now.getTime() - daysAgo * 86400000 - hoursAgo * 3600000);
            const dateStr = date.toISOString().replace('T', ' ').substring(0, 19);
            const id = i + 1;
            const userId = Math.floor(Math.random() * 100) + 1;
            const category = categories[Math.floor(Math.random() * categories.length)];
            const amount = (Math.random() * 1000 + 10).toFixed(2);
            const status = statuses[Math.floor(Math.random() * statuses.length)];
            values.push(`(${id}, ${userId}, '${category}', ${amount}, '${status}', '${dateStr}')`);
        }
        return values.join(',\n        ');
    }
    describe('第1步：创建 ClickHouse 数据源并测试连接', () => {
        it('应该成功创建 ClickHouse 数据源', async () => {
            const ds = await dataSourceService.create({
                name: 'ClickHouse 电商数据',
                type: client_1.DataSourceType.CLICKHOUSE,
                config: {
                    host: 'localhost',
                    port: clickhouseContainer.getMappedPort(8123),
                    database: 'default',
                    username: 'default',
                    password: '',
                },
                poolSize: 5,
                queryTimeout: 30000,
                businessLineId,
            });
            dataSourceId = ds.id;
            expect(ds.name).toBe('ClickHouse 电商数据');
            expect(ds.type).toBe(client_1.DataSourceType.CLICKHOUSE);
            expect(ds.businessLineId).toBe(businessLineId);
        });
        it('应该成功测试连接', async () => {
            const success = await dataSourceService.testConnection(dataSourceId);
            expect(success).toBe(true);
        });
        it('应该能推断出 Schema', async () => {
            const schema = await dataSourceService.inferSchema(dataSourceId);
            expect(schema.length).toBeGreaterThan(0);
            const ordersTable = schema.find((t) => t.table === 'orders');
            expect(ordersTable).toBeDefined();
            expect(ordersTable.columns.length).toBeGreaterThan(3);
            const idCol = ordersTable.columns.find((c) => c.name === 'id');
            expect(idCol).toBeDefined();
            expect(idCol.type).toBe('number');
        });
    });
    describe('第2步：定义 GMV 和订单量两个指标', () => {
        it('应该成功创建 GMV 指标', async () => {
            const metric = await metricService.create({
                name: 'GMV',
                description: '商品交易总额',
                type: 'SQL',
                sqlTemplate: "SELECT {{dimensions}}, SUM(payment_amount) AS value FROM orders WHERE order_status = 'completed' AND created_at BETWEEN '{{startDate}}' AND '{{endDate}}' {{groupBy}}",
                aggregation: client_1.Aggregation.SUM,
                timeWindow: client_1.TimeWindow.DAY,
                dimensions: ['product_category'],
                dataSourceId,
                businessLineId,
                isAutoCompare: true,
            });
            gmvMetricId = metric.id;
            expect(metric.name).toBe('GMV');
            expect(metric.aggregation).toBe(client_1.Aggregation.SUM);
        });
        it('应该成功创建订单量指标', async () => {
            const metric = await metricService.create({
                name: '订单量',
                description: '完成订单数量',
                type: 'SQL',
                sqlTemplate: "SELECT {{dimensions}}, COUNT(*) AS value FROM orders WHERE order_status = 'completed' AND created_at BETWEEN '{{startDate}}' AND '{{endDate}}' {{groupBy}}",
                aggregation: client_1.Aggregation.COUNT,
                timeWindow: client_1.TimeWindow.DAY,
                dimensions: ['product_category'],
                dataSourceId,
                businessLineId,
                isAutoCompare: true,
            });
            orderCountMetricId = metric.id;
            expect(metric.name).toBe('订单量');
        });
        it('GMV 指标应该能正常执行查询', async () => {
            const endDate = new Date();
            const startDate = new Date(endDate.getTime() - 7 * 86400000);
            const result = await metricService.execute(gmvMetricId, {
                dateRange: {
                    start: startDate.toISOString(),
                    end: endDate.toISOString(),
                },
            });
            expect(result.metric.id).toBe(gmvMetricId);
            expect(result.data).toBeDefined();
            expect(Array.isArray(result.data)).toBe(true);
            expect(result.data.length).toBeGreaterThan(0);
        });
        it('应该能获取同环比对比数据', async () => {
            const endDate = new Date();
            const startDate = new Date(endDate.getTime() - 7 * 86400000);
            const result = await metricService.getComparison(gmvMetricId, {
                dateRange: {
                    start: startDate.toISOString(),
                    end: endDate.toISOString(),
                },
                type: 'mom',
            });
            expect(result.comparisonType).toBe('mom');
            expect(result.current.value).toBeDefined();
            expect(result.previous.value).toBeDefined();
            expect(typeof result.changeRate === 'number' || result.changeRate === null).toBe(true);
        });
    });
    describe('第3步：搭建带折线图和数字卡的看板', () => {
        it('应该成功创建看板', async () => {
            const dashboard = await dashboardService.create({
                name: '电商运营总览',
                description: '电商业务核心指标总览',
                businessLineId,
            }, userId);
            dashboardId = dashboard.id;
            expect(dashboard.name).toBe('电商运营总览');
            expect(dashboard.createdBy).toBe(userId);
            expect(dashboard.version).toBe(1);
        });
        it('应该能添加 GMV 数字卡组件', async () => {
            const widget = await dashboardService.addWidget(dashboardId, {
                type: client_1.WidgetType.NUMBER_CARD,
                title: 'GMV',
                metricId: gmvMetricId,
                config: {
                    showChangeRate: true,
                    compareType: 'mom',
                },
                layout: { x: 0, y: 0, w: 3, h: 2 },
            });
            expect(widget.type).toBe(client_1.WidgetType.NUMBER_CARD);
            expect(widget.title).toBe('GMV');
            expect(widget.metricId).toBe(gmvMetricId);
        });
        it('应该能添加订单量折线图组件', async () => {
            const widget = await dashboardService.addWidget(dashboardId, {
                type: client_1.WidgetType.LINE_CHART,
                title: '订单量趋势',
                metricId: orderCountMetricId,
                config: {
                    xAxis: 'date',
                    yAxis: 'value',
                    showLegend: true,
                },
                layout: { x: 3, y: 0, w: 9, h: 4 },
            });
            expect(widget.type).toBe(client_1.WidgetType.LINE_CHART);
            expect(widget.title).toBe('订单量趋势');
        });
        it('应该能获取完整看板数据（含 widgets）', async () => {
            const dashboard = await dashboardService.findOne(dashboardId);
            expect(dashboard.widgets).toHaveLength(2);
            expect(dashboard.widgets[0].metric).toBeDefined();
        });
    });
    describe('第4步：看板实时刷新与筛选器联动', () => {
        it('应该能导出看板配置', async () => {
            const exported = await dashboardService.exportDashboard(dashboardId);
            expect(exported.version).toBe(1);
            expect(exported.dashboard.name).toBe('电商运营总览');
            expect(exported.widgets).toHaveLength(2);
            expect(exported.exportedAt).toBeDefined();
        });
        it('应该能导入看板配置', async () => {
            const exported = await dashboardService.exportDashboard(dashboardId);
            exported.dashboard.name = '电商运营总览-副本';
            const imported = await dashboardService.importDashboard(exported, userId, businessLineId);
            expect(imported).toBeDefined();
            expect(imported.name).toBe('电商运营总览-副本');
            expect(imported.widgets).toHaveLength(2);
            expect(imported.id).not.toBe(dashboardId);
        });
        it('应该能批量更新组件布局', async () => {
            const dashboard = await dashboardService.findOne(dashboardId);
            const widgets = dashboard.widgets;
            const items = widgets.map((w, i) => ({
                widgetId: w.id,
                x: i * 4,
                y: 0,
                w: 4,
                h: 4,
            }));
            const updated = await dashboardService.batchUpdateLayout(dashboardId, items);
            expect(updated.widgets).toHaveLength(2);
        });
        it('组件间应该可以建立联动关系', async () => {
            const dashboard = await dashboardService.findOne(dashboardId);
            const [w1, w2] = dashboard.widgets;
            const result = await dashboardService.linkWidget(dashboardId, w1.id, w2.id);
            expect(result.linkedWidgetIds).toContain(w2.id);
            const w2Updated = await prisma.widget.findUnique({ where: { id: w2.id } });
            expect(w2Updated.linkedWidgetIds).toContain(w1.id);
        });
    });
    describe('第5步：告警链路 - GMV下跌20%告警', () => {
        let ruleId;
        it('应该能创建 GMV 波动告警规则', async () => {
            const rule = await prisma.alertRule.create({
                data: {
                    name: 'GMV下跌告警',
                    type: client_1.AlertType.FLUCTUATION,
                    metricId: gmvMetricId,
                    condition: {
                        fluctuationPercent: 20,
                        compareType: 'mom',
                        direction: 'down',
                    },
                    channels: [{ type: 'WECOM', target: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test-key' }],
                    silenceMinutes: 60,
                    isActive: true,
                },
            });
            ruleId = rule.id;
            expect(rule.name).toBe('GMV下跌告警');
            expect(rule.type).toBe(client_1.AlertType.FLUCTUATION);
            expect(rule.isActive).toBe(true);
        });
        it('评估告警规则应该能正常执行', async () => {
            const result = await alertService.evaluateRule(ruleId);
            expect(result).toBeDefined();
        });
        it('应该能列出告警记录', async () => {
            const records = await prisma.alertRecord.findMany({
                where: { ruleId },
                orderBy: { createdAt: 'desc' },
            });
            expect(Array.isArray(records)).toBe(true);
        });
        it('阈值告警规则应该能正常创建', async () => {
            const rule = await prisma.alertRule.create({
                data: {
                    name: 'GMV阈值告警',
                    type: client_1.AlertType.THRESHOLD,
                    metricId: gmvMetricId,
                    condition: {
                        upper: 1000000,
                        lower: 1000,
                    },
                    channels: [{ type: 'EMAIL', target: 'admin@example.com' }],
                    silenceMinutes: 30,
                    isActive: true,
                },
            });
            const result = await alertService.evaluateRule(rule.id);
            expect(result).toBeDefined();
        });
        it('断流告警规则应该能正常创建', async () => {
            const rule = await prisma.alertRule.create({
                data: {
                    name: '数据断流告警',
                    type: client_1.AlertType.STREAM_BREAK,
                    metricId: orderCountMetricId,
                    condition: {
                        breakMinutes: 60,
                    },
                    channels: [
                        { type: 'DINGTALK', target: 'https://oapi.dingtalk.com/robot/send?access_token=test' },
                    ],
                    silenceMinutes: 15,
                    isActive: false,
                },
            });
            expect(rule.type).toBe(client_1.AlertType.STREAM_BREAK);
            expect(rule.isActive).toBe(false);
        });
    });
    describe('第6步：乐观锁冲突处理', () => {
        it('应该检测到版本冲突并抛出异常', async () => {
            const dashboard = await dashboardService.findOne(dashboardId);
            const currentVersion = dashboard.version;
            await dashboardService.update(dashboardId, {
                name: '第一次修改',
                expectedVersion: currentVersion,
            });
            await expect(dashboardService.update(dashboardId, {
                name: '第二次修改（应该冲突）',
                expectedVersion: currentVersion,
            })).rejects.toThrow(/modified by another user/);
        });
        it('带上正确版本号应该能成功更新', async () => {
            const dashboard = await dashboardService.findOne(dashboardId);
            const versionBefore = dashboard.version;
            const updated = await dashboardService.update(dashboardId, {
                name: '第三次修改（正确版本）',
                expectedVersion: versionBefore,
            });
            expect(updated.version).toBe(versionBefore + 1);
        });
    });
    describe('第7步：SQL危险语句拦截', () => {
        it('执行 DROP TABLE 应该被拦截', async () => {
            await expect(dataSourceService.executeQuery(dataSourceId, { sql: 'DROP TABLE orders' })).rejects.toThrow(/Forbidden/);
        });
        it('执行 DELETE FROM 应该被拦截', async () => {
            await expect(dataSourceService.executeQuery(dataSourceId, { sql: 'DELETE FROM orders WHERE 1=1' })).rejects.toThrow(/Forbidden/);
        });
        it('正常 SELECT 查询应该能执行', async () => {
            const result = await dataSourceService.executeQuery(dataSourceId, {
                sql: 'SELECT count() AS cnt FROM orders',
            });
            expect(result.rows).toBeDefined();
            expect(result.rowCount).toBeGreaterThanOrEqual(0);
        });
    });
    describe('第8步：多租户数据隔离', () => {
        let anotherBusinessLineId;
        beforeAll(async () => {
            const bl = await prisma.businessLine.create({
                data: { name: '广告业务线', code: 'advertising', tenantId },
            });
            anotherBusinessLineId = bl.id;
        });
        it('不同业务线的数据源应该隔离', async () => {
            const allDs = await dataSourceService.findAll();
            const ecommerceDs = await dataSourceService.findAll(businessLineId);
            const adDs = await dataSourceService.findAll(anotherBusinessLineId);
            expect(ecommerceDs.length).toBe(1);
            expect(adDs.length).toBe(0);
            expect(allDs.length).toBeGreaterThanOrEqual(ecommerceDs.length);
        });
        it('不同业务线的指标应该隔离', async () => {
            const ecommerceMetrics = await metricService.findAll(businessLineId);
            const adMetrics = await metricService.findAll(anotherBusinessLineId);
            expect(ecommerceMetrics.length).toBeGreaterThanOrEqual(2);
            expect(adMetrics.length).toBe(0);
        });
    });
});
//# sourceMappingURL=app.integration.spec.js.map