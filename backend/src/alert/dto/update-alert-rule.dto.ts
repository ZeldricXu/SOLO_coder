import { PartialType } from '@nestjs/mapped-types';
import { CreateAlertRuleDto } from './create-alert-rule.dto';
import { IsOptional, IsInt, IsString, Min } from 'class-validator';

export class UpdateAlertRuleDto extends PartialType(CreateAlertRuleDto) {
  @IsOptional()
  @IsInt()
  @Min(1)
  consecutiveThreshold?: number;

  @IsOptional()
  @IsInt()
  @Min(1)
  dedupMinutes?: number;

  @IsOptional()
  @IsString()
  aggregationGroup?: string;
}
