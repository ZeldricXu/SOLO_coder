import { Injectable, NotFoundException } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { DataSourceService } from '../data-source/data-source.service';
import { CreateMetricDto } from './dto/create-metric.dto';
import { UpdateMetricDto } from './dto/update-metric.dto';
import { ExecuteMetricDto } from './dto/execute-metric.dto';
import { ComparisonDto } from './dto/comparison.dto';
import { metricTemplates } from './templates';
import { Aggregation } from '@prisma/client';

@Injectable()
export class MetricService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly dataSourceService: DataSourceService,
  ) {}

  async create(dto: CreateMetricDto) {
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

  async findAll(businessLineId?: string) {
    const where = businessLineId ? { businessLineId } : {};
    return this.prisma.metric.findMany({
      where,
      orderBy: { createdAt: 'desc' },
    });
  }

  async findOne(id: string) {
    const metric = await this.prisma.metric.findUnique({ where: { id } });
    if (!metric) {
      throw new NotFoundException(`Metric ${id} not found`);
    }
    return metric;
  }

  async update(id: string, dto: UpdateMetricDto) {
    await this.findOne(id);
    return this.prisma.metric.update({
      where: { id },
      data: dto as any,
    });
  }

  async remove(id: string) {
    await this.findOne(id);
    return this.prisma.metric.delete({ where: { id } });
  }

  async execute(id: string, dto: ExecuteMetricDto) {
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

  async getComparison(id: string, dto: ComparisonDto) {
    const metric = await this.findOne(id);
    const currentRange = dto.dateRange;
    const previousRange = this.shiftDateRange(currentRange, dto.type);

    const currentDto: ExecuteMetricDto = { dateRange: currentRange };
    const previousDto: ExecuteMetricDto = { dateRange: previousRange };

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

  getTemplates(category?: string) {
    if (category) {
      return metricTemplates.filter((t) => t.category === category);
    }
    return metricTemplates;
  }

  private buildSql(metric: any, dto: ExecuteMetricDto): string {
    let sql = metric.sqlTemplate;

    if (!sql && metric.templateId) {
      const template = metricTemplates.find((t) => t.id === metric.templateId);
      if (template) {
        sql = template.sqlTemplate;
      }
    }

    if (!sql) {
      throw new NotFoundException(`No SQL template found for metric ${metric.id}`);
    }

    const dimensions = dto.dimensions ?? (metric.dimensions as string[]) ?? [];
    const dimensionClause = dimensions.length > 0 ? dimensions.join(', ') : '';
    const groupByClause = dimensions.length > 0 ? `GROUP BY ${dimensions.join(', ')}` : '';

    sql = sql.replace(/\{\{startDate\}\}/g, dto.dateRange.start);
    sql = sql.replace(/\{\{endDate\}\}/g, dto.dateRange.end);
    sql = sql.replace(/\{\{dimensions\}\}/g, dimensionClause);
    sql = sql.replace(/\{\{groupBy\}\}/g, groupByClause);

    if (dto.granularity) {
      sql = this.applyGranularity(sql, dto.granularity);
    }

    sql = this.applyAggregation(sql, metric.aggregation as Aggregation);

    return sql;
  }

  private applyGranularity(sql: string, granularity: string): string {
    const granularityMap: Record<string, string> = {
      hour: "DATE_FORMAT(created_at, '%Y-%m-%d %H:00')",
      day: "DATE_FORMAT(created_at, '%Y-%m-%d')",
      week: "YEARWEEK(created_at, 1)",
      month: "DATE_FORMAT(created_at, '%Y-%m')",
    };
    const expr = granularityMap[granularity];
    if (!expr) return sql;
    if (sql.includes('{{granularity}}')) {
      return sql.replace(/\{\{granularity\}\}/g, expr);
    }
    return sql;
  }

  private applyAggregation(sql: string, aggregation: Aggregation): string {
    if (aggregation === Aggregation.NONE) return sql;

    const aggFunc = aggregation.toString();
    return `SELECT ${aggFunc}(*) AS aggregated_value FROM (${sql}) AS subquery`;
  }

  private extractAggregateValue(rows: Record<string, any>[], aggregation: Aggregation): number {
    if (aggregation === Aggregation.NONE && rows.length > 0) {
      const values = Object.values(rows[0]).filter((v) => typeof v === 'number');
      return values.length > 0 ? values[0] : 0;
    }
    if (rows.length > 0 && rows[0].aggregated_value != null) {
      return Number(rows[0].aggregated_value);
    }
    return 0;
  }

  private computeChangeRate(current: number, previous: number): number | null {
    if (previous === 0) return null;
    return (current - previous) / previous;
  }

  private shiftDateRange(
    range: { start: string; end: string },
    type: 'yoy' | 'mom',
  ): { start: string; end: string } {
    const start = new Date(range.start);
    const end = new Date(range.end);

    if (type === 'yoy') {
      start.setFullYear(start.getFullYear() - 1);
      end.setFullYear(end.getFullYear() - 1);
    } else {
      start.setMonth(start.getMonth() - 1);
      end.setMonth(end.getMonth() - 1);
    }

    return { start: start.toISOString(), end: end.toISOString() };
  }
}
