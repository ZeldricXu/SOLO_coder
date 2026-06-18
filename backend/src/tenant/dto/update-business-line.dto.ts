import { PartialType } from '@nestjs/mapped-types';
import { CreateBusinessLineDto } from './create-business-line.dto';

export class UpdateBusinessLineDto extends PartialType(CreateBusinessLineDto) {}
