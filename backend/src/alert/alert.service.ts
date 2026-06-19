import { Injectable, NotFoundException, Logger } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { MetricService } from '../metric/metric.service';
import { NotificationService } from './notification.service';
import { CreateAlertRuleDto } from './dto/create-alert-rule.dto';
import { UpdateAlertRuleDto } from './dto/update-alert-rule.dto';
import { AlertMessage } from './notifiers/base.notifier';

interface PendingAggregationItem {
  ruleId: string;
  ruleName: string;
  metricName: string;
  businessLineId: string;
  aggregationGroup: string;
  value: number;
  message: string;
  condition: Record<string, any>;
  dashboardUrl: string;
  recordId: string;
  channels: { type: string; target: string }[];
  escalationMinutes: number;
  escalationChannels: { type: string; target: string }[] | null;
  createdAt: number;
}

interface AggregationKey {
  businessLineId: string;
  aggregationGroup: string;
  windowBucket: number;
}

@Injectable()
export class AlertService {
  private readonly logger = new Logger(AlertService.name);
  private readonly pendingAggregations: Map<string, PendingAggregationItem[]> =
    new Map();

  constructor(
    private readonly prisma: PrismaService,
    private readonly metricService: MetricService,
    private readonly notificationService: NotificationService,
  ) {}

  async create(dto: CreateAlertRuleDto) {
    const rule = await this.prisma.alertRule.create({
      data: {
        name: dto.name,
        type: dto.type,
        condition: dto.condition,
        metricId: dto.metricId,
        channels: dto.channels as any,
        silenceMinutes: dto.silenceMinutes ?? 60,
        escalationMinutes: dto.escalationMinutes ?? 0,
        escalationChannels: dto.escalationChannels ? (dto.escalationChannels as any) : null,
        consecutiveThreshold: dto.consecutiveThreshold ?? 1,
        dedupMinutes: dto.dedupMinutes ?? 30,
        aggregationGroup: dto.aggregationGroup ?? null,
      },
      include: { metric: true },
    });

    if (rule.isActive) {
      await this.scheduleEvaluation(rule.id);
    }

    return rule;
  }

  async findAll(metricId?: string, businessLineId?: string) {
    const where: Record<string, any> = {};
    if (metricId) where.metricId = metricId;
    if (businessLineId) where.metric = { businessLineId };

    return this.prisma.alertRule.findMany({
      where,
      orderBy: { createdAt: 'desc' },
      include: { metric: true },
    });
  }

  async findOne(id: string) {
    const rule = await this.prisma.alertRule.findUnique({
      where: { id },
      include: { metric: true },
    });
    if (!rule) {
      throw new NotFoundException(`Alert rule ${id} not found`);
    }
    return rule;
  }

  async update(id: string, dto: UpdateAlertRuleDto) {
    const existing = await this.findOne(id);

    const data: Record<string, any> = {};
    if (dto.name !== undefined) data.name = dto.name;
    if (dto.type !== undefined) data.type = dto.type;
    if (dto.condition !== undefined) data.condition = dto.condition;
    if (dto.metricId !== undefined) data.metricId = dto.metricId;
    if (dto.channels !== undefined) data.channels = dto.channels;
    if (dto.silenceMinutes !== undefined) data.silenceMinutes = dto.silenceMinutes;
    if (dto.escalationMinutes !== undefined) data.escalationMinutes = dto.escalationMinutes;
    if (dto.escalationChannels !== undefined) data.escalationChannels = dto.escalationChannels;
    if (dto.consecutiveThreshold !== undefined) data.consecutiveThreshold = dto.consecutiveThreshold;
    if (dto.dedupMinutes !== undefined) data.dedupMinutes = dto.dedupMinutes;
    if (dto.aggregationGroup !== undefined) data.aggregationGroup = dto.aggregationGroup;

    const rule = await this.prisma.alertRule.update({
      where: { id },
      data,
      include: { metric: true },
    });

    if (dto.isActive !== undefined && dto.isActive !== existing.isActive) {
      if (dto.isActive) {
        await this.scheduleEvaluation(id);
      } else {
        await this.removeEvaluation(id);
      }
    }

    return rule;
  }

  async remove(id: string) {
    await this.findOne(id);
    await this.removeEvaluation(id);
    return this.prisma.alertRule.delete({ where: { id } });
  }

  async toggle(id: string) {
    const rule = await this.findOne(id);
    const isActive = !rule.isActive;

    const updated = await this.prisma.alertRule.update({
      where: { id },
      data: { isActive },
      include: { metric: true },
    });

    if (isActive) {
      await this.scheduleEvaluation(id);
    } else {
      await this.removeEvaluation(id);
    }

    return updated;
  }

  async findRecords(ruleId?: string, acknowledged?: boolean) {
    const where: Record<string, any> = {};
    if (ruleId) where.ruleId = ruleId;
    if (acknowledged !== undefined) where.acknowledged = acknowledged;

    return this.prisma.alertRecord.findMany({
      where,
      orderBy: { createdAt: 'desc' },
      include: { rule: { include: { metric: true } } },
    });
  }

  async acknowledgeRecord(id: string, acknowledgedBy: string) {
    const record = await this.prisma.alertRecord.findUnique({ where: { id } });
    if (!record) {
      throw new NotFoundException(`Alert record ${id} not found`);
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

  async acknowledgeRule(ruleId: string, userId: string) {
    const rule = await this.findOne(ruleId);

    const latestRecord = await this.prisma.alertRecord.findFirst({
      where: { ruleId },
      orderBy: { createdAt: 'desc' },
    });

    if (latestRecord && !latestRecord.acknowledged) {
      await this.prisma.alertRecord.update({
        where: { id: latestRecord.id },
        data: {
          acknowledged: true,
          acknowledgedBy: userId,
          acknowledgedAt: new Date(),
        },
      });
    }

    await this.prisma.alertRule.update({
      where: { id: ruleId },
      data: { hitCount: 0 },
    });

    return { ruleId, acknowledged: true, hitCountReset: true };
  }

  async getHistory(ruleId: string) {
    await this.findOne(ruleId);
    return this.prisma.alertRecord.findMany({
      where: { ruleId },
      orderBy: { createdAt: 'desc' },
    });
  }

  async evaluateRule(ruleId: string): Promise<void> {
    const rule = await this.prisma.alertRule.findUnique({
      where: { id: ruleId },
      include: { metric: true },
    });

    if (!rule || !rule.isActive) {
      return;
    }

    if (rule.lastTriggeredAt) {
      const silenceEnd = new Date(
        rule.lastTriggeredAt.getTime() + rule.silenceMinutes * 60 * 1000,
      );
      if (new Date() < silenceEnd) {
        this.logger.debug(`Rule ${ruleId} is within silence period, skipping`);
        return;
      }
    }

    await this.evaluateWithNoiseReduction(rule);
  }

  private async evaluateWithNoiseReduction(rule: any): Promise<void> {
    const condition = rule.condition as Record<string, any>;
    let triggered = false;
    let value = 0;
    let message = '';

    switch (rule.type) {
      case 'THRESHOLD': {
        const result = await this.evaluateThreshold(rule, condition);
        triggered = result.violated;
        value = result.value;
        message = result.message;
        break;
      }
      case 'FLUCTUATION': {
        const result = await this.evaluateFluctuation(rule, condition);
        triggered = result.violated;
        value = result.value;
        message = result.message;
        break;
      }
      case 'STREAM_BREAK': {
        const result = await this.evaluateStreamBreak(rule, condition);
        triggered = result.violated;
        value = result.value;
        message = result.message;
        break;
      }
    }

    let hitCount = rule.hitCount || 0;
    let shouldFire = false;

    if (triggered) {
      hitCount += 1;
      if (hitCount >= rule.consecutiveThreshold) {
        shouldFire = true;
        hitCount = 0;
      }
    } else {
      hitCount = 0;
    }

    const now = new Date();
    const nowMs = now.getTime();
    const dedupWindowMs = (rule.dedupMinutes || 30) * 60 * 1000;
    const inDedupWindow =
      rule.lastTriggeredAt &&
      nowMs - rule.lastTriggeredAt.getTime() < dedupWindowMs;

    const shouldSendNotification = shouldFire && !inDedupWindow;

    await this.prisma.alertRule.update({
      where: { id: rule.id },
      data: {
        hitCount,
        lastTriggeredAt: shouldFire ? now : rule.lastTriggeredAt,
      },
    });

    if (!shouldFire) {
      return;
    }

    const record = await this.prisma.alertRecord.create({
      data: {
        ruleId: rule.id,
        value,
        message,
        notified: shouldSendNotification ? false : false,
      },
    });

    if (!shouldSendNotification) {
      this.logger.debug(
        `Rule ${rule.id} fire but in dedup window (hitCount reset), record ${record.id} (notified=false)`,
      );
      return;
    }

    const alertMessage: AlertMessage = {
      ruleName: rule.name,
      metricName: rule.metric.name,
      value,
      condition,
      timestamp: now,
      dashboardUrl: `${process.env.APP_URL || 'http://localhost:3000'}/metrics/${rule.metricId}`,
    };

    const channels = rule.channels as { type: string; target: string }[];
    const escalationChannels = rule.escalationChannels as { type: string; target: string }[] | null;

    if (rule.aggregationGroup) {
      const windowBucket = Math.floor(nowMs / 60000);
      const key = this.getAggregationKey({
        businessLineId: rule.metric.businessLineId,
        aggregationGroup: rule.aggregationGroup,
        windowBucket,
      });

      const items = this.pendingAggregations.get(key) || [];
      items.push({
        ruleId: rule.id,
        ruleName: rule.name,
        metricName: rule.metric.name,
        businessLineId: rule.metric.businessLineId,
        aggregationGroup: rule.aggregationGroup,
        value,
        message,
        condition,
        dashboardUrl: alertMessage.dashboardUrl,
        recordId: record.id,
        channels,
        escalationMinutes: rule.escalationMinutes,
        escalationChannels,
        createdAt: nowMs,
      });
      this.pendingAggregations.set(key, items);

      this.logger.debug(
        `Rule ${rule.id} added to aggregation queue ${key} (size=${items.length})`,
      );

      const hasThree = items.length >= 3;
      const oldestOverTwoMinutes = items.length > 0 && nowMs - items[0].createdAt > 2 * 60 * 1000;

      if (hasThree || oldestOverTwoMinutes) {
        await this.flushAggregationByKey(key);
      }
    } else {
      await this.notificationService.sendNotifications(
        rule.id,
        channels,
        rule.escalationMinutes,
        escalationChannels,
        alertMessage,
      );

      this.logger.warn(
        `Alert triggered: ${rule.name} - ${message} (record ${record.id})`,
      );
    }
  }

  private getAggregationKey(key: AggregationKey): string {
    return `${key.businessLineId}::${key.aggregationGroup}::${key.windowBucket}`;
  }

  private parseAggregationKey(keyStr: string): AggregationKey {
    const [businessLineId, aggregationGroup, windowBucketStr] = keyStr.split('::');
    return {
      businessLineId,
      aggregationGroup,
      windowBucket: Number(windowBucketStr),
    };
  }

  private async fetchBusinessLineName(businessLineId: string): Promise<string> {
    try {
      const bl = await this.prisma.businessLine.findUnique({
        where: { id: businessLineId },
        select: { name: true },
      });
      return bl?.name || businessLineId;
    } catch {
      return businessLineId;
    }
  }

  async flushAggregations(): Promise<{ flushed: number; messages: string[] }> {
    const flushed: string[] = [];
    let count = 0;

    const keys = Array.from(this.pendingAggregations.keys());
    for (const key of keys) {
      const items = this.pendingAggregations.get(key);
      if (!items || items.length === 0) continue;

      const result = await this.flushAggregationByKey(key);
      if (result) {
        count += 1;
        flushed.push(result);
      }
    }

    return { flushed: count, messages: flushed };
  }

  private async flushAggregationByKey(keyStr: string): Promise<string | null> {
    const items = this.pendingAggregations.get(keyStr);
    if (!items || items.length === 0) {
      this.pendingAggregations.delete(keyStr);
      return null;
    }

    const key = this.parseAggregationKey(keyStr);
    const businessLineName = await this.fetchBusinessLineName(key.businessLineId);
    const groupName = key.aggregationGroup;
    const count = items.length;

    const bodyLines = items.map((item, idx) => {
      const line = `${idx + 1}. ${item.ruleName} - ${item.message}`;
      return line.length > 120 ? line.substring(0, 117) + '...' : line;
    });

    const first = items[0];
    const title = `[聚合告警] ${businessLineName}-${groupName} ${count}条告警`;
    const body = bodyLines.join('\n');
    const ruleIds = items.map((i) => i.ruleId);
    const values = items.map((i) => i.value);

    const mergedMessage: AlertMessage = {
      ruleName: title,
      metricName: groupName,
      value: values[0],
      condition: {
        aggregated: true,
        title,
        body,
        ruleIds,
        values,
        source: 'aggregation',
      },
      timestamp: new Date(),
      dashboardUrl: first.dashboardUrl,
    };

    const representativeChannels = first.channels;
    const escalationMinutes = first.escalationMinutes;
    const escalationChannels = first.escalationChannels;

    const allRecordIds = items.map((i) => i.recordId);

    try {
      await this.notificationService.sendNotifications(
        first.ruleId,
        representativeChannels,
        escalationMinutes,
        escalationChannels,
        mergedMessage,
      );

      await this.prisma.alertRecord.updateMany({
        where: { id: { in: allRecordIds } },
        data: { notified: true, notifiedAt: new Date() },
      });

      this.logger.warn(
        `Aggregation flushed: ${title} (${count} items, records: ${allRecordIds.join(',')})`,
      );
    } catch (error) {
      this.logger.error(`Failed to flush aggregation ${keyStr}: ${error.message}`);
    }

    this.pendingAggregations.delete(keyStr);
    return title;
  }

  private async evaluateThreshold(
    rule: any,
    condition: Record<string, any>,
  ): Promise<{ violated: boolean; value: number; message: string }> {
    const now = new Date();
    const start = new Date(now.getTime() - 24 * 60 * 60 * 1000);

    const result = await this.metricService.execute(rule.metricId, {
      dateRange: { start: start.toISOString(), end: now.toISOString() },
    });

    const value = this.extractValue(result.data);
    const messages: string[] = [];

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

  private async evaluateFluctuation(
    rule: any,
    condition: Record<string, any>,
  ): Promise<{ violated: boolean; value: number; message: string }> {
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

  private async evaluateStreamBreak(
    rule: any,
    condition: Record<string, any>,
  ): Promise<{ violated: boolean; value: number; message: string }> {
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

  private extractValue(rows: Record<string, any>[]): number {
    if (!rows || rows.length === 0) return 0;
    const first = rows[0];
    const numValues = Object.values(first).filter((v) => typeof v === 'number');
    if (numValues.length > 0) return numValues[0] as number;
    const aggregated = first.aggregated_value ?? first.value ?? first.count ?? first.sum;
    return aggregated != null ? Number(aggregated) : 0;
  }

  private async scheduleEvaluation(ruleId: string): Promise<void> {
    try {
      const { Queue } = await import('bullmq');
      const queue = new Queue('alert-evaluation', {
        connection: {
          host: process.env.REDIS_HOST || 'localhost',
          port: Number(process.env.REDIS_PORT) || 6379,
        },
      });

      await queue.add(
        'evaluate',
        { ruleId },
        {
          repeat: { every: 60000 },
          jobId: `alert-rule-${ruleId}`,
        },
      );

      await queue.close();
      this.logger.log(`Scheduled evaluation for rule ${ruleId}`);
    } catch (error) {
      this.logger.error(`Failed to schedule evaluation for rule ${ruleId}: ${error.message}`);
    }
  }

  private async removeEvaluation(ruleId: string): Promise<void> {
    try {
      const { Queue } = await import('bullmq');
      const queue = new Queue('alert-evaluation', {
        connection: {
          host: process.env.REDIS_HOST || 'localhost',
          port: Number(process.env.REDIS_PORT) || 6379,
        },
      });

      await queue.removeRepeatableByKey(`alert-rule-${ruleId}`);
      await queue.close();
      this.logger.log(`Removed evaluation schedule for rule ${ruleId}`);
    } catch (error) {
      this.logger.error(`Failed to remove evaluation for rule ${ruleId}: ${error.message}`);
    }
  }
}
