"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.MetricModule = void 0;
const common_1 = require("@nestjs/common");
const prisma_module_1 = require("../prisma/prisma.module");
const data_source_module_1 = require("../data-source/data-source.module");
const metric_controller_1 = require("./metric.controller");
const metric_service_1 = require("./metric.service");
let MetricModule = class MetricModule {
};
exports.MetricModule = MetricModule;
exports.MetricModule = MetricModule = __decorate([
    (0, common_1.Module)({
        imports: [prisma_module_1.PrismaModule, (0, common_1.forwardRef)(() => data_source_module_1.DataSourceModule)],
        controllers: [metric_controller_1.MetricController],
        providers: [metric_service_1.MetricService],
        exports: [metric_service_1.MetricService],
    })
], MetricModule);
//# sourceMappingURL=metric.module.js.map