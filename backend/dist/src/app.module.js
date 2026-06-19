"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.AppModule = void 0;
const common_1 = require("@nestjs/common");
const config_1 = require("@nestjs/config");
const bullmq_1 = require("@nestjs/bullmq");
const prisma_module_1 = require("./prisma/prisma.module");
const data_source_module_1 = require("./data-source/data-source.module");
const metric_module_1 = require("./metric/metric.module");
const dashboard_module_1 = require("./dashboard/dashboard.module");
const realtime_module_1 = require("./realtime/realtime.module");
const alert_module_1 = require("./alert/alert.module");
const auth_module_1 = require("./auth/auth.module");
const tenant_module_1 = require("./tenant/tenant.module");
const audit_module_1 = require("./audit/audit.module");
let AppModule = class AppModule {
};
exports.AppModule = AppModule;
exports.AppModule = AppModule = __decorate([
    (0, common_1.Module)({
        imports: [
            config_1.ConfigModule.forRoot({
                isGlobal: true,
            }),
            bullmq_1.BullModule.forRootAsync({
                inject: [config_1.ConfigService],
                useFactory: (configService) => ({
                    connection: {
                        host: configService.get('REDIS_HOST', 'localhost'),
                        port: configService.get('REDIS_PORT', 6379),
                    },
                }),
            }),
            prisma_module_1.PrismaModule,
            data_source_module_1.DataSourceModule,
            metric_module_1.MetricModule,
            dashboard_module_1.DashboardModule,
            realtime_module_1.RealtimeModule,
            alert_module_1.AlertModule,
            auth_module_1.AuthModule,
            tenant_module_1.TenantModule,
            audit_module_1.AuditModule,
        ],
    })
], AppModule);
//# sourceMappingURL=app.module.js.map