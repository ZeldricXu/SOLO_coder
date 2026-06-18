import { IsString, IsNotEmpty } from 'class-validator';

export class AcknowledgeDto {
  @IsString()
  @IsNotEmpty()
  acknowledgedBy: string;
}
