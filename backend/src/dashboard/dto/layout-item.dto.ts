import { IsString, IsNumber, IsNotEmpty } from 'class-validator';

export class LayoutItemDto {
  @IsString()
  @IsNotEmpty()
  widgetId: string;

  @IsNumber()
  x: number;

  @IsNumber()
  y: number;

  @IsNumber()
  w: number;

  @IsNumber()
  h: number;
}
