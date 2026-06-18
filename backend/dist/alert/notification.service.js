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
var NotificationService_1;
Object.defineProperty(exports, "__esModule", { value: true });
exports.NotificationService = void 0;
const common_1 = require("@nestjs/common");
const prisma_service_1 = require("../prisma/prisma.service");
const notifier_factory_1 = require("./notifiers/notifier-factory");
let NotificationService = NotificationService_1 = class NotificationService {
    constructor(prisma) {
        this.prisma = prisma;
        this.logger = new common_1.Logger(NotificationService_1.name);
    }
    async sendNotifications(ruleId, channels, escalationMinutes, escalationChannels, message) {
        const results = await Promise.allSettled(channels.map((ch) => {
            const notifier = notifier_factory_1.NotifierFactory.create(ch.type, ch.target);
            return notifier.send(message);
        }));
        const failures = results.filter((r) => r.status === 'rejected');
        if (failures.length > 0) {
            this.logger.warn(`${failures.length}/${channels.length} notification(s) failed for rule ${ruleId}`);
        }
        await this.prisma.alertRecord.updateMany({
            where: { ruleId, notified: false },
            data: { notified: true, notifiedAt: new Date() },
        });
        if (escalationMinutes > 0 && escalationChannels && escalationChannels.length > 0) {
            setTimeout(() => this.handleEscalation(ruleId, escalationChannels, message), escalationMinutes * 60 * 1000);
        }
    }
    async handleEscalation(ruleId, escalationChannels, message) {
        const unacknowledged = await this.prisma.alertRecord.findFirst({
            where: { ruleId, acknowledged: false },
            orderBy: { createdAt: 'desc' },
        });
        if (!unacknowledged) {
            return;
        }
        this.logger.warn(`Escalating alert for rule ${ruleId}`);
        const escalationMessage = {
            ...message,
            ruleName: `[ESCALATION] ${message.ruleName}`,
        };
        await Promise.allSettled(escalationChannels.map((ch) => {
            const notifier = notifier_factory_1.NotifierFactory.create(ch.type, ch.target);
            return notifier.send(escalationMessage);
        }));
    }
};
exports.NotificationService = NotificationService;
exports.NotificationService = NotificationService = NotificationService_1 = __decorate([
    (0, common_1.Injectable)(),
    __metadata("design:paramtypes", [prisma_service_1.PrismaService])
], NotificationService);
//# sourceMappingURL=notification.service.js.map