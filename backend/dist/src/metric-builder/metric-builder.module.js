"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.MetricBuilderModule = void 0;
const common_1 = require("@nestjs/common");
const data_source_module_1 = require("../data-source/data-source.module");
const metric_module_1 = require("../metric/metric.module");
const metric_builder_service_1 = require("./metric-builder.service");
const metric_builder_controller_1 = require("./metric-builder.controller");
let MetricBuilderModule = class MetricBuilderModule {
};
exports.MetricBuilderModule = MetricBuilderModule;
exports.MetricBuilderModule = MetricBuilderModule = __decorate([
    (0, common_1.Module)({
        imports: [(0, common_1.forwardRef)(() => data_source_module_1.DataSourceModule), (0, common_1.forwardRef)(() => metric_module_1.MetricModule)],
        controllers: [metric_builder_controller_1.MetricBuilderController],
        providers: [metric_builder_service_1.MetricBuilderService],
        exports: [metric_builder_service_1.MetricBuilderService],
    })
], MetricBuilderModule);
//# sourceMappingURL=metric-builder.module.js.map