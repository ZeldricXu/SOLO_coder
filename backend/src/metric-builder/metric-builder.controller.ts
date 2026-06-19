import {
  Controller,
  Get,
  Post,
  Body,
  Param,
  Query,
} from '@nestjs/common';
import { MetricBuilderService } from './metric-builder.service';
import {
  GenerateSqlDto,
  BuildMetricDto,
  CreateMetricFromVisualDto,
} from './dto/build-metric.dto';
import { CurrentUser } from '../common/decorators/current-user.decorator';

@Controller('metric-builder')
export class MetricBuilderController {
  constructor(private readonly metricBuilderService: MetricBuilderService) {}

  @Get(':dataSourceId/tables')
  listTables(@Param('dataSourceId') dataSourceId: string) {
    return this.metricBuilderService.listTables(dataSourceId);
  }

  @Get(':dataSourceId/columns')
  listColumns(
    @Param('dataSourceId') dataSourceId: string,
    @Query('tableName') tableName: string,
  ) {
    return this.metricBuilderService.listColumns(dataSourceId, tableName);
  }

  @Post(':dataSourceId/generate-sql')
  async generateSql(
    @Param('dataSourceId') dataSourceId: string,
    @Body() config: GenerateSqlDto,
  ) {
    const sql = await this.metricBuilderService.generateSql(dataSourceId, config);
    return { sql };
  }

  @Post(':dataSourceId/preview')
  preview(
    @Param('dataSourceId') dataSourceId: string,
    @Body() config: BuildMetricDto,
  ) {
    return this.metricBuilderService.buildMetric(dataSourceId, config);
  }

  @Post(':dataSourceId/create')
  createMetric(
    @CurrentUser('id') userId: string,
    @Param('dataSourceId') dataSourceId: string,
    @Query('businessLineId') businessLineId: string,
    @Body() config: CreateMetricFromVisualDto,
  ) {
    return this.metricBuilderService.createMetricFromVisual(
      userId,
      businessLineId,
      dataSourceId,
      config,
    );
  }
}
