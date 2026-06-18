import { IsString, IsEnum, IsObject, IsOptional, IsInt, Min, IsNotEmpty } from 'class-validator';
import { DataSourceType } from '@prisma/client';

export class CreateDataSourceDto {
  @IsString()
  @IsNotEmpty()
  name: string;

  @IsEnum(DataSourceType)
  type: DataSourceType;

  @IsObject()
  config: Record<string, any>;

  @IsOptional()
  @IsInt()
  @Min(1)
  poolSize?: number;

  @IsOptional()
  @IsInt()
  @Min(1000)
  queryTimeout?: number;

  @IsString()
  @IsNotEmpty()
  businessLineId: string;
}
