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
Object.defineProperty(exports, "__esModule", { value: true });
exports.CreateMetricFromVisualDto = exports.BuildMetricDto = exports.GenerateSqlDto = exports.ListColumnsParams = exports.ListTablesParams = exports.VisualMetricConfig = exports.FilterCondition = void 0;
const class_validator_1 = require("class-validator");
const class_transformer_1 = require("class-transformer");
class FilterCondition {
}
exports.FilterCondition = FilterCondition;
__decorate([
    (0, class_validator_1.IsString)(),
    (0, class_validator_1.IsNotEmpty)(),
    __metadata("design:type", String)
], FilterCondition.prototype, "field", void 0);
__decorate([
    (0, class_validator_1.IsEnum)(['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'in', 'like', 'between']),
    __metadata("design:type", String)
], FilterCondition.prototype, "operator", void 0);
class VisualMetricConfig {
}
exports.VisualMetricConfig = VisualMetricConfig;
__decorate([
    (0, class_validator_1.IsString)(),
    (0, class_validator_1.IsNotEmpty)(),
    __metadata("design:type", String)
], VisualMetricConfig.prototype, "table", void 0);
__decorate([
    (0, class_validator_1.IsString)(),
    (0, class_validator_1.IsNotEmpty)(),
    __metadata("design:type", String)
], VisualMetricConfig.prototype, "metricField", void 0);
__decorate([
    (0, class_validator_1.IsEnum)(['SUM', 'COUNT', 'AVG', 'MAX', 'MIN', 'DISTINCT_COUNT']),
    __metadata("design:type", String)
], VisualMetricConfig.prototype, "aggregation", void 0);
__decorate([
    (0, class_validator_1.IsOptional)(),
    (0, class_validator_1.IsString)(),
    __metadata("design:type", String)
], VisualMetricConfig.prototype, "alias", void 0);
__decorate([
    (0, class_validator_1.IsOptional)(),
    (0, class_validator_1.IsString)(),
    __metadata("design:type", String)
], VisualMetricConfig.prototype, "timeField", void 0);
__decorate([
    (0, class_validator_1.IsOptional)(),
    (0, class_validator_1.IsEnum)(['HOUR', 'DAY', 'WEEK', 'MONTH']),
    __metadata("design:type", String)
], VisualMetricConfig.prototype, "granularity", void 0);
__decorate([
    (0, class_validator_1.IsOptional)(),
    (0, class_validator_1.IsString)(),
    __metadata("design:type", String)
], VisualMetricConfig.prototype, "startDate", void 0);
__decorate([
    (0, class_validator_1.IsOptional)(),
    (0, class_validator_1.IsString)(),
    __metadata("design:type", String)
], VisualMetricConfig.prototype, "endDate", void 0);
__decorate([
    (0, class_validator_1.IsOptional)(),
    (0, class_validator_1.IsArray)(),
    (0, class_validator_1.IsString)({ each: true }),
    __metadata("design:type", Array)
], VisualMetricConfig.prototype, "dimensions", void 0);
__decorate([
    (0, class_validator_1.IsOptional)(),
    (0, class_validator_1.IsArray)(),
    (0, class_validator_1.ValidateNested)({ each: true }),
    (0, class_transformer_1.Type)(() => FilterCondition),
    __metadata("design:type", Array)
], VisualMetricConfig.prototype, "filters", void 0);
class ListTablesParams {
}
exports.ListTablesParams = ListTablesParams;
__decorate([
    (0, class_validator_1.IsString)(),
    (0, class_validator_1.IsNotEmpty)(),
    __metadata("design:type", String)
], ListTablesParams.prototype, "dataSourceId", void 0);
class ListColumnsParams {
}
exports.ListColumnsParams = ListColumnsParams;
__decorate([
    (0, class_validator_1.IsString)(),
    (0, class_validator_1.IsNotEmpty)(),
    __metadata("design:type", String)
], ListColumnsParams.prototype, "dataSourceId", void 0);
__decorate([
    (0, class_validator_1.IsString)(),
    (0, class_validator_1.IsNotEmpty)(),
    __metadata("design:type", String)
], ListColumnsParams.prototype, "tableName", void 0);
class GenerateSqlDto extends VisualMetricConfig {
}
exports.GenerateSqlDto = GenerateSqlDto;
class BuildMetricDto extends VisualMetricConfig {
}
exports.BuildMetricDto = BuildMetricDto;
class CreateMetricFromVisualDto extends VisualMetricConfig {
}
exports.CreateMetricFromVisualDto = CreateMetricFromVisualDto;
__decorate([
    (0, class_validator_1.IsString)(),
    (0, class_validator_1.IsNotEmpty)(),
    __metadata("design:type", String)
], CreateMetricFromVisualDto.prototype, "name", void 0);
__decorate([
    (0, class_validator_1.IsString)(),
    (0, class_validator_1.IsNotEmpty)(),
    __metadata("design:type", String)
], CreateMetricFromVisualDto.prototype, "description", void 0);
__decorate([
    (0, class_validator_1.IsOptional)(),
    (0, class_validator_1.IsEnum)(['HOUR', 'DAY', 'WEEK', 'MONTH', 'QUARTER', 'YEAR']),
    __metadata("design:type", String)
], CreateMetricFromVisualDto.prototype, "timeWindow", void 0);
__decorate([
    (0, class_validator_1.IsOptional)(),
    __metadata("design:type", Boolean)
], CreateMetricFromVisualDto.prototype, "isAutoCompare", void 0);
//# sourceMappingURL=build-metric.dto.js.map