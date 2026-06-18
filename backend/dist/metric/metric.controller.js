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
exports.MetricController = void 0;
const common_1 = require("@nestjs/common");
const metric_service_1 = require("./metric.service");
const create_metric_dto_1 = require("./dto/create-metric.dto");
const update_metric_dto_1 = require("./dto/update-metric.dto");
const execute_metric_dto_1 = require("./dto/execute-metric.dto");
const comparison_dto_1 = require("./dto/comparison.dto");
let MetricController = class MetricController {
    constructor(metricService) {
        this.metricService = metricService;
    }
    create(dto) {
        return this.metricService.create(dto);
    }
    getTemplates(category) {
        return this.metricService.getTemplates(category);
    }
    findAll(businessLineId) {
        return this.metricService.findAll(businessLineId);
    }
    findOne(id) {
        return this.metricService.findOne(id);
    }
    update(id, dto) {
        return this.metricService.update(id, dto);
    }
    remove(id) {
        return this.metricService.remove(id);
    }
    execute(id, dto) {
        return this.metricService.execute(id, dto);
    }
    getComparison(id, dto) {
        return this.metricService.getComparison(id, dto);
    }
};
exports.MetricController = MetricController;
__decorate([
    (0, common_1.Post)(),
    __param(0, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [create_metric_dto_1.CreateMetricDto]),
    __metadata("design:returntype", void 0)
], MetricController.prototype, "create", null);
__decorate([
    (0, common_1.Get)('templates'),
    __param(0, (0, common_1.Query)('category')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], MetricController.prototype, "getTemplates", null);
__decorate([
    (0, common_1.Get)(),
    __param(0, (0, common_1.Query)('businessLineId')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], MetricController.prototype, "findAll", null);
__decorate([
    (0, common_1.Get)(':id'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], MetricController.prototype, "findOne", null);
__decorate([
    (0, common_1.Put)(':id'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, update_metric_dto_1.UpdateMetricDto]),
    __metadata("design:returntype", void 0)
], MetricController.prototype, "update", null);
__decorate([
    (0, common_1.Delete)(':id'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], MetricController.prototype, "remove", null);
__decorate([
    (0, common_1.Post)(':id/execute'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, execute_metric_dto_1.ExecuteMetricDto]),
    __metadata("design:returntype", void 0)
], MetricController.prototype, "execute", null);
__decorate([
    (0, common_1.Get)(':id/comparison'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Query)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, comparison_dto_1.ComparisonDto]),
    __metadata("design:returntype", void 0)
], MetricController.prototype, "getComparison", null);
exports.MetricController = MetricController = __decorate([
    (0, common_1.Controller)('metrics'),
    __metadata("design:paramtypes", [metric_service_1.MetricService])
], MetricController);
//# sourceMappingURL=metric.controller.js.map