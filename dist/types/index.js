"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.DeviceStatus = exports.TaskStatus = void 0;
var TaskStatus;
(function (TaskStatus) {
    TaskStatus["PENDING"] = "pending";
    TaskStatus["RUNNING"] = "running";
    TaskStatus["COMPLETED"] = "completed";
    TaskStatus["FAILED"] = "failed";
    TaskStatus["CANCELLED"] = "cancelled";
    TaskStatus["TIMEOUT"] = "timeout";
})(TaskStatus || (exports.TaskStatus = TaskStatus = {}));
var DeviceStatus;
(function (DeviceStatus) {
    DeviceStatus["INACTIVE"] = "inactive";
    DeviceStatus["ACTIVE"] = "active";
    DeviceStatus["OFFLINE"] = "offline";
    DeviceStatus["ERROR"] = "error";
    DeviceStatus["DECOMMISSIONED"] = "decommissioned";
})(DeviceStatus || (exports.DeviceStatus = DeviceStatus = {}));
//# sourceMappingURL=index.js.map