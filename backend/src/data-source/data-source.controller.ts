import {
  Controller,
  Get,
  Post,
  Put,
  Delete,
  Body,
  Param,
  Query,
} from '@nestjs/common';
import { DataSourceService } from './data-source.service';
import { CreateDataSourceDto } from './dto/create-data-source.dto';
import { UpdateDataSourceDto } from './dto/update-data-source.dto';
import { QueryDto } from './dto/query.dto';

@Controller('data-sources')
export class DataSourceController {
  constructor(private readonly dataSourceService: DataSourceService) {}

  @Post()
  create(@Body() dto: CreateDataSourceDto) {
    return this.dataSourceService.create(dto);
  }

  @Get()
  findAll(@Query('businessLineId') businessLineId?: string) {
    return this.dataSourceService.findAll(businessLineId);
  }

  @Get(':id')
  findOne(@Param('id') id: string) {
    return this.dataSourceService.findOne(id);
  }

  @Put(':id')
  update(@Param('id') id: string, @Body() dto: UpdateDataSourceDto) {
    return this.dataSourceService.update(id, dto);
  }

  @Delete(':id')
  remove(@Param('id') id: string) {
    return this.dataSourceService.remove(id);
  }

  @Post(':id/test')
  testConnection(@Param('id') id: string) {
    return this.dataSourceService.testConnection(id);
  }

  @Post(':id/query')
  executeQuery(@Param('id') id: string, @Body() dto: QueryDto) {
    return this.dataSourceService.executeQuery(id, dto);
  }

  @Get(':id/schema')
  inferSchema(@Param('id') id: string) {
    return this.dataSourceService.inferSchema(id);
  }
}
