import { IsString, IsNotEmpty } from 'class-validator';

export class CreateBusinessLineDto {
  @IsString()
  @IsNotEmpty()
  name: string;

  @IsString()
  @IsNotEmpty()
  code: string;
}
