import {
  IsString,
  IsEnum,
  IsObject,
  IsOptional,
  IsInt,
  IsArray,
  IsNotEmpty,
  ValidateNested,
  IsBoolean,
  Min,
} from 'class-validator';
import { Type } from 'class-transformer';
import { AlertType } from '@prisma/client';

export enum AlertChannelType {
  EMAIL = 'EMAIL',
  WECOM = 'WECOM',
  DINGTALK = 'DINGTALK',
}

export class AlertChannelDto {
  @IsEnum(AlertChannelType)
  type: AlertChannelType;

  @IsString()
  @IsNotEmpty()
  target: string;
}

export class CreateAlertRuleDto {
  @IsString()
  @IsNotEmpty()
  name: string;

  @IsEnum(AlertType)
  type: AlertType;

  @IsObject()
  condition: Record<string, any>;

  @IsString()
  @IsNotEmpty()
  metricId: string;

  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => AlertChannelDto)
  channels: AlertChannelDto[];

  @IsOptional()
  @IsInt()
  silenceMinutes?: number;

  @IsOptional()
  @IsInt()
  escalationMinutes?: number;

  @IsOptional()
  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => AlertChannelDto)
  escalationChannels?: AlertChannelDto[];

  @IsOptional()
  @IsBoolean()
  isActive?: boolean;

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
