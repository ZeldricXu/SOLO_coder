"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const testing_1 = require("@nestjs/testing");
const config_1 = require("@nestjs/config");
const realtime_service_1 = require("./realtime.service");
const realtime_gateway_1 = require("./realtime.gateway");
jest.mock('ioredis', () => {
    const mockRedis = {
        publish: jest.fn().mockResolvedValue(1),
        subscribe: jest.fn().mockResolvedValue(undefined),
        unsubscribe: jest.fn().mockResolvedValue(undefined),
        quit: jest.fn().mockResolvedValue(undefined),
        on: jest.fn(),
    };
    return {
        __esModule: true,
        default: jest.fn().mockImplementation(() => mockRedis),
    };
});
describe('RealtimeService', () => {
    let service;
    let configService;
    let gateway;
    let mockServer;
    beforeEach(async () => {
        mockServer = {
            to: jest.fn().mockReturnThis(),
            emit: jest.fn(),
        };
        const module = await testing_1.Test.createTestingModule({
            providers: [
                realtime_service_1.RealtimeService,
                {
                    provide: config_1.ConfigService,
                    useValue: {
                        get: jest.fn((key, defaultValue) => {
                            if (key === 'REDIS_HOST')
                                return 'localhost';
                            if (key === 'REDIS_PORT')
                                return 6379;
                            if (key === 'REALTIME_THROTTLE_MS')
                                return 1000;
                            return defaultValue;
                        }),
                    },
                },
                {
                    provide: realtime_gateway_1.RealtimeGateway,
                    useValue: {
                        server: mockServer,
                    },
                },
            ],
        }).compile();
        service = module.get(realtime_service_1.RealtimeService);
        configService = module.get(config_1.ConfigService);
        gateway = module.get(realtime_gateway_1.RealtimeGateway);
        await service.onModuleInit();
    });
    afterEach(() => {
        jest.useRealTimers();
        if (service) {
            const throttleMap = service.throttleMap;
            if (throttleMap) {
                for (const [, entry] of throttleMap) {
                    if (entry.timer)
                        clearTimeout(entry.timer);
                }
                throttleMap.clear();
            }
        }
    });
    describe('pushToRoom - first push', () => {
        it('should emit directly on first push without delay', () => {
            service.broadcastToDashboard('dash-1', 'metric:update', { value: 42 });
            expect(mockServer.to).toHaveBeenCalledWith('dashboard:dash-1');
            expect(mockServer.emit).toHaveBeenCalledWith('metric:update', { value: 42 });
        });
        it('should create a throttle entry on first push', () => {
            service.broadcastToDashboard('dash-1', 'metric:update', { value: 42 });
            const throttleMap = service.throttleMap;
            const key = 'dashboard:dash-1:metric:update';
            expect(throttleMap.has(key)).toBe(true);
            const entry = throttleMap.get(key);
            expect(entry.lastPush).toBeGreaterThan(0);
            expect(entry.queue).toEqual([]);
            expect(entry.timer).toBeNull();
        });
    });
    describe('pushToRoom - throttling', () => {
        it('should queue data when pushed within throttleMs', () => {
            jest.useFakeTimers();
            service.broadcastToDashboard('dash-1', 'metric:update', { value: 1 });
            mockServer.emit.mockClear();
            service.broadcastToDashboard('dash-1', 'metric:update', { value: 2 });
            expect(mockServer.emit).not.toHaveBeenCalled();
        });
        it('should set a timer for queued data', () => {
            jest.useFakeTimers();
            service.broadcastToDashboard('dash-1', 'metric:update', { value: 1 });
            service.broadcastToDashboard('dash-1', 'metric:update', { value: 2 });
            const throttleMap = service.throttleMap;
            const key = 'dashboard:dash-1:metric:update';
            const entry = throttleMap.get(key);
            expect(entry.timer).not.toBeNull();
            expect(entry.queue).toHaveLength(1);
        });
        it('should emit merged data after throttle delay', () => {
            jest.useFakeTimers();
            service.broadcastToDashboard('dash-1', 'metric:update', { value: 1 });
            service.broadcastToDashboard('dash-1', 'metric:update', { value: 2 });
            service.broadcastToDashboard('dash-1', 'metric:update', { value: 3 });
            mockServer.emit.mockClear();
            jest.advanceTimersByTime(1000);
            expect(mockServer.emit).toHaveBeenCalledTimes(1);
            const emittedData = mockServer.emit.mock.calls[0][1];
            expect(emittedData).toEqual(expect.objectContaining({
                _merged: true,
                _mergedCount: 2,
                value: 3,
            }));
        });
        it('should emit directly when throttleMs has elapsed', () => {
            jest.useFakeTimers();
            service.broadcastToDashboard('dash-1', 'metric:update', { value: 1 });
            jest.advanceTimersByTime(1001);
            mockServer.emit.mockClear();
            service.broadcastToDashboard('dash-1', 'metric:update', { value: 2 });
            expect(mockServer.emit).toHaveBeenCalledWith('metric:update', { value: 2 });
        });
    });
    describe('mergeUpdates', () => {
        it('should return null for empty array', () => {
            const result = service.mergeUpdates([]);
            expect(result).toBeNull();
        });
        it('should return the single update for array with one element', () => {
            const update = { value: 42 };
            const result = service.mergeUpdates([update]);
            expect(result).toEqual({ value: 42 });
        });
        it('should return the latest update with _merged markers for multiple updates', () => {
            const updates = [
                { value: 1, ts: 'a' },
                { value: 2, ts: 'b' },
                { value: 3, ts: 'c' },
            ];
            const result = service.mergeUpdates(updates);
            expect(result).toEqual({
                value: 3,
                ts: 'c',
                _merged: true,
                _mergedCount: 3,
            });
        });
        it('should handle non-object latest values', () => {
            const result = service.mergeUpdates([null, 'hello']);
            expect(result).toBe('hello');
        });
    });
    describe('backpressure - queue overflow (TODO)', () => {
        it('should accumulate all queued items without limit (no MAX_QUEUE_SIZE enforcement)', () => {
            jest.useFakeTimers();
            service.broadcastToDashboard('dash-1', 'metric:update', { value: 0 });
            for (let i = 1; i <= 200; i++) {
                service.broadcastToDashboard('dash-1', 'metric:update', { value: i });
            }
            const throttleMap = service.throttleMap;
            const key = 'dashboard:dash-1:metric:update';
            const entry = throttleMap.get(key);
            expect(entry.queue.length).toBe(200);
            mockServer.emit.mockClear();
            jest.advanceTimersByTime(1000);
            const emittedData = mockServer.emit.mock.calls[0][1];
            expect(emittedData._mergedCount).toBe(200);
            expect(emittedData._merged).toBe(true);
        });
        it.todo('should enforce MAX_QUEUE_SIZE limit and discard oldest data when queue exceeds threshold - current implementation lacks this protection');
    });
    describe('broadcastToBusinessLine', () => {
        it('should push to business line room', () => {
            service.broadcastToBusinessLine('bl-1', 'alert:trigger', { alert: 'high' });
            expect(mockServer.to).toHaveBeenCalledWith('businessLine:bl-1');
            expect(mockServer.emit).toHaveBeenCalledWith('alert:trigger', { alert: 'high' });
        });
    });
    describe('Redis publisher integration', () => {
        it('onMetricUpdate should publish to metric:update channel', async () => {
            const publisher = service.publisher;
            await service.onMetricUpdate('metric-1', { value: 99 });
            expect(publisher.publish).toHaveBeenCalledWith('metric:update', JSON.stringify({ metricId: 'metric-1', value: 99 }));
        });
        it('onAlertTrigger should publish to alert:trigger channel', async () => {
            const publisher = service.publisher;
            await service.onAlertTrigger({
                businessLineId: 'bl-1',
                alertType: 'threshold',
            });
            expect(publisher.publish).toHaveBeenCalledWith('alert:trigger', JSON.stringify({ businessLineId: 'bl-1', alertType: 'threshold' }));
        });
        it('onDataChange should publish to data:change channel', async () => {
            const publisher = service.publisher;
            await service.onDataChange('bl-1', { table: 'sales' });
            expect(publisher.publish).toHaveBeenCalledWith('data:change', JSON.stringify({ businessLineId: 'bl-1', table: 'sales' }));
        });
    });
    describe('onModuleDestroy', () => {
        it('should clear all throttle timers and close Redis connections', async () => {
            jest.useFakeTimers();
            service.broadcastToDashboard('dash-1', 'event', {});
            service.broadcastToDashboard('dash-2', 'event', {});
            const subscriber = service.subscriber;
            const publisher = service.publisher;
            await service.onModuleDestroy();
            const throttleMap = service.throttleMap;
            expect(throttleMap.size).toBe(0);
            expect(subscriber.unsubscribe).toHaveBeenCalled();
            expect(subscriber.quit).toHaveBeenCalled();
            expect(publisher.quit).toHaveBeenCalled();
        });
    });
});
//# sourceMappingURL=realtime.service.spec.js.map