"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ResourceExhaustedError = exports.UnauthorizedError = exports.NotFoundError = exports.TimeoutError = exports.ValidationError = exports.BaseError = void 0;
class BaseError extends Error {
    constructor(message, code = 500, details) {
        super(message);
        this.name = this.constructor.name;
        this.code = code;
        this.details = details;
        Error.captureStackTrace(this, this.constructor);
    }
}
exports.BaseError = BaseError;
class ValidationError extends BaseError {
    constructor(message, details) {
        super(message, 422, details);
    }
}
exports.ValidationError = ValidationError;
class TimeoutError extends BaseError {
    constructor(message = '操作超时') {
        super(message, 504);
    }
}
exports.TimeoutError = TimeoutError;
class NotFoundError extends BaseError {
    constructor(message = '资源不存在') {
        super(message, 404);
    }
}
exports.NotFoundError = NotFoundError;
class UnauthorizedError extends BaseError {
    constructor(message = '未授权') {
        super(message, 401);
    }
}
exports.UnauthorizedError = UnauthorizedError;
class ResourceExhaustedError extends BaseError {
    constructor(message = '资源耗尽') {
        super(message, 429);
    }
}
exports.ResourceExhaustedError = ResourceExhaustedError;
//# sourceMappingURL=errors.js.map