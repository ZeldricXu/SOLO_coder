import { PartialType } from '@nestjs/mapped-types';
import { CreateDataSourceDto } from './create-data-source.dto';

export class UpdateDataSourceDto extends PartialType(CreateDataSourceDto) {}
