import {
  IsString,
  IsOptional,
  IsBoolean,
  IsNotEmpty,
  IsObject,
} from 'class-validator';

export class CreateDashboardDto {
  @IsString()
  @IsNotEmpty()
  name: string;

  @IsOptional()
  @IsString()
  description?: string;

  @IsString()
  @IsNotEmpty()
  businessLineId: string;

  @IsOptional()
  @IsBoolean()
  isPublic?: boolean;

  @IsOptional()
  @IsObject()
  layout?: Record<string, any>;

  @IsOptional()
  @IsObject()
  globalFilters?: Record<string, any>;
}
