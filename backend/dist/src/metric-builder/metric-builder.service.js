"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.MetricBuilderService = void 0;
const common_1 = require("@nestjs/common");
const data_source_service_1 = require("../data-source/data-source.service");
const metric_service_1 = require("../metric/metric.service");
const client_1 = require("@prisma/client");
const GRANULARITY_MYSQL = {
    HOUR: "DATE_FORMAT({field}, '%Y-%m-%d %H:00')",
    DAY: 'DATE({field})',
    WEEK: 'YEARWEEK({field}, 1)',
    MONTH: "DATE_FORMAT({field}, '%Y-%m')",
};
const GRANULARITY_CLICKHOUSE = {
    HOUR: "formatDateTime({field}, '%Y-%m-%d %H:00')",
    DAY: 'toDate({field})',
    WEEK: 'toYYYYWW({field})',
    MONTH: "formatDateTime({field}, '%Y-%m')",
};
const GRANULARITY_POSTGRESQL = {
    HOUR: "to_char({field}, 'YYYY-MM-DD HH24:00')",
    DAY: '({field})::date',
    WEEK: "to_char(date_trunc('week', {field}), 'IYYYIW')",
    MONTH: "to_char({field}, 'YYYY-MM')",
};
const OPERATOR_MAP = {
    eq: '=',
    ne: '!=',
    gt: '>',
    gte: '>=',
    lt: '<',
    lte: '<=',
    in: 'IN',
    like: 'LIKE',
    between: 'BETWEEN',
};
let MetricBuilderService = class MetricBuilderService {
    constructor(dataSourceService, metricService) {
        this.dataSourceService = dataSourceService;
        this.metricService = metricService;
    }
    async listTables(dataSourceId) {
        const schema = await this.dataSourceService.inferSchema(dataSourceId);
        return schema.map((table) => ({
            name: table.table,
            columns: table.columns,
        }));
    }
    async listColumns(dataSourceId, tableName) {
        const tables = await this.listTables(dataSourceId);
        const table = tables.find((t) => t.name === tableName);
        if (!table) {
            throw new common_1.NotFoundException(`Table ${tableName} not found in data source`);
        }
        return table.columns;
    }
    async generateSql(dataSourceId, config) {
        const dataSource = await this.dataSourceService.findOne(dataSourceId);
        return this.buildSqlFromConfig(config, dataSource.type);
    }
    async buildMetric(dataSourceId, config) {
        const sql = await this.generateSql(dataSourceId, config);
        const data = await this.dataSourceService.executeQuery(dataSourceId, { sql });
        return { sql, data };
    }
    async createMetricFromVisual(userId, businessLineId, dataSourceId, config) {
        const sql = await this.generateSql(dataSourceId, config);
        const aggregation = this.mapVisualAggregation(config.aggregation);
        const timeWindow = this.mapTimeWindow(config.timeWindow ?? config.granularity);
        const createMetricDto = {
            name: config.name,
            description: config.description,
            type: client_1.MetricType.SQL,
            sqlTemplate: sql,
            aggregation,
            timeWindow,
            dimensions: config.dimensions ?? [],
            dataSourceId,
            businessLineId,
            isAutoCompare: config.isAutoCompare ?? true,
        };
        return this.metricService.create(createMetricDto);
    }
    buildSqlFromConfig(config, dataSourceType) {
        const { table, metricField, aggregation, alias, timeField, granularity, startDate, endDate, dimensions = [], filters = [], } = config;
        const selectParts = [];
        const groupByParts = [];
        const metricExpr = this.buildMetricExpression(metricField, aggregation, alias);
        selectParts.push(metricExpr);
        if (timeField && granularity) {
            const timeExpr = this.buildGranularityExpression(timeField, granularity, dataSourceType);
            const timeAlias = `time_${granularity.toLowerCase()}`;
            selectParts.push(`${timeExpr} AS ${timeAlias}`);
            groupByParts.push(timeExpr);
        }
        for (const dim of dimensions) {
            selectParts.push(dim);
            groupByParts.push(dim);
        }
        const whereClauses = [];
        if (timeField && startDate && endDate) {
            whereClauses.push(`${timeField} BETWEEN '${this.escapeValue(startDate)}' AND '${this.escapeValue(endDate)}'`);
        }
        for (const filter of filters) {
            whereClauses.push(this.buildFilterClause(filter));
        }
        const selectClause = selectParts.join(', ');
        const fromClause = `FROM ${table}`;
        const whereClause = whereClauses.length > 0 ? `WHERE ${whereClauses.join(' AND ')}` : '';
        const groupByClause = groupByParts.length > 0 ? `GROUP BY ${groupByParts.join(', ')}` : '';
        const parts = [
            `SELECT ${selectClause}`,
            fromClause,
            whereClause,
            groupByClause,
        ].filter(Boolean);
        return parts.join(' ');
    }
    buildMetricExpression(metricField, aggregation, alias) {
        const outputAlias = alias ?? metricField;
        if (aggregation === 'COUNT') {
            return `COUNT(*) AS ${outputAlias}`;
        }
        if (aggregation === 'DISTINCT_COUNT') {
            return `COUNT(DISTINCT ${metricField}) AS ${outputAlias}`;
        }
        return `${aggregation}(${metricField}) AS ${outputAlias}`;
    }
    buildGranularityExpression(field, granularity, dataSourceType) {
        let templateMap;
        switch (dataSourceType) {
            case client_1.DataSourceType.MYSQL:
                templateMap = GRANULARITY_MYSQL;
                break;
            case client_1.DataSourceType.CLICKHOUSE:
                templateMap = GRANULARITY_CLICKHOUSE;
                break;
            case client_1.DataSourceType.POSTGRESQL:
                templateMap = GRANULARITY_POSTGRESQL;
                break;
            default:
                templateMap = GRANULARITY_MYSQL;
        }
        const template = templateMap[granularity];
        if (!template) {
            throw new common_1.BadRequestException(`Unsupported granularity: ${granularity}`);
        }
        return template.replace(/\{field\}/g, field);
    }
    buildFilterClause(filter) {
        const { field, operator, value } = filter;
        const sqlOperator = OPERATOR_MAP[operator];
        if (!sqlOperator) {
            throw new common_1.BadRequestException(`Unsupported operator: ${operator}`);
        }
        if (operator === 'in') {
            const values = Array.isArray(value) ? value : [value];
            const escapedValues = values.map((v) => this.formatValue(v)).join(', ');
            return `${field} IN (${escapedValues})`;
        }
        if (operator === 'between') {
            if (!Array.isArray(value) || value.length !== 2) {
                throw new common_1.BadRequestException('BETWEEN operator requires array of [min, max]');
            }
            return `${field} BETWEEN ${this.formatValue(value[0])} AND ${this.formatValue(value[1])}`;
        }
        if (operator === 'like') {
            return `${field} LIKE ${this.formatValue(value)}`;
        }
        return `${field} ${sqlOperator} ${this.formatValue(value)}`;
    }
    formatValue(value) {
        if (value === null || value === undefined) {
            return 'NULL';
        }
        if (typeof value === 'number') {
            return String(value);
        }
        if (typeof value === 'boolean') {
            return value ? '1' : '0';
        }
        return `'${this.escapeValue(String(value))}'`;
    }
    escapeValue(value) {
        return value.replace(/'/g, "''");
    }
    mapVisualAggregation(visualAgg) {
        switch (visualAgg) {
            case 'SUM':
                return client_1.Aggregation.SUM;
            case 'COUNT':
            case 'DISTINCT_COUNT':
                return client_1.Aggregation.COUNT;
            case 'AVG':
                return client_1.Aggregation.AVG;
            case 'MAX':
                return client_1.Aggregation.MAX;
            case 'MIN':
                return client_1.Aggregation.MIN;
            default:
                return client_1.Aggregation.NONE;
        }
    }
    mapTimeWindow(granularity) {
        switch (granularity) {
            case 'HOUR':
                return client_1.TimeWindow.HOUR;
            case 'DAY':
                return client_1.TimeWindow.DAY;
            case 'WEEK':
                return client_1.TimeWindow.WEEK;
            case 'MONTH':
                return client_1.TimeWindow.MONTH;
            case 'QUARTER':
            case 'YEAR':
            default:
                return client_1.TimeWindow.DAY;
        }
    }
};
exports.MetricBuilderService = MetricBuilderService;
exports.MetricBuilderService = MetricBuilderService = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [data_source_service_1.DataSourceService,
        metric_service_1.MetricService])
], MetricBuilderService);
//# sourceMappingURL=metric-builder.service.js.map