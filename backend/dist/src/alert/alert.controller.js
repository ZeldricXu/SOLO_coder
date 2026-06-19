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
Object.defineProperty(exports, "__esModule", { value: true });
exports.AlertController = void 0;
const common_1 = require("@nestjs/common");
const alert_service_1 = require("./alert.service");
const create_alert_rule_dto_1 = require("./dto/create-alert-rule.dto");
const update_alert_rule_dto_1 = require("./dto/update-alert-rule.dto");
const acknowledge_dto_1 = require("./dto/acknowledge.dto");
let AlertController = class AlertController {
    constructor(alertService) {
        this.alertService = alertService;
    }
    createRule(dto) {
        return this.alertService.create(dto);
    }
    findRules(metricId, businessLineId) {
        return this.alertService.findAll(metricId, businessLineId);
    }
    findOneRule(id) {
        return this.alertService.findOne(id);
    }
    updateRule(id, dto) {
        return this.alertService.update(id, dto);
    }
    removeRule(id) {
        return this.alertService.remove(id);
    }
    toggleRule(id) {
        return this.alertService.toggle(id);
    }
    findRecords(ruleId, acknowledged) {
        const ack = acknowledged !== undefined ? acknowledged === 'true' : undefined;
        return this.alertService.findRecords(ruleId, ack);
    }
    acknowledgeRecord(id, dto) {
        return this.alertService.acknowledgeRecord(id, dto.acknowledgedBy);
    }
    getHistory(id) {
        return this.alertService.getHistory(id);
    }
};
exports.AlertController = AlertController;
__decorate([
    (0, common_1.Post)('rules'),
    __param(0, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [create_alert_rule_dto_1.CreateAlertRuleDto]),
    __metadata("design:returntype", void 0)
], AlertController.prototype, "createRule", null);
__decorate([
    (0, common_1.Get)('rules'),
    __param(0, (0, common_1.Query)('metricId')),
    __param(1, (0, common_1.Query)('businessLineId')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String]),
    __metadata("design:returntype", void 0)
], AlertController.prototype, "findRules", null);
__decorate([
    (0, common_1.Get)('rules/:id'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], AlertController.prototype, "findOneRule", null);
__decorate([
    (0, common_1.Put)('rules/:id'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, update_alert_rule_dto_1.UpdateAlertRuleDto]),
    __metadata("design:returntype", void 0)
], AlertController.prototype, "updateRule", null);
__decorate([
    (0, common_1.Delete)('rules/:id'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], AlertController.prototype, "removeRule", null);
__decorate([
    (0, common_1.Patch)('rules/:id/toggle'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], AlertController.prototype, "toggleRule", null);
__decorate([
    (0, common_1.Get)('records'),
    __param(0, (0, common_1.Query)('ruleId')),
    __param(1, (0, common_1.Query)('acknowledged')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String]),
    __metadata("design:returntype", void 0)
], AlertController.prototype, "findRecords", null);
__decorate([
    (0, common_1.Patch)('records/:id/acknowledge'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, acknowledge_dto_1.AcknowledgeDto]),
    __metadata("design:returntype", void 0)
], AlertController.prototype, "acknowledgeRecord", null);
__decorate([
    (0, common_1.Get)('rules/:id/history'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], AlertController.prototype, "getHistory", null);
exports.AlertController = AlertController = __decorate([
    (0, common_1.Controller)('alerts'),
    __metadata("design:paramtypes", [alert_service_1.AlertService])
], AlertController);
//# sourceMappingURL=alert.controller.js.map