import { Controller, Get, Param, Query, UseGuards } from '@nestjs/common';
import { AuditService } from './audit.service';
import { QueryAuditDto } from './dto/query-audit.dto';
import { JwtAuthGuard } from '../common/guards/jwt-auth.guard';

@Controller('audit')
@UseGuards(JwtAuthGuard)
export class AuditController {
  constructor(private readonly auditService: AuditService) {}

  @Get('logs')
  findAll(@Query() dto: QueryAuditDto) {
    return this.auditService.findAll(dto);
  }

  @Get('logs/:id')
  findOne(@Param('id') id: string) {
    return this.auditService.findOne(id);
  }
}
