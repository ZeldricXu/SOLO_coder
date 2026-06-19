import { Injectable, NotFoundException, BadRequestException } from '@nestjs/common';
import { DataSourceService } from '../data-source/data-source.service';
import { MetricService } from '../metric/metric.service';
import { SchemaTable, SchemaColumn, QueryResult } from '../data-source/connectors/base.connector';
import {
  VisualMetricConfig,
  FilterCondition,
  CreateMetricFromVisualDto,
} from './dto/build-metric.dto';
import { DataSourceType, Aggregation, TimeWindow, MetricType } from '@prisma/client';
import { CreateMetricDto } from '../metric/dto/create-metric.dto';

const GRANULARITY_MYSQL: Record<string, string> = {
  HOUR: "DATE_FORMAT({field}, '%Y-%m-%d %H:00')",
  DAY: 'DATE({field})',
  WEEK: 'YEARWEEK({field}, 1)',
  MONTH: "DATE_FORMAT({field}, '%Y-%m')",
};

const GRANULARITY_CLICKHOUSE: Record<string, string> = {
  HOUR: "formatDateTime({field}, '%Y-%m-%d %H:00')",
  DAY: 'toDate({field})',
  WEEK: 'toYYYYWW({field})',
  MONTH: "formatDateTime({field}, '%Y-%m')",
};

const GRANULARITY_POSTGRESQL: Record<string, string> = {
  HOUR: "to_char({field}, 'YYYY-MM-DD HH24:00')",
  DAY: '({field})::date',
  WEEK: "to_char(date_trunc('week', {field}), 'IYYYIW')",
  MONTH: "to_char({field}, 'YYYY-MM')",
};

const OPERATOR_MAP: Record<string, string> = {
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

interface TableInfo {
  name: string;
  columns: SchemaColumn[];
}

@Injectable()
export class MetricBuilderService {
  constructor(
    private readonly dataSourceService: DataSourceService,
    private readonly metricService: MetricService,
  ) {}

  async listTables(dataSourceId: string): Promise<TableInfo[]> {
    const schema: SchemaTable[] = await this.dataSourceService.inferSchema(dataSourceId);
    return schema.map((table) => ({
      name: table.table,
      columns: table.columns,
    }));
  }

  async listColumns(
    dataSourceId: string,
    tableName: string,
  ): Promise<SchemaColumn[]> {
    const tables = await this.listTables(dataSourceId);
    const table = tables.find((t) => t.name === tableName);
    if (!table) {
      throw new NotFoundException(`Table ${tableName} not found in data source`);
    }
    return table.columns;
  }

  async generateSql(dataSourceId: string, config: VisualMetricConfig): Promise<string> {
    const dataSource = await this.dataSourceService.findOne(dataSourceId);
    return this.buildSqlFromConfig(config, dataSource.type as DataSourceType);
  }

  async buildMetric(
    dataSourceId: string,
    config: VisualMetricConfig,
  ): Promise<{ sql: string; data: QueryResult }> {
    const sql = await this.generateSql(dataSourceId, config);
    const data = await this.dataSourceService.executeQuery(dataSourceId, { sql });
    return { sql, data };
  }

  async createMetricFromVisual(
    userId: string,
    businessLineId: string,
    dataSourceId: string,
    config: CreateMetricFromVisualDto,
  ) {
    const sql = await this.generateSql(dataSourceId, config);

    const aggregation = this.mapVisualAggregation(config.aggregation);
    const timeWindow = this.mapTimeWindow(config.timeWindow ?? config.granularity);

    const createMetricDto: CreateMetricDto = {
      name: config.name,
      description: config.description,
      type: MetricType.SQL,
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

  private buildSqlFromConfig(
    config: VisualMetricConfig,
    dataSourceType: DataSourceType,
  ): string {
    const {
      table,
      metricField,
      aggregation,
      alias,
      timeField,
      granularity,
      startDate,
      endDate,
      dimensions = [],
      filters = [],
    } = config;

    const selectParts: string[] = [];
    const groupByParts: string[] = [];

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

    const whereClauses: string[] = [];

    if (timeField && startDate && endDate) {
      whereClauses.push(
        `${timeField} BETWEEN '${this.escapeValue(startDate)}' AND '${this.escapeValue(endDate)}'`,
      );
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

  private buildMetricExpression(
    metricField: string,
    aggregation: string,
    alias?: string,
  ): string {
    const outputAlias = alias ?? metricField;

    if (aggregation === 'COUNT') {
      return `COUNT(*) AS ${outputAlias}`;
    }

    if (aggregation === 'DISTINCT_COUNT') {
      return `COUNT(DISTINCT ${metricField}) AS ${outputAlias}`;
    }

    return `${aggregation}(${metricField}) AS ${outputAlias}`;
  }

  private buildGranularityExpression(
    field: string,
    granularity: string,
    dataSourceType: DataSourceType,
  ): string {
    let templateMap: Record<string, string>;

    switch (dataSourceType) {
      case DataSourceType.MYSQL:
        templateMap = GRANULARITY_MYSQL;
        break;
      case DataSourceType.CLICKHOUSE:
        templateMap = GRANULARITY_CLICKHOUSE;
        break;
      case DataSourceType.POSTGRESQL:
        templateMap = GRANULARITY_POSTGRESQL;
        break;
      default:
        templateMap = GRANULARITY_MYSQL;
    }

    const template = templateMap[granularity];
    if (!template) {
      throw new BadRequestException(`Unsupported granularity: ${granularity}`);
    }

    return template.replace(/\{field\}/g, field);
  }

  private buildFilterClause(filter: FilterCondition): string {
    const { field, operator, value } = filter;
    const sqlOperator = OPERATOR_MAP[operator];

    if (!sqlOperator) {
      throw new BadRequestException(`Unsupported operator: ${operator}`);
    }

    if (operator === 'in') {
      const values = Array.isArray(value) ? value : [value];
      const escapedValues = values.map((v) => this.formatValue(v)).join(', ');
      return `${field} IN (${escapedValues})`;
    }

    if (operator === 'between') {
      if (!Array.isArray(value) || value.length !== 2) {
        throw new BadRequestException('BETWEEN operator requires array of [min, max]');
      }
      return `${field} BETWEEN ${this.formatValue(value[0])} AND ${this.formatValue(value[1])}`;
    }

    if (operator === 'like') {
      return `${field} LIKE ${this.formatValue(value)}`;
    }

    return `${field} ${sqlOperator} ${this.formatValue(value)}`;
  }

  private formatValue(value: any): string {
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

  private escapeValue(value: string): string {
    return value.replace(/'/g, "''");
  }

  private mapVisualAggregation(visualAgg: string): Aggregation {
    switch (visualAgg) {
      case 'SUM':
        return Aggregation.SUM;
      case 'COUNT':
      case 'DISTINCT_COUNT':
        return Aggregation.COUNT;
      case 'AVG':
        return Aggregation.AVG;
      case 'MAX':
        return Aggregation.MAX;
      case 'MIN':
        return Aggregation.MIN;
      default:
        return Aggregation.NONE;
    }
  }

  private mapTimeWindow(granularity?: string): TimeWindow {
    switch (granularity) {
      case 'HOUR':
        return TimeWindow.HOUR;
      case 'DAY':
        return TimeWindow.DAY;
      case 'WEEK':
        return TimeWindow.WEEK;
      case 'MONTH':
        return TimeWindow.MONTH;
      case 'QUARTER':
      case 'YEAR':
      default:
        return TimeWindow.DAY;
    }
  }
}
