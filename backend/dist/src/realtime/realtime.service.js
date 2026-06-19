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
var __param = (this && this.__param) || function (paramIndex, decorator) {
    return function (target, key) { decorator(target, key, paramIndex); }
};
var RealtimeService_1;
Object.defineProperty(exports, "__esModule", { value: true });
exports.RealtimeService = void 0;
const common_1 = require("@nestjs/common");
const config_1 = require("@nestjs/config");
const ioredis_1 = require("ioredis");
const realtime_gateway_1 = require("./realtime.gateway");
const DEFAULT_THROTTLE_MS = 1000;
const REDIS_CHANNELS = ['metric:update', 'alert:trigger', 'data:change'];
let RealtimeService = RealtimeService_1 = class RealtimeService {
    constructor(configService, gateway) {
        this.configService = configService;
        this.gateway = gateway;
        this.logger = new common_1.Logger(RealtimeService_1.name);
        this.throttleMap = new Map();
        const host = this.configService.get('REDIS_HOST', 'localhost');
        const port = this.configService.get('REDIS_PORT', 6379);
        this.publisher = new ioredis_1.default({ host, port });
        this.subscriber = new ioredis_1.default({ host, port });
        this.throttleMs = this.configService.get('REALTIME_THROTTLE_MS', DEFAULT_THROTTLE_MS);
    }
    async onModuleInit() {
        await this.subscriber.subscribe(...REDIS_CHANNELS);
        this.subscriber.on('message', (channel, message) => {
            try {
                const data = JSON.parse(message);
                switch (channel) {
                    case 'metric:update':
                        this.pushToRoom(`metric:${data.metricId}`, 'metric:update', data);
                        break;
                    case 'alert:trigger':
                        this.pushToRoom(`businessLine:${data.businessLineId}`, 'alert:trigger', data);
                        break;
                    case 'data:change':
                        this.pushToRoom(`businessLine:${data.businessLineId}`, 'data:change', data);
                        break;
                }
            }
            catch (error) {
                this.logger.error(`Failed to process Redis message on ${channel}: ${error}`);
            }
        });
        this.logger.log('RealtimeService initialized with Redis subscriber');
    }
    async onModuleDestroy() {
        for (const [, entry] of this.throttleMap) {
            if (entry.timer) {
                clearTimeout(entry.timer);
            }
        }
        this.throttleMap.clear();
        await this.subscriber.unsubscribe(...REDIS_CHANNELS);
        await this.subscriber.quit();
        await this.publisher.quit();
        this.logger.log('RealtimeService destroyed');
    }
    getServer() {
        return this.gateway.server;
    }
    pushToRoom(room, event, data) {
        const key = `${room}:${event}`;
        const now = Date.now();
        const entry = this.throttleMap.get(key);
        if (!entry) {
            this.throttleMap.set(key, {
                lastPush: now,
                queue: [],
                timer: null,
            });
            this.getServer().to(room).emit(event, data);
            return;
        }
        if (now - entry.lastPush >= this.throttleMs) {
            entry.lastPush = now;
            entry.queue = [];
            if (entry.timer) {
                clearTimeout(entry.timer);
                entry.timer = null;
            }
            this.getServer().to(room).emit(event, data);
        }
        else {
            entry.queue.push(data);
            if (!entry.timer) {
                const delay = this.throttleMs - (now - entry.lastPush);
                entry.timer = setTimeout(() => {
                    const queued = entry.queue;
                    entry.queue = [];
                    entry.timer = null;
                    entry.lastPush = Date.now();
                    const merged = this.mergeUpdates(queued);
                    this.getServer().to(room).emit(event, merged);
                }, delay);
            }
        }
    }
    mergeUpdates(updates) {
        if (updates.length === 0)
            return null;
        if (updates.length === 1)
            return updates[0];
        const latest = updates[updates.length - 1];
        if (typeof latest === 'object' && latest !== null) {
            return {
                ...latest,
                _merged: true,
                _mergedCount: updates.length,
            };
        }
        return latest;
    }
    async onMetricUpdate(metricId, data) {
        const payload = { metricId, ...data };
        await this.publisher.publish('metric:update', JSON.stringify(payload));
    }
    async onAlertTrigger(alertData) {
        await this.publisher.publish('alert:trigger', JSON.stringify(alertData));
    }
    async onDataChange(businessLineId, data) {
        const payload = { businessLineId, ...data };
        await this.publisher.publish('data:change', JSON.stringify(payload));
    }
    broadcastToDashboard(dashboardId, event, data) {
        this.pushToRoom(`dashboard:${dashboardId}`, event, data);
    }
    broadcastToBusinessLine(businessLineId, event, data) {
        this.pushToRoom(`businessLine:${businessLineId}`, event, data);
    }
};
exports.RealtimeService = RealtimeService;
exports.RealtimeService = RealtimeService = RealtimeService_1 = __decorate([
    (0, common_1.Injectable)(),
    __param(1, (0, common_1.Inject)((0, common_1.forwardRef)(() => realtime_gateway_1.RealtimeGateway))),
    __metadata("design:paramtypes", [config_1.ConfigService,
        realtime_gateway_1.RealtimeGateway])
], RealtimeService);
//# sourceMappingURL=realtime.service.js.map