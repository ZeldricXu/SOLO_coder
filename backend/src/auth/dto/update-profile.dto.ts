import { IsString, IsOptional, MinLength } from 'class-validator';

export class UpdateProfileDto {
  @IsOptional()
  @IsString()
  name?: string;

  @IsOptional()
  @IsString()
  oldPassword?: string;

  @IsOptional()
  @IsString()
  @MinLength(8)
  newPassword?: string;
}
