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
var AlertProcessor_1;
Object.defineProperty(exports, "__esModule", { value: true });
exports.AlertProcessor = void 0;
const bullmq_1 = require("@nestjs/bullmq");
const common_1 = require("@nestjs/common");
const alert_service_1 = require("./alert.service");
let AlertProcessor = AlertProcessor_1 = class AlertProcessor extends bullmq_1.WorkerHost {
    constructor(alertService) {
        super();
        this.alertService = alertService;
        this.logger = new common_1.Logger(AlertProcessor_1.name);
    }
    async process(job) {
        this.logger.debug(`Processing alert evaluation for rule ${job.data.ruleId}`);
        try {
            await this.alertService.evaluateRule(job.data.ruleId);
        }
        catch (error) {
            this.logger.error(`Failed to evaluate rule ${job.data.ruleId}: ${error.message}`, error.stack);
            throw error;
        }
    }
};
exports.AlertProcessor = AlertProcessor;
exports.AlertProcessor = AlertProcessor = AlertProcessor_1 = __decorate([
    (0, bullmq_1.Processor)('alert-evaluation'),
    __metadata("design:paramtypes", [alert_service_1.AlertService])
], AlertProcessor);
//# sourceMappingURL=alert.processor.js.map