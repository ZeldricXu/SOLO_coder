"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.UpdateWidgetDto = void 0;
const mapped_types_1 = require("@nestjs/mapped-types");
const create_widget_dto_1 = require("./create-widget.dto");
class UpdateWidgetDto extends (0, mapped_types_1.PartialType)(create_widget_dto_1.CreateWidgetDto) {
}
exports.UpdateWidgetDto = UpdateWidgetDto;
//# sourceMappingURL=update-widget.dto.js.map