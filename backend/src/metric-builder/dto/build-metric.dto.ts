import {
  IsString,
  IsEnum,
  IsOptional,
  IsArray,
  IsNotEmpty,
  ValidateNested,
} from 'class-validator';
import { Type } from 'class-transformer';

export type VisualAggregation =
  | 'SUM'
  | 'COUNT'
  | 'AVG'
  | 'MAX'
  | 'MIN'
  | 'DISTINCT_COUNT';

export type VisualGranularity = 'HOUR' | 'DAY' | 'WEEK' | 'MONTH';

export type FilterOperator =
  | 'eq'
  | 'ne'
  | 'gt'
  | 'gte'
  | 'lt'
  | 'lte'
  | 'in'
  | 'like'
  | 'between';

export class FilterCondition {
  @IsString()
  @IsNotEmpty()
  field: string;

  @IsEnum(['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'in', 'like', 'between'])
  operator: FilterOperator;

  value: any;
}

export class VisualMetricConfig {
  @IsString()
  @IsNotEmpty()
  table: string;

  @IsString()
  @IsNotEmpty()
  metricField: string;

  @IsEnum(['SUM', 'COUNT', 'AVG', 'MAX', 'MIN', 'DISTINCT_COUNT'])
  aggregation: VisualAggregation;

  @IsOptional()
  @IsString()
  alias?: string;

  @IsOptional()
  @IsString()
  timeField?: string;

  @IsOptional()
  @IsEnum(['HOUR', 'DAY', 'WEEK', 'MONTH'])
  granularity?: VisualGranularity;

  @IsOptional()
  @IsString()
  startDate?: string;

  @IsOptional()
  @IsString()
  endDate?: string;

  @IsOptional()
  @IsArray()
  @IsString({ each: true })
  dimensions?: string[];

  @IsOptional()
  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => FilterCondition)
  filters?: FilterCondition[];
}

export class ListTablesParams {
  @IsString()
  @IsNotEmpty()
  dataSourceId: string;
}

export class ListColumnsParams {
  @IsString()
  @IsNotEmpty()
  dataSourceId: string;

  @IsString()
  @IsNotEmpty()
  tableName: string;
}

export class GenerateSqlDto extends VisualMetricConfig {}

export class BuildMetricDto extends VisualMetricConfig {}

export class CreateMetricFromVisualDto extends VisualMetricConfig {
  @IsString()
  @IsNotEmpty()
  name: string;

  @IsString()
  @IsNotEmpty()
  description: string;

  @IsOptional()
  @IsEnum(['HOUR', 'DAY', 'WEEK', 'MONTH', 'QUARTER', 'YEAR'])
  timeWindow?: string;

  @IsOptional()
  isAutoCompare?: boolean;
}
