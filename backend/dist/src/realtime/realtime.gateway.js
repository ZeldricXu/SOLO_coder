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
var RealtimeGateway_1;
Object.defineProperty(exports, "__esModule", { value: true });
exports.RealtimeGateway = void 0;
const websockets_1 = require("@nestjs/websockets");
const socket_io_1 = require("socket.io");
const jwt_1 = require("@nestjs/jwt");
const common_1 = require("@nestjs/common");
const realtime_service_1 = require("./realtime.service");
let RealtimeGateway = RealtimeGateway_1 = class RealtimeGateway {
    constructor(jwtService, realtimeService) {
        this.jwtService = jwtService;
        this.realtimeService = realtimeService;
        this.logger = new common_1.Logger(RealtimeGateway_1.name);
    }
    async handleConnection(client) {
        const token = client.handshake.auth?.token;
        if (!token) {
            this.logger.warn(`Client ${client.id} disconnected: no token provided`);
            client.disconnect(true);
            return;
        }
        try {
            const payload = await this.jwtService.verifyAsync(token);
            client.data.user = payload;
            if (payload.businessLineId) {
                client.join(`businessLine:${payload.businessLineId}`);
            }
            if (payload.dashboardId) {
                client.join(`dashboard:${payload.dashboardId}`);
            }
            this.logger.log(`Client ${client.id} connected as user ${payload.sub || payload.email}`);
        }
        catch {
            this.logger.warn(`Client ${client.id} disconnected: invalid token`);
            client.disconnect(true);
        }
    }
    handleDisconnect(client) {
        this.logger.log(`Client ${client.id} disconnected`);
    }
    handleSubscribeDashboard(client, payload) {
        client.join(`dashboard:${payload.dashboardId}`);
        return { event: 'subscribe:dashboard', data: { joined: payload.dashboardId } };
    }
    handleUnsubscribeDashboard(client, payload) {
        client.leave(`dashboard:${payload.dashboardId}`);
        return { event: 'unsubscribe:dashboard', data: { left: payload.dashboardId } };
    }
    handleSubscribeMetric(client, payload) {
        client.join(`metric:${payload.metricId}`);
        return { event: 'subscribe:metric', data: { joined: payload.metricId } };
    }
    handleUnsubscribeMetric(client, payload) {
        client.leave(`metric:${payload.metricId}`);
        return { event: 'unsubscribe:metric', data: { left: payload.metricId } };
    }
    handleFilterUpdate(client, payload) {
        this.realtimeService.broadcastToDashboard(payload.dashboardId, 'filter:update', {
            widgetId: payload.widgetId,
            filters: payload.filters,
            linkedWidgetIds: payload.linkedWidgetIds,
            updatedBy: client.data.user?.sub || client.data.user?.email,
        });
    }
};
exports.RealtimeGateway = RealtimeGateway;
__decorate([
    (0, websockets_1.WebSocketServer)(),
    __metadata("design:type", socket_io_1.Server)
], RealtimeGateway.prototype, "server", void 0);
__decorate([
    (0, websockets_1.SubscribeMessage)('subscribe:dashboard'),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [socket_io_1.Socket, Object]),
    __metadata("design:returntype", void 0)
], RealtimeGateway.prototype, "handleSubscribeDashboard", null);
__decorate([
    (0, websockets_1.SubscribeMessage)('unsubscribe:dashboard'),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [socket_io_1.Socket, Object]),
    __metadata("design:returntype", void 0)
], RealtimeGateway.prototype, "handleUnsubscribeDashboard", null);
__decorate([
    (0, websockets_1.SubscribeMessage)('subscribe:metric'),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [socket_io_1.Socket, Object]),
    __metadata("design:returntype", void 0)
], RealtimeGateway.prototype, "handleSubscribeMetric", null);
__decorate([
    (0, websockets_1.SubscribeMessage)('unsubscribe:metric'),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [socket_io_1.Socket, Object]),
    __metadata("design:returntype", void 0)
], RealtimeGateway.prototype, "handleUnsubscribeMetric", null);
__decorate([
    (0, websockets_1.SubscribeMessage)('filter:update'),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [socket_io_1.Socket, Object]),
    __metadata("design:returntype", void 0)
], RealtimeGateway.prototype, "handleFilterUpdate", null);
exports.RealtimeGateway = RealtimeGateway = RealtimeGateway_1 = __decorate([
    (0, websockets_1.WebSocketGateway)({
        cors: { origin: '*' },
        transports: ['websocket', 'polling'],
    }),
    __param(1, (0, common_1.Inject)((0, common_1.forwardRef)(() => realtime_service_1.RealtimeService))),
    __metadata("design:paramtypes", [jwt_1.JwtService,
        realtime_service_1.RealtimeService])
], RealtimeGateway);
//# sourceMappingURL=realtime.gateway.js.map