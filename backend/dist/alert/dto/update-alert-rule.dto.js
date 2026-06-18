"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.UpdateAlertRuleDto = void 0;
const mapped_types_1 = require("@nestjs/mapped-types");
const create_alert_rule_dto_1 = require("./create-alert-rule.dto");
class UpdateAlertRuleDto extends (0, mapped_types_1.PartialType)(create_alert_rule_dto_1.CreateAlertRuleDto) {
}
exports.UpdateAlertRuleDto = UpdateAlertRuleDto;
//# sourceMappingURL=update-alert-rule.dto.js.map