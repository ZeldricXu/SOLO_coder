import {
  Controller,
  Get,
  Post,
  Put,
  Delete,
  Patch,
  Body,
  Param,
  Query,
  UseGuards,
} from '@nestjs/common';
import { AlertService } from './alert.service';
import { CreateAlertRuleDto } from './dto/create-alert-rule.dto';
import { UpdateAlertRuleDto } from './dto/update-alert-rule.dto';
import { AcknowledgeDto } from './dto/acknowledge.dto';
import { CurrentUser } from '../common/decorators/current-user.decorator';
import { JwtAuthGuard } from '../common/guards/jwt-auth.guard';

@Controller('alerts')
export class AlertController {
  constructor(private readonly alertService: AlertService) {}

  @Post('rules')
  createRule(@Body() dto: CreateAlertRuleDto) {
    return this.alertService.create(dto);
  }

  @Get('rules')
  findRules(
    @Query('metricId') metricId?: string,
    @Query('businessLineId') businessLineId?: string,
  ) {
    return this.alertService.findAll(metricId, businessLineId);
  }

  @Get('rules/:id')
  findOneRule(@Param('id') id: string) {
    return this.alertService.findOne(id);
  }

  @Put('rules/:id')
  updateRule(@Param('id') id: string, @Body() dto: UpdateAlertRuleDto) {
    return this.alertService.update(id, dto);
  }

  @Delete('rules/:id')
  removeRule(@Param('id') id: string) {
    return this.alertService.remove(id);
  }

  @Patch('rules/:id/toggle')
  toggleRule(@Param('id') id: string) {
    return this.alertService.toggle(id);
  }

  @Post('rules/:id/acknowledge')
  @UseGuards(JwtAuthGuard)
  async acknowledgeRule(
    @Param('id') id: string,
    @CurrentUser() user: any,
  ) {
    return this.alertService.acknowledgeRule(id, user.sub ?? user.id);
  }

  @Post('flush-aggregations')
  async flushAggregations() {
    return this.alertService.flushAggregations();
  }

  @Get('records')
  findRecords(
    @Query('ruleId') ruleId?: string,
    @Query('acknowledged') acknowledged?: string,
  ) {
    const ack = acknowledged !== undefined ? acknowledged === 'true' : undefined;
    return this.alertService.findRecords(ruleId, ack);
  }

  @Patch('records/:id/acknowledge')
  acknowledgeRecord(@Param('id') id: string, @Body() dto: AcknowledgeDto) {
    return this.alertService.acknowledgeRecord(id, dto.acknowledgedBy);
  }

  @Get('rules/:id/history')
  getHistory(@Param('id') id: string) {
    return this.alertService.getHistory(id);
  }
}
