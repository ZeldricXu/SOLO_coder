import {
  IsString,
  IsEnum,
  IsOptional,
  IsArray,
  IsNotEmpty,
  IsObject,
} from 'class-validator';
import { WidgetType } from '@prisma/client';

export class CreateWidgetDto {
  @IsEnum(WidgetType)
  type: WidgetType;

  @IsString()
  @IsNotEmpty()
  title: string;

  @IsOptional()
  @IsString()
  metricId?: string;

  @IsOptional()
  @IsObject()
  config?: Record<string, any>;

  @IsObject()
  layout: { x: number; y: number; w: number; h: number; minW?: number; minH?: number };

  @IsOptional()
  @IsObject()
  filters?: Record<string, any>;

  @IsOptional()
  @IsArray()
  @IsString({ each: true })
  linkedWidgetIds?: string[];
}
