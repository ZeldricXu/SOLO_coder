"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const testing_1 = require("@nestjs/testing");
const alert_service_1 = require("./alert.service");
const prisma_service_1 = require("../prisma/prisma.service");
const metric_service_1 = require("../metric/metric.service");
const notification_service_1 = require("./notification.service");
const common_1 = require("@nestjs/common");
describe('AlertService', () => {
    let service;
    let prismaMock;
    let metricMock;
    let notificationMock;
    const baseRule = {
        id: 'rule-1',
        name: 'Test Rule',
        type: 'THRESHOLD',
        isActive: true,
        lastTriggeredAt: null,
        silenceMinutes: 60,
        escalationMinutes: 0,
        escalationChannels: null,
        channels: [{ type: 'email', target: 'admin@test.com' }],
        condition: { upper: 100, lower: 10 },
        metricId: 'metric-1',
        metric: { id: 'metric-1', name: 'Test Metric' },
    };
    beforeEach(async () => {
        prismaMock = {
            alertRule: {
                create: jest.fn(),
                findMany: jest.fn(),
                findUnique: jest.fn(),
                update: jest.fn(),
                delete: jest.fn(),
            },
            alertRecord: {
                create: jest.fn(),
                findMany: jest.fn(),
                findUnique: jest.fn(),
                update: jest.fn(),
                updateMany: jest.fn(),
            },
        };
        metricMock = {
            execute: jest.fn(),
            getComparison: jest.fn(),
        };
        notificationMock = {
            sendNotifications: jest.fn().mockResolvedValue(undefined),
        };
        const module = await testing_1.Test.createTestingModule({
            providers: [
                alert_service_1.AlertService,
                { provide: prisma_service_1.PrismaService, useValue: prismaMock },
                { provide: metric_service_1.MetricService, useValue: metricMock },
                { provide: notification_service_1.NotificationService, useValue: notificationMock },
            ],
        }).compile();
        service = module.get(alert_service_1.AlertService);
    });
    describe('evaluateRule - 阈值告警(THRESHOLD)', () => {
        it('值超过upper时触发告警', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                condition: { upper: 100 },
            });
            metricMock.execute.mockResolvedValue({
                data: [{ aggregated_value: 150 }],
            });
            prismaMock.alertRecord.create.mockResolvedValue({ id: 'rec-1' });
            prismaMock.alertRule.update.mockResolvedValue({});
            await service.evaluateRule('rule-1');
            expect(prismaMock.alertRecord.create).toHaveBeenCalledWith(expect.objectContaining({
                data: expect.objectContaining({
                    ruleId: 'rule-1',
                    value: 150,
                }),
            }));
            expect(notificationMock.sendNotifications).toHaveBeenCalled();
        });
        it('值低于lower时触发告警', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                type: 'THRESHOLD',
                condition: { lower: 10 },
            });
            metricMock.execute.mockResolvedValue({
                data: [{ aggregated_value: 5 }],
            });
            prismaMock.alertRecord.create.mockResolvedValue({ id: 'rec-2' });
            prismaMock.alertRule.update.mockResolvedValue({});
            await service.evaluateRule('rule-1');
            expect(prismaMock.alertRecord.create).toHaveBeenCalledWith(expect.objectContaining({
                data: expect.objectContaining({
                    value: 5,
                }),
            }));
        });
        it('值在范围内不触发告警', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                condition: { upper: 100, lower: 10 },
            });
            metricMock.execute.mockResolvedValue({
                data: [{ aggregated_value: 50 }],
            });
            await service.evaluateRule('rule-1');
            expect(prismaMock.alertRecord.create).not.toHaveBeenCalled();
            expect(notificationMock.sendNotifications).not.toHaveBeenCalled();
        });
        it('upper和lower同时存在，值违反upper时message包含upper信息', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                condition: { upper: 50, lower: 10 },
            });
            metricMock.execute.mockResolvedValue({
                data: [{ aggregated_value: 80 }],
            });
            prismaMock.alertRecord.create.mockResolvedValue({ id: 'rec-3' });
            prismaMock.alertRule.update.mockResolvedValue({});
            await service.evaluateRule('rule-1');
            const createCall = prismaMock.alertRecord.create.mock.calls[0][0];
            expect(createCall.data.message).toContain('exceeds upper threshold');
            expect(createCall.data.message).not.toContain('below lower threshold');
        });
    });
    describe('evaluateRule - 波动告警(FLUCTUATION)', () => {
        it('changeRate超过fluctuationPercent时触发', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                type: 'FLUCTUATION',
                condition: { fluctuationPercent: 20 },
            });
            metricMock.getComparison.mockResolvedValue({
                changeRate: 0.5,
                current: { value: 150 },
            });
            prismaMock.alertRecord.create.mockResolvedValue({ id: 'rec-4' });
            prismaMock.alertRule.update.mockResolvedValue({});
            await service.evaluateRule('rule-1');
            expect(prismaMock.alertRecord.create).toHaveBeenCalled();
            const createCall = prismaMock.alertRecord.create.mock.calls[0][0];
            expect(createCall.data.message).toContain('50.00%');
            expect(createCall.data.message).toContain('20%');
        });
        it('changeRate未超过fluctuationPercent时不触发', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                type: 'FLUCTUATION',
                condition: { fluctuationPercent: 80 },
            });
            metricMock.getComparison.mockResolvedValue({
                changeRate: 0.1,
                current: { value: 110 },
            });
            await service.evaluateRule('rule-1');
            expect(prismaMock.alertRecord.create).not.toHaveBeenCalled();
        });
        it('changeRate为null(previous=0)时不触发', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                type: 'FLUCTUATION',
                condition: { fluctuationPercent: 20 },
            });
            metricMock.getComparison.mockResolvedValue({
                changeRate: null,
                current: { value: 0 },
            });
            await service.evaluateRule('rule-1');
            expect(prismaMock.alertRecord.create).not.toHaveBeenCalled();
        });
        it('负向波动(abs)超过fluctuationPercent时也触发', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                type: 'FLUCTUATION',
                condition: { fluctuationPercent: 20 },
            });
            metricMock.getComparison.mockResolvedValue({
                changeRate: -0.5,
                current: { value: 50 },
            });
            prismaMock.alertRecord.create.mockResolvedValue({ id: 'rec-5' });
            prismaMock.alertRule.update.mockResolvedValue({});
            await service.evaluateRule('rule-1');
            expect(prismaMock.alertRecord.create).toHaveBeenCalled();
        });
    });
    describe('evaluateRule - 断流告警(STREAM_BREAK)', () => {
        it('无数据时触发告警', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                type: 'STREAM_BREAK',
                condition: { breakMinutes: 30 },
            });
            metricMock.execute.mockResolvedValue({
                data: [],
            });
            prismaMock.alertRecord.create.mockResolvedValue({ id: 'rec-6' });
            prismaMock.alertRule.update.mockResolvedValue({});
            await service.evaluateRule('rule-1');
            expect(prismaMock.alertRecord.create).toHaveBeenCalledWith(expect.objectContaining({
                data: expect.objectContaining({
                    message: expect.stringContaining('no data received'),
                }),
            }));
        });
        it('有数据时不触发告警', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                type: 'STREAM_BREAK',
                condition: { breakMinutes: 30 },
            });
            metricMock.execute.mockResolvedValue({
                data: [{ count: 5 }],
            });
            await service.evaluateRule('rule-1');
            expect(prismaMock.alertRecord.create).not.toHaveBeenCalled();
        });
        it('未指定breakMinutes时默认30分钟', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                type: 'STREAM_BREAK',
                condition: {},
            });
            metricMock.execute.mockResolvedValue({ data: [] });
            prismaMock.alertRecord.create.mockResolvedValue({ id: 'rec-7' });
            prismaMock.alertRule.update.mockResolvedValue({});
            await service.evaluateRule('rule-1');
            const executeCall = metricMock.execute.mock.calls[0][1];
            const cutoffDate = new Date(executeCall.dateRange.start);
            const nowDate = new Date(executeCall.dateRange.end);
            const diffMs = nowDate.getTime() - cutoffDate.getTime();
            expect(diffMs).toBeCloseTo(30 * 60 * 1000, -2);
        });
    });
    describe('evaluateRule - 静默期', () => {
        it('刚触发过的规则在静默期内不重复触发', async () => {
            const fiveMinutesAgo = new Date(Date.now() - 5 * 60 * 1000);
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                lastTriggeredAt: fiveMinutesAgo,
                silenceMinutes: 60,
            });
            await service.evaluateRule('rule-1');
            expect(metricMock.execute).not.toHaveBeenCalled();
            expect(prismaMock.alertRecord.create).not.toHaveBeenCalled();
        });
        it('静默期过后可以再次触发', async () => {
            const twoHoursAgo = new Date(Date.now() - 120 * 60 * 1000);
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                lastTriggeredAt: twoHoursAgo,
                silenceMinutes: 60,
            });
            metricMock.execute.mockResolvedValue({
                data: [{ aggregated_value: 200 }],
            });
            prismaMock.alertRecord.create.mockResolvedValue({ id: 'rec-8' });
            prismaMock.alertRule.update.mockResolvedValue({});
            await service.evaluateRule('rule-1');
            expect(prismaMock.alertRecord.create).toHaveBeenCalled();
        });
        it('lastTriggeredAt为null时不受静默期限制', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                lastTriggeredAt: null,
            });
            metricMock.execute.mockResolvedValue({
                data: [{ aggregated_value: 200 }],
            });
            prismaMock.alertRecord.create.mockResolvedValue({ id: 'rec-9' });
            prismaMock.alertRule.update.mockResolvedValue({});
            await service.evaluateRule('rule-1');
            expect(prismaMock.alertRecord.create).toHaveBeenCalled();
        });
    });
    describe('evaluateRule - 非活跃规则', () => {
        it('isActive=false时不评估', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                isActive: false,
            });
            await service.evaluateRule('rule-1');
            expect(metricMock.execute).not.toHaveBeenCalled();
            expect(metricMock.getComparison).not.toHaveBeenCalled();
        });
        it('规则不存在时不评估', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue(null);
            await service.evaluateRule('rule-1');
            expect(metricMock.execute).not.toHaveBeenCalled();
        });
    });
    describe('evaluateRule - 触发后更新lastTriggeredAt', () => {
        it('触发后调用alertRule.update更新lastTriggeredAt', async () => {
            prismaMock.alertRule.findUnique.mockResolvedValue({
                ...baseRule,
                condition: { upper: 50 },
            });
            metricMock.execute.mockResolvedValue({
                data: [{ aggregated_value: 80 }],
            });
            prismaMock.alertRecord.create.mockResolvedValue({ id: 'rec-10' });
            prismaMock.alertRule.update.mockResolvedValue({});
            await service.evaluateRule('rule-1');
            expect(prismaMock.alertRule.update).toHaveBeenCalledWith(expect.objectContaining({
                where: { id: 'rule-1' },
                data: expect.objectContaining({ lastTriggeredAt: expect.any(Date) }),
            }));
        });
    });
    describe('acknowledgeRecord', () => {
        it('确认后acknowledged=true', async () => {
            prismaMock.alertRecord.findUnique.mockResolvedValue({
                id: 'rec-1',
                acknowledged: false,
            });
            prismaMock.alertRecord.update.mockResolvedValue({
                id: 'rec-1',
                acknowledged: true,
                acknowledgedBy: 'user-1',
                acknowledgedAt: expect.any(Date),
            });
            const result = await service.acknowledgeRecord('rec-1', 'user-1');
            expect(prismaMock.alertRecord.update).toHaveBeenCalledWith({
                where: { id: 'rec-1' },
                data: {
                    acknowledged: true,
                    acknowledgedBy: 'user-1',
                    acknowledgedAt: expect.any(Date),
                },
            });
            expect(result.acknowledged).toBe(true);
        });
        it('记录不存在时抛出NotFoundException', async () => {
            prismaMock.alertRecord.findUnique.mockResolvedValue(null);
            await expect(service.acknowledgeRecord('nonexistent', 'user-1')).rejects.toThrow(common_1.NotFoundException);
        });
    });
});
//# sourceMappingURL=alert.service.spec.js.map