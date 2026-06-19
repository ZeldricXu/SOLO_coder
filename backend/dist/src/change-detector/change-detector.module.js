"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.ChangeDetectorModule = void 0;
const common_1 = require("@nestjs/common");
const prisma_module_1 = require("../prisma/prisma.module");
const data_source_module_1 = require("../data-source/data-source.module");
const realtime_module_1 = require("../realtime/realtime.module");
const change_detector_service_1 = require("./change-detector.service");
let ChangeDetectorModule = class ChangeDetectorModule {
};
exports.ChangeDetectorModule = ChangeDetectorModule;
exports.ChangeDetectorModule = ChangeDetectorModule = __decorate([
    (0, common_1.Module)({
        imports: [prisma_module_1.PrismaModule, data_source_module_1.DataSourceModule, realtime_module_1.RealtimeModule],
        providers: [change_detector_service_1.ChangeDetectorService],
        exports: [change_detector_service_1.ChangeDetectorService],
    })
], ChangeDetectorModule);
//# sourceMappingURL=change-detector.module.js.map