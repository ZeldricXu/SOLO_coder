export declare class BaseError extends Error {
    readonly code: number;
    readonly details?: unknown;
    constructor(message: string, code?: number, details?: unknown);
}
export declare class ValidationError extends BaseError {
    constructor(message: string, details?: unknown);
}
export declare class TimeoutError extends BaseError {
    constructor(message?: string);
}
export declare class NotFoundError extends BaseError {
    constructor(message?: string);
}
export declare class UnauthorizedError extends BaseError {
    constructor(message?: string);
}
export declare class ResourceExhaustedError extends BaseError {
    constructor(message?: string);
}
//# sourceMappingURL=errors.d.ts.map