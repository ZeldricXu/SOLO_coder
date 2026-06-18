"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.UpdateMetricDto = void 0;
const mapped_types_1 = require("@nestjs/mapped-types");
const create_metric_dto_1 = require("./create-metric.dto");
class UpdateMetricDto extends (0, mapped_types_1.PartialType)(create_metric_dto_1.CreateMetricDto) {
}
exports.UpdateMetricDto = UpdateMetricDto;
//# sourceMappingURL=update-metric.dto.js.map