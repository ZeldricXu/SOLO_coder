import { IsArray, ValidateNested } from 'class-validator';
import { Type } from 'class-transformer';
import { LayoutItemDto } from './layout-item.dto';

export class BatchLayoutDto {
  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => LayoutItemDto)
  items: LayoutItemDto[];
}
