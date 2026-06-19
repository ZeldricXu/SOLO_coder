import { PartialType } from '@nestjs/mapped-types';
import { CreateDashboardDto } from './create-dashboard.dto';
import { IsOptional, IsInt, Min } from 'class-validator';

export class UpdateDashboardDto extends PartialType(CreateDashboardDto) {
  @IsOptional()
  @IsInt()
  @Min(1)
  expectedVersion?: number;
}
