import {
  IsString,
  IsEnum,
  IsOptional,
  IsArray,
  IsBoolean,
  IsNotEmpty,
} from 'class-validator';
import { MetricType, Aggregation, TimeWindow } from '@prisma/client';

export class CreateMetricDto {
  @IsString()
  @IsNotEmpty()
  name: string;

  @IsString()
  @IsNotEmpty()
  description: string;

  @IsEnum(MetricType)
  type: MetricType;

  @IsOptional()
  @IsString()
  sqlTemplate?: string;

  @IsOptional()
  @IsString()
  templateId?: string;

  @IsEnum(Aggregation)
  aggregation: Aggregation;

  @IsEnum(TimeWindow)
  timeWindow: TimeWindow;

  @IsOptional()
  @IsArray()
  @IsString({ each: true })
  dimensions?: string[];

  @IsString()
  @IsNotEmpty()
  dataSourceId: string;

  @IsString()
  @IsNotEmpty()
  businessLineId: string;

  @IsOptional()
  @IsBoolean()
  isAutoCompare?: boolean;
}
