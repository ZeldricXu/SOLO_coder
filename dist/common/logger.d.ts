export declare const logger: import("pino").Logger<never>;
export declare class LoggerContext {
    private context;
    constructor(context: Record<string, unknown>);
    private formatMessage;
    info(message: string, meta?: Record<string, unknown>): void;
    error(message: string, error?: Error, meta?: Record<string, unknown>): void;
    warn(message: string, errorOrMeta?: Error | Record<string, unknown>, meta?: Record<string, unknown>): void;
    debug(message: string, meta?: Record<string, unknown>): void;
    child(additionalContext: Record<string, unknown>): LoggerContext;
}
//# sourceMappingURL=logger.d.ts.map