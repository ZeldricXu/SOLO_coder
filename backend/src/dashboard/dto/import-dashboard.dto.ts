import { IsObject } from 'class-validator';

export class ImportDashboardDto {
  @IsObject()
  data: Record<string, any>;
}
