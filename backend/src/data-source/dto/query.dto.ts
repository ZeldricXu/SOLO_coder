import { IsString, IsNotEmpty, IsOptional, IsArray, IsInt, Min } from 'class-validator';

export class QueryDto {
  @IsString()
  @IsNotEmpty({ message: 'SQL query is required' })
  sql: string;

  @IsOptional()
  @IsArray()
  params?: any[];

  @IsOptional()
  @IsInt()
  @Min(1000)
  timeout?: number;
}
