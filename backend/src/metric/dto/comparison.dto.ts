import { IsEnum, IsNotEmpty, ValidateNested, IsString } from 'class-validator';
import { Type } from 'class-transformer';

class DateRangeDto {
  @IsString()
  @IsNotEmpty()
  start: string;

  @IsString()
  @IsNotEmpty()
  end: string;
}

export class ComparisonDto {
  @IsEnum(['yoy', 'mom'])
  type: 'yoy' | 'mom';

  @ValidateNested()
  @Type(() => DateRangeDto)
  dateRange: DateRangeDto;
}
