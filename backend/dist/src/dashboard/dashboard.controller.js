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
exports.DashboardController = void 0;
const common_1 = require("@nestjs/common");
const dashboard_service_1 = require("./dashboard.service");
const create_dashboard_dto_1 = require("./dto/create-dashboard.dto");
const update_dashboard_dto_1 = require("./dto/update-dashboard.dto");
const create_widget_dto_1 = require("./dto/create-widget.dto");
const update_widget_dto_1 = require("./dto/update-widget.dto");
const batch_layout_dto_1 = require("./dto/batch-layout.dto");
const link_widget_dto_1 = require("./dto/link-widget.dto");
const import_dashboard_dto_1 = require("./dto/import-dashboard.dto");
const current_user_decorator_1 = require("../common/decorators/current-user.decorator");
let DashboardController = class DashboardController {
    constructor(dashboardService) {
        this.dashboardService = dashboardService;
    }
    create(dto, user) {
        return this.dashboardService.create(dto, user?.id);
    }
    findAll(businessLineId) {
        return this.dashboardService.findAll(businessLineId);
    }
    findOne(id) {
        return this.dashboardService.findOne(id);
    }
    update(id, dto) {
        return this.dashboardService.update(id, dto);
    }
    remove(id) {
        return this.dashboardService.remove(id);
    }
    addWidget(id, dto) {
        return this.dashboardService.addWidget(id, dto);
    }
    updateWidget(id, widgetId, dto) {
        return this.dashboardService.updateWidget(id, widgetId, dto);
    }
    removeWidget(id, widgetId) {
        return this.dashboardService.removeWidget(id, widgetId);
    }
    batchUpdateLayout(id, dto) {
        return this.dashboardService.batchUpdateLayout(id, dto.items);
    }
    linkWidget(id, widgetId, dto) {
        return this.dashboardService.linkWidget(id, widgetId, dto.targetWidgetId);
    }
    unlinkWidget(id, widgetId, targetWidgetId) {
        return this.dashboardService.unlinkWidget(id, widgetId, targetWidgetId);
    }
    exportDashboard(id) {
        return this.dashboardService.exportDashboard(id);
    }
    importDashboard(dto, user) {
        return this.dashboardService.importDashboard(dto.data, user?.id, dto.data?.businessLineId);
    }
};
exports.DashboardController = DashboardController;
__decorate([
    (0, common_1.Post)(),
    __param(0, (0, common_1.Body)()),
    __param(1, (0, current_user_decorator_1.CurrentUser)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [create_dashboard_dto_1.CreateDashboardDto, Object]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "create", null);
__decorate([
    (0, common_1.Get)(),
    __param(0, (0, common_1.Query)('businessLineId')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "findAll", null);
__decorate([
    (0, common_1.Get)(':id'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "findOne", null);
__decorate([
    (0, common_1.Put)(':id'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, update_dashboard_dto_1.UpdateDashboardDto]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "update", null);
__decorate([
    (0, common_1.Delete)(':id'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "remove", null);
__decorate([
    (0, common_1.Post)(':id/widgets'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, create_widget_dto_1.CreateWidgetDto]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "addWidget", null);
__decorate([
    (0, common_1.Put)(':id/widgets/:widgetId'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Param)('widgetId')),
    __param(2, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String, update_widget_dto_1.UpdateWidgetDto]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "updateWidget", null);
__decorate([
    (0, common_1.Delete)(':id/widgets/:widgetId'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Param)('widgetId')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "removeWidget", null);
__decorate([
    (0, common_1.Put)(':id/layout'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, batch_layout_dto_1.BatchLayoutDto]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "batchUpdateLayout", null);
__decorate([
    (0, common_1.Post)(':id/widgets/:widgetId/link'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Param)('widgetId')),
    __param(2, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String, link_widget_dto_1.LinkWidgetDto]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "linkWidget", null);
__decorate([
    (0, common_1.Delete)(':id/widgets/:widgetId/link/:targetWidgetId'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Param)('widgetId')),
    __param(2, (0, common_1.Param)('targetWidgetId')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, String, String]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "unlinkWidget", null);
__decorate([
    (0, common_1.Get)(':id/export'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "exportDashboard", null);
__decorate([
    (0, common_1.Post)('import'),
    __param(0, (0, common_1.Body)()),
    __param(1, (0, current_user_decorator_1.CurrentUser)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [import_dashboard_dto_1.ImportDashboardDto, Object]),
    __metadata("design:returntype", void 0)
], DashboardController.prototype, "importDashboard", null);
exports.DashboardController = DashboardController = __decorate([
    (0, common_1.Controller)('dashboards'),
    __metadata("design:paramtypes", [dashboard_service_1.DashboardService])
], DashboardController);
//# sourceMappingURL=dashboard.controller.js.map