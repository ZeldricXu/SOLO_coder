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
import { MetricService } from './metric.service';
import { CreateMetricDto } from './dto/create-metric.dto';
import { UpdateMetricDto } from './dto/update-metric.dto';
import { ExecuteMetricDto } from './dto/execute-metric.dto';
import { ComparisonDto } from './dto/comparison.dto';

@Controller('metrics')
export class MetricController {
  constructor(private readonly metricService: MetricService) {}

  @Post()
  create(@Body() dto: CreateMetricDto) {
    return this.metricService.create(dto);
  }

  @Get('templates')
  getTemplates(@Query('category') category?: string) {
    return this.metricService.getTemplates(category);
  }

  @Get()
  findAll(@Query('businessLineId') businessLineId?: string) {
    return this.metricService.findAll(businessLineId);
  }

  @Get(':id')
  findOne(@Param('id') id: string) {
    return this.metricService.findOne(id);
  }

  @Put(':id')
  update(@Param('id') id: string, @Body() dto: UpdateMetricDto) {
    return this.metricService.update(id, dto);
  }

  @Delete(':id')
  remove(@Param('id') id: string) {
    return this.metricService.remove(id);
  }

  @Post(':id/execute')
  execute(@Param('id') id: string, @Body() dto: ExecuteMetricDto) {
    return this.metricService.execute(id, dto);
  }

  @Get(':id/comparison')
  getComparison(@Param('id') id: string, @Query() dto: ComparisonDto) {
    return this.metricService.getComparison(id, dto);
  }
}
