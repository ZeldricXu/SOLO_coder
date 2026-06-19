"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.UpdateDataSourceDto = void 0;
const mapped_types_1 = require("@nestjs/mapped-types");
const create_data_source_dto_1 = require("./create-data-source.dto");
class UpdateDataSourceDto extends (0, mapped_types_1.PartialType)(create_data_source_dto_1.CreateDataSourceDto) {
}
exports.UpdateDataSourceDto = UpdateDataSourceDto;
//# sourceMappingURL=update-data-source.dto.js.map