import { IsNotEmpty, IsOptional, IsString, ValidateNested, IsArray } from 'class-validator';
import { Type } from 'class-transformer';

class DateRangeDto {
  @IsString()
  @IsNotEmpty()
  start: string;

  @IsString()
  @IsNotEmpty()
  end: string;
}

export class ExecuteMetricDto {
  @ValidateNested()
  @Type(() => DateRangeDto)
  dateRange: DateRangeDto;

  @IsOptional()
  @IsArray()
  @IsString({ each: true })
  dimensions?: string[];

  @IsOptional()
  filters?: Record<string, any>;

  @IsOptional()
  @IsString()
  granularity?: 'hour' | 'day' | 'week' | 'month';
}
