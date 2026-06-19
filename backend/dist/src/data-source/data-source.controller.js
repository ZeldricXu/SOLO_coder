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
exports.DataSourceController = void 0;
const common_1 = require("@nestjs/common");
const data_source_service_1 = require("./data-source.service");
const create_data_source_dto_1 = require("./dto/create-data-source.dto");
const update_data_source_dto_1 = require("./dto/update-data-source.dto");
const query_dto_1 = require("./dto/query.dto");
let DataSourceController = class DataSourceController {
    constructor(dataSourceService) {
        this.dataSourceService = dataSourceService;
    }
    create(dto) {
        return this.dataSourceService.create(dto);
    }
    findAll(businessLineId) {
        return this.dataSourceService.findAll(businessLineId);
    }
    findOne(id) {
        return this.dataSourceService.findOne(id);
    }
    update(id, dto) {
        return this.dataSourceService.update(id, dto);
    }
    remove(id) {
        return this.dataSourceService.remove(id);
    }
    testConnection(id) {
        return this.dataSourceService.testConnection(id);
    }
    executeQuery(id, dto) {
        return this.dataSourceService.executeQuery(id, dto);
    }
    inferSchema(id) {
        return this.dataSourceService.inferSchema(id);
    }
};
exports.DataSourceController = DataSourceController;
__decorate([
    (0, common_1.Post)(),
    __param(0, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [create_data_source_dto_1.CreateDataSourceDto]),
    __metadata("design:returntype", void 0)
], DataSourceController.prototype, "create", null);
__decorate([
    (0, common_1.Get)(),
    __param(0, (0, common_1.Query)('businessLineId')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], DataSourceController.prototype, "findAll", null);
__decorate([
    (0, common_1.Get)(':id'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], DataSourceController.prototype, "findOne", null);
__decorate([
    (0, common_1.Put)(':id'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, update_data_source_dto_1.UpdateDataSourceDto]),
    __metadata("design:returntype", void 0)
], DataSourceController.prototype, "update", null);
__decorate([
    (0, common_1.Delete)(':id'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], DataSourceController.prototype, "remove", null);
__decorate([
    (0, common_1.Post)(':id/test'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], DataSourceController.prototype, "testConnection", null);
__decorate([
    (0, common_1.Post)(':id/query'),
    __param(0, (0, common_1.Param)('id')),
    __param(1, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String, query_dto_1.QueryDto]),
    __metadata("design:returntype", void 0)
], DataSourceController.prototype, "executeQuery", null);
__decorate([
    (0, common_1.Get)(':id/schema'),
    __param(0, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [String]),
    __metadata("design:returntype", void 0)
], DataSourceController.prototype, "inferSchema", null);
exports.DataSourceController = DataSourceController = __decorate([
    (0, common_1.Controller)('data-sources'),
    __metadata("design:paramtypes", [data_source_service_1.DataSourceService])
], DataSourceController);
//# sourceMappingURL=data-source.controller.js.map