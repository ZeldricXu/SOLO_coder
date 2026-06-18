import { IsString, IsNotEmpty } from 'class-validator';

export class LinkWidgetDto {
  @IsString()
  @IsNotEmpty()
  targetWidgetId: string;
}
