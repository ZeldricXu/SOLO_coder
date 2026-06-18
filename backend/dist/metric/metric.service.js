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
exports.MetricService = void 0;
const common_1 = require("@nestjs/common");
const prisma_service_1 = require("../prisma/prisma.service");
const data_source_service_1 = require("../data-source/data-source.service");
const templates_1 = require("./templates");
const client_1 = require("@prisma/client");
let MetricService = class MetricService {
    constructor(prisma, dataSourceService) {
        this.prisma = prisma;
        this.dataSourceService = dataSourceService;
    }
    async create(dto) {
        return this.prisma.metric.create({
            data: {
                name: dto.name,
                description: dto.description,
                type: dto.type,
                sqlTemplate: dto.sqlTemplate,
                templateId: dto.templateId,
                aggregation: dto.aggregation,
                timeWindow: dto.timeWindow,
                dimensions: dto.dimensions ?? [],
                dataSourceId: dto.dataSourceId,
                businessLineId: dto.businessLineId,
                isAutoCompare: dto.isAutoCompare ?? true,
            },
        });
    }
    async findAll(businessLineId) {
        const where = businessLineId ? { businessLineId } : {};
        return this.prisma.metric.findMany({
            where,
            orderBy: { createdAt: 'desc' },
        });
    }
    async findOne(id) {
        const metric = await this.prisma.metric.findUnique({ where: { id } });
        if (!metric) {
            throw new common_1.NotFoundException(`Metric ${id} not found`);
        }
        return metric;
    }
    async update(id, dto) {
        await this.findOne(id);
        return this.prisma.metric.update({
            where: { id },
            data: dto,
        });
    }
    async remove(id) {
        await this.findOne(id);
        return this.prisma.metric.delete({ where: { id } });
    }
    async execute(id, dto) {
        const metric = await this.findOne(id);
        const sql = this.buildSql(metric, dto);
        const result = await this.dataSourceService.executeQuery(metric.dataSourceId, {
            sql,
        });
        return {
            metric: { id: metric.id, name: metric.name, aggregation: metric.aggregation },
            dateRange: dto.dateRange,
            dimensions: dto.dimensions ?? [],
            data: result.rows,
        };
    }
    async getComparison(id, dto) {
        const metric = await this.findOne(id);
        const currentRange = dto.dateRange;
        const previousRange = this.shiftDateRange(currentRange, dto.type);
        const currentDto = { dateRange: currentRange };
        const previousDto = { dateRange: previousRange };
        const currentSql = this.buildSql(metric, currentDto);
        const previousSql = this.buildSql(metric, previousDto);
        const [currentResult, previousResult] = await Promise.all([
            this.dataSourceService.executeQuery(metric.dataSourceId, { sql: currentSql }),
            this.dataSourceService.executeQuery(metric.dataSourceId, { sql: previousSql }),
        ]);
        const currentValue = this.extractAggregateValue(currentResult.rows, metric.aggregation);
        const previousValue = this.extractAggregateValue(previousResult.rows, metric.aggregation);
        const changeRate = this.computeChangeRate(currentValue, previousValue);
        return {
            metric: { id: metric.id, name: metric.name, aggregation: metric.aggregation },
            comparisonType: dto.type,
            current: { dateRange: currentRange, value: currentValue, rows: currentResult.rows },
            previous: { dateRange: previousRange, value: previousValue, rows: previousResult.rows },
            changeRate,
        };
    }
    getTemplates(category) {
        if (category) {
            return templates_1.metricTemplates.filter((t) => t.category === category);
        }
        return templates_1.metricTemplates;
    }
    buildSql(metric, dto) {
        let sql = metric.sqlTemplate;
        if (!sql && metric.templateId) {
            const template = templates_1.metricTemplates.find((t) => t.id === metric.templateId);
            if (template) {
                sql = template.sqlTemplate;
            }
        }
        if (!sql) {
            throw new common_1.NotFoundException(`No SQL template found for metric ${metric.id}`);
        }
        const dimensions = dto.dimensions ?? metric.dimensions ?? [];
        const dimensionClause = dimensions.length > 0 ? dimensions.join(', ') : '';
        const groupByClause = dimensions.length > 0 ? `GROUP BY ${dimensions.join(', ')}` : '';
        sql = sql.replace(/\{\{startDate\}\}/g, dto.dateRange.start);
        sql = sql.replace(/\{\{endDate\}\}/g, dto.dateRange.end);
        sql = sql.replace(/\{\{dimensions\}\}/g, dimensionClause);
        sql = sql.replace(/\{\{groupBy\}\}/g, groupByClause);
        if (dto.granularity) {
            sql = this.applyGranularity(sql, dto.granularity);
        }
        sql = this.applyAggregation(sql, metric.aggregation);
        return sql;
    }
    applyGranularity(sql, granularity) {
        const granularityMap = {
            hour: "DATE_FORMAT(created_at, '%Y-%m-%d %H:00')",
            day: "DATE_FORMAT(created_at, '%Y-%m-%d')",
            week: "YEARWEEK(created_at, 1)",
            month: "DATE_FORMAT(created_at, '%Y-%m')",
        };
        const expr = granularityMap[granularity];
        if (!expr)
            return sql;
        if (sql.includes('{{granularity}}')) {
            return sql.replace(/\{\{granularity\}\}/g, expr);
        }
        return sql;
    }
    applyAggregation(sql, aggregation) {
        if (aggregation === client_1.Aggregation.NONE)
            return sql;
        const aggFunc = aggregation.toString();
        return `SELECT ${aggFunc}(*) AS aggregated_value FROM (${sql}) AS subquery`;
    }
    extractAggregateValue(rows, aggregation) {
        if (aggregation === client_1.Aggregation.NONE && rows.length > 0) {
            const values = Object.values(rows[0]).filter((v) => typeof v === 'number');
            return values.length > 0 ? values[0] : 0;
        }
        if (rows.length > 0 && rows[0].aggregated_value != null) {
            return Number(rows[0].aggregated_value);
        }
        return 0;
    }
    computeChangeRate(current, previous) {
        if (previous === 0)
            return null;
        return (current - previous) / previous;
    }
    shiftDateRange(range, type) {
        const start = new Date(range.start);
        const end = new Date(range.end);
        if (type === 'yoy') {
            start.setFullYear(start.getFullYear() - 1);
            end.setFullYear(end.getFullYear() - 1);
        }
        else {
            start.setMonth(start.getMonth() - 1);
            end.setMonth(end.getMonth() - 1);
        }
        return { start: start.toISOString(), end: end.toISOString() };
    }
};
exports.MetricService = MetricService;
exports.MetricService = MetricService = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [prisma_service_1.PrismaService,
        data_source_service_1.DataSourceService])
], MetricService);
//# sourceMappingURL=metric.service.js.map