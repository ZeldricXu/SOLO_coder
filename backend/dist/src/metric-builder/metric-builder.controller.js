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
exports.MetricBuilderController = void 0;
const common_1 = require("@nestjs/common");
const metric_builder_service_1 = require("./metric-builder.service");
const build_metric_dto_1 = require("./dto/build-metric.dto");
const current_user_decorator_1 = require("../common/decorators/current-user.decorator");
let MetricBuilderController = class MetricBuilderController {
    constructor(metricBuilderService) {
        this.metricBuilderService = metricBuilderService;
    }
    listTables(dataSourceId) {
        return this.metricBuilderService.listTables(dataSourceId);
    }
    listColumns(dataSourceId, tableName) {
        return this.metricBuilderService.listColumns(dataSourceId, tableName);
    }
    async generateSql(dataSourceId, config) {
        const sql = await this.metricBuilderService.generateSql(dataSourceId, config);
        return { sql };
    }
    preview(dataSourceId, config) {
        return this.metricBuilderService.buildMetric(dataSourceId, config);
    }
    createMetric(userId, dataSourceId, businessLineId, config) {
        return this.metricBuilderService.createMetricFromVisual(userId, businessLineId, dataSourceId, config);
    }
};
exports.MetricBuilderController = MetricBuilderController;
__decorate([
    (0, common_1.Get)(':dataSourceId/tables'),
    __param(0, (0, common_1.Param)('dataSourceId')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], MetricBuilderController.prototype, "listTables", null);
__decorate([
    (0, common_1.Get)(':dataSourceId/columns'),
    __param(0, (0, common_1.Param)('dataSourceId')),
    __param(1, (0, common_1.Query)('tableName')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String]),
    __metadata("design:returntype", void 0)
], MetricBuilderController.prototype, "listColumns", null);
__decorate([
    (0, common_1.Post)(':dataSourceId/generate-sql'),
    __param(0, (0, common_1.Param)('dataSourceId')),
    __param(1, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, build_metric_dto_1.GenerateSqlDto]),
    __metadata("design:returntype", Promise)
], MetricBuilderController.prototype, "generateSql", null);
__decorate([
    (0, common_1.Post)(':dataSourceId/preview'),
    __param(0, (0, common_1.Param)('dataSourceId')),
    __param(1, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, build_metric_dto_1.BuildMetricDto]),
    __metadata("design:returntype", void 0)
], MetricBuilderController.prototype, "preview", null);
__decorate([
    (0, common_1.Post)(':dataSourceId/create'),
    __param(0, (0, current_user_decorator_1.CurrentUser)('id')),
    __param(1, (0, common_1.Param)('dataSourceId')),
    __param(2, (0, common_1.Query)('businessLineId')),
    __param(3, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String, String, build_metric_dto_1.CreateMetricFromVisualDto]),
    __metadata("design:returntype", void 0)
], MetricBuilderController.prototype, "createMetric", null);
exports.MetricBuilderController = MetricBuilderController = __decorate([
    (0, common_1.Controller)('metric-builder'),
    __metadata("design:paramtypes", [metric_builder_service_1.MetricBuilderService])
], MetricBuilderController);
//# sourceMappingURL=metric-builder.controller.js.map