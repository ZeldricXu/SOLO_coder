"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
var AlertService_1;
Object.defineProperty(exports, "__esModule", { value: true });
exports.AlertService = void 0;
const common_1 = require("@nestjs/common");
const prisma_service_1 = require("../prisma/prisma.service");
const metric_service_1 = require("../metric/metric.service");
const notification_service_1 = require("./notification.service");
let AlertService = AlertService_1 = class AlertService {
    constructor(prisma, metricService, notificationService) {
        this.prisma = prisma;
        this.metricService = metricService;
        this.notificationService = notificationService;
        this.logger = new common_1.Logger(AlertService_1.name);
    }
    async create(dto) {
        const rule = await this.prisma.alertRule.create({
            data: {
                name: dto.name,
                type: dto.type,
                condition: dto.condition,
                metricId: dto.metricId,
                channels: dto.channels,
                silenceMinutes: dto.silenceMinutes ?? 60,
                escalationMinutes: dto.escalationMinutes ?? 0,
                escalationChannels: dto.escalationChannels ? dto.escalationChannels : null,
            },
            include: { metric: true },
        });
        if (rule.isActive) {
            await this.scheduleEvaluation(rule.id);
        }
        return rule;
    }
    async findAll(metricId, businessLineId) {
        const where = {};
        if (metricId)
            where.metricId = metricId;
        if (businessLineId)
            where.metric = { businessLineId };
        return this.prisma.alertRule.findMany({
            where,
            orderBy: { createdAt: 'desc' },
            include: { metric: true },
        });
    }
    async findOne(id) {
        const rule = await this.prisma.alertRule.findUnique({
            where: { id },
            include: { metric: true },
        });
        if (!rule) {
            throw new common_1.NotFoundException(`Alert rule ${id} not found`);
        }
        return rule;
    }
    async update(id, dto) {
        const existing = await this.findOne(id);
        const data = {};
        if (dto.name !== undefined)
            data.name = dto.name;
        if (dto.type !== undefined)
            data.type = dto.type;
        if (dto.condition !== undefined)
            data.condition = dto.condition;
        if (dto.metricId !== undefined)
            data.metricId = dto.metricId;
        if (dto.channels !== undefined)
            data.channels = dto.channels;
        if (dto.silenceMinutes !== undefined)
            data.silenceMinutes = dto.silenceMinutes;
        if (dto.escalationMinutes !== undefined)
            data.escalationMinutes = dto.escalationMinutes;
        if (dto.escalationChannels !== undefined)
            data.escalationChannels = dto.escalationChannels;
        const rule = await this.prisma.alertRule.update({
            where: { id },
            data,
            include: { metric: true },
        });
        if (dto.isActive !== undefined && dto.isActive !== existing.isActive) {
            if (dto.isActive) {
                await this.scheduleEvaluation(id);
            }
            else {
                await this.removeEvaluation(id);
            }
        }
        return rule;
    }
    async remove(id) {
        await this.findOne(id);
        await this.removeEvaluation(id);
        return this.prisma.alertRule.delete({ where: { id } });
    }
    async toggle(id) {
        const rule = await this.findOne(id);
        const isActive = !rule.isActive;
        const updated = await this.prisma.alertRule.update({
            where: { id },
            data: { isActive },
            include: { metric: true },
        });
        if (isActive) {
            await this.scheduleEvaluation(id);
        }
        else {
            await this.removeEvaluation(id);
        }
        return updated;
    }
    async findRecords(ruleId, acknowledged) {
        const where = {};
        if (ruleId)
            where.ruleId = ruleId;
        if (acknowledged !== undefined)
            where.acknowledged = acknowledged;
        return this.prisma.alertRecord.findMany({
            where,
            orderBy: { createdAt: 'desc' },
            include: { rule: { include: { metric: true } } },
        });
    }
    async acknowledgeRecord(id, acknowledgedBy) {
        const record = await this.prisma.alertRecord.findUnique({ where: { id } });
        if (!record) {
            throw new common_1.NotFoundException(`Alert record ${id} not found`);
        }
        return this.prisma.alertRecord.update({
            where: { id },
            data: {
                acknowledged: true,
                acknowledgedBy,
                acknowledgedAt: new Date(),
            },
        });
    }
    async getHistory(ruleId) {
        await this.findOne(ruleId);
        return this.prisma.alertRecord.findMany({
            where: { ruleId },
            orderBy: { createdAt: 'desc' },
        });
    }
    async evaluateRule(ruleId) {
        const rule = await this.prisma.alertRule.findUnique({
            where: { id: ruleId },
            include: { metric: true },
        });
        if (!rule || !rule.isActive) {
            return;
        }
        if (rule.lastTriggeredAt) {
            const silenceEnd = new Date(rule.lastTriggeredAt.getTime() + rule.silenceMinutes * 60 * 1000);
            if (new Date() < silenceEnd) {
                this.logger.debug(`Rule ${ruleId} is within silence period, skipping`);
                return;
            }
        }
        const condition = rule.condition;
        let violated = false;
        let value = 0;
        let message = '';
        switch (rule.type) {
            case 'THRESHOLD':
                ({ violated, value, message } = await this.evaluateThreshold(rule, condition));
                break;
            case 'FLUCTUATION':
                ({ violated, value, message } = await this.evaluateFluctuation(rule, condition));
                break;
            case 'STREAM_BREAK':
                ({ violated, value, message } = await this.evaluateStreamBreak(rule, condition));
                break;
        }
        if (!violated) {
            return;
        }
        const alertMessage = {
            ruleName: rule.name,
            metricName: rule.metric.name,
            value,
            condition,
            timestamp: new Date(),
            dashboardUrl: `${process.env.APP_URL || 'http://localhost:3000'}/metrics/${rule.metricId}`,
        };
        const record = await this.prisma.alertRecord.create({
            data: {
                ruleId: rule.id,
                value,
                message,
                notified: false,
            },
        });
        await this.prisma.alertRule.update({
            where: { id: ruleId },
            data: { lastTriggeredAt: new Date() },
        });
        const channels = rule.channels;
        const escalationChannels = rule.escalationChannels;
        await this.notificationService.sendNotifications(ruleId, channels, rule.escalationMinutes, escalationChannels, alertMessage);
        this.logger.warn(`Alert triggered: ${rule.name} - ${message} (record ${record.id})`);
    }
    async evaluateThreshold(rule, condition) {
        const now = new Date();
        const start = new Date(now.getTime() - 24 * 60 * 60 * 1000);
        const result = await this.metricService.execute(rule.metricId, {
            dateRange: { start: start.toISOString(), end: now.toISOString() },
        });
        const value = this.extractValue(result.data);
        const messages = [];
        if (condition.upper !== undefined && value > condition.upper) {
            messages.push(`value ${value} exceeds upper threshold ${condition.upper}`);
        }
        if (condition.lower !== undefined && value < condition.lower) {
            messages.push(`value ${value} is below lower threshold ${condition.lower}`);
        }
        return {
            violated: messages.length > 0,
            value,
            message: messages.join('; '),
        };
    }
    async evaluateFluctuation(rule, condition) {
        const now = new Date();
        const start = new Date(now.getTime() - 24 * 60 * 60 * 1000);
        const result = await this.metricService.getComparison(rule.metricId, {
            type: 'mom',
            dateRange: { start: start.toISOString(), end: now.toISOString() },
        });
        const changeRate = result.changeRate;
        if (changeRate === null) {
            return { violated: false, value: 0, message: '' };
        }
        const fluctuationPercent = (condition.fluctuationPercent ?? 0) / 100;
        const violated = Math.abs(changeRate) > fluctuationPercent;
        return {
            violated,
            value: result.current.value ?? 0,
            message: violated
                ? `change rate ${(changeRate * 100).toFixed(2)}% exceeds fluctuation threshold ${condition.fluctuationPercent}%`
                : '',
        };
    }
    async evaluateStreamBreak(rule, condition) {
        const breakMinutes = condition.breakMinutes ?? 30;
        const cutoff = new Date(Date.now() - breakMinutes * 60 * 1000);
        const now = new Date();
        const result = await this.metricService.execute(rule.metricId, {
            dateRange: { start: cutoff.toISOString(), end: now.toISOString() },
        });
        const hasData = result.data && result.data.length > 0;
        const value = hasData ? this.extractValue(result.data) : 0;
        return {
            violated: !hasData,
            value,
            message: !hasData
                ? `no data received in the last ${breakMinutes} minutes`
                : '',
        };
    }
    extractValue(rows) {
        if (!rows || rows.length === 0)
            return 0;
        const first = rows[0];
        const numValues = Object.values(first).filter((v) => typeof v === 'number');
        if (numValues.length > 0)
            return numValues[0];
        const aggregated = first.aggregated_value ?? first.value ?? first.count ?? first.sum;
        return aggregated != null ? Number(aggregated) : 0;
    }
    async scheduleEvaluation(ruleId) {
        try {
            const { Queue } = await Promise.resolve().then(() => require('bullmq'));
            const queue = new Queue('alert-evaluation', {
                connection: {
                    host: process.env.REDIS_HOST || 'localhost',
                    port: Number(process.env.REDIS_PORT) || 6379,
                },
            });
            await queue.add('evaluate', { ruleId }, {
                repeat: { every: 60000 },
                jobId: `alert-rule-${ruleId}`,
            });
            await queue.close();
            this.logger.log(`Scheduled evaluation for rule ${ruleId}`);
        }
        catch (error) {
            this.logger.error(`Failed to schedule evaluation for rule ${ruleId}: ${error.message}`);
        }
    }
    async removeEvaluation(ruleId) {
        try {
            const { Queue } = await Promise.resolve().then(() => require('bullmq'));
            const queue = new Queue('alert-evaluation', {
                connection: {
                    host: process.env.REDIS_HOST || 'localhost',
                    port: Number(process.env.REDIS_PORT) || 6379,
                },
            });
            await queue.removeRepeatableByKey(`alert-rule-${ruleId}`);
            await queue.close();
            this.logger.log(`Removed evaluation schedule for rule ${ruleId}`);
        }
        catch (error) {
            this.logger.error(`Failed to remove evaluation for rule ${ruleId}: ${error.message}`);
        }
    }
};
exports.AlertService = AlertService;
exports.AlertService = AlertService = AlertService_1 = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [prisma_service_1.PrismaService,
        metric_service_1.MetricService,
        notification_service_1.NotificationService])
], AlertService);
//# sourceMappingURL=alert.service.js.map