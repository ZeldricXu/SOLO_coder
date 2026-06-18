"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.UpdateBusinessLineDto = void 0;
const mapped_types_1 = require("@nestjs/mapped-types");
const create_business_line_dto_1 = require("./create-business-line.dto");
class UpdateBusinessLineDto extends (0, mapped_types_1.PartialType)(create_business_line_dto_1.CreateBusinessLineDto) {
}
exports.UpdateBusinessLineDto = UpdateBusinessLineDto;
//# sourceMappingURL=update-business-line.dto.js.map