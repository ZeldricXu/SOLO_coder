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
import { DashboardService } from './dashboard.service';
import { CreateDashboardDto } from './dto/create-dashboard.dto';
import { UpdateDashboardDto } from './dto/update-dashboard.dto';
import { CreateWidgetDto } from './dto/create-widget.dto';
import { UpdateWidgetDto } from './dto/update-widget.dto';
import { BatchLayoutDto } from './dto/batch-layout.dto';
import { LinkWidgetDto } from './dto/link-widget.dto';
import { ImportDashboardDto } from './dto/import-dashboard.dto';
import { CurrentUser } from '../common/decorators/current-user.decorator';

@Controller('dashboards')
export class DashboardController {
  constructor(private readonly dashboardService: DashboardService) {}

  @Post()
  create(@Body() dto: CreateDashboardDto, @CurrentUser() user: any) {
    return this.dashboardService.create(dto, user?.id);
  }

  @Get()
  findAll(@Query('businessLineId') businessLineId?: string) {
    return this.dashboardService.findAll(businessLineId);
  }

  @Get(':id')
  findOne(@Param('id') id: string) {
    return this.dashboardService.findOne(id);
  }

  @Put(':id')
  update(@Param('id') id: string, @Body() dto: UpdateDashboardDto) {
    return this.dashboardService.update(id, dto);
  }

  @Delete(':id')
  remove(@Param('id') id: string) {
    return this.dashboardService.remove(id);
  }

  @Post(':id/widgets')
  addWidget(@Param('id') id: string, @Body() dto: CreateWidgetDto) {
    return this.dashboardService.addWidget(id, dto);
  }

  @Put(':id/widgets/:widgetId')
  updateWidget(
    @Param('id') id: string,
    @Param('widgetId') widgetId: string,
    @Body() dto: UpdateWidgetDto,
  ) {
    return this.dashboardService.updateWidget(id, widgetId, dto);
  }

  @Delete(':id/widgets/:widgetId')
  removeWidget(
    @Param('id') id: string,
    @Param('widgetId') widgetId: string,
  ) {
    return this.dashboardService.removeWidget(id, widgetId);
  }

  @Put(':id/layout')
  batchUpdateLayout(
    @Param('id') id: string,
    @Body() dto: BatchLayoutDto,
  ) {
    return this.dashboardService.batchUpdateLayout(id, dto.items);
  }

  @Post(':id/widgets/:widgetId/link')
  linkWidget(
    @Param('id') id: string,
    @Param('widgetId') widgetId: string,
    @Body() dto: LinkWidgetDto,
  ) {
    return this.dashboardService.linkWidget(id, widgetId, dto.targetWidgetId);
  }

  @Delete(':id/widgets/:widgetId/link/:targetWidgetId')
  unlinkWidget(
    @Param('id') id: string,
    @Param('widgetId') widgetId: string,
    @Param('targetWidgetId') targetWidgetId: string,
  ) {
    return this.dashboardService.unlinkWidget(id, widgetId, targetWidgetId);
  }

  @Get(':id/export')
  exportDashboard(@Param('id') id: string) {
    return this.dashboardService.exportDashboard(id);
  }

  @Post('import')
  importDashboard(@Body() dto: ImportDashboardDto, @CurrentUser() user: any) {
    return this.dashboardService.importDashboard(dto.data, user?.id, dto.data?.businessLineId);
  }
}
