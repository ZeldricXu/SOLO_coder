"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.LoggerContext = exports.logger = void 0;
const pino_1 = __importDefault(require("pino"));
const dayjs_1 = __importDefault(require("dayjs"));
exports.logger = (0, pino_1.default)({
    level: process.env.LOG_LEVEL || 'info',
    base: {
        service: 'contract-audit-platform',
    },
    timestamp: () => `,"time":"${(0, dayjs_1.default)().toISOString()}"`,
    formatters: {
        level: (label) => ({ level: label }),
    },
});
class LoggerContext {
    context;
    constructor(context) {
        this.context = context;
    }
    formatMessage(message, meta) {
        return {
            msg: message,
            context: {
                ...this.context,
                ...meta,
            },
        };
    }
    info(message, meta) {
        exports.logger.info(this.formatMessage(message, meta));
    }
    error(message, error, meta) {
        exports.logger.error({
            ...this.formatMessage(message, meta),
            error: error?.message,
            stack: error?.stack,
        });
    }
    warn(message, errorOrMeta, meta) {
        let error;
        let actualMeta;
        if (errorOrMeta instanceof Error) {
            error = errorOrMeta;
            actualMeta = meta;
        }
        else {
            actualMeta = errorOrMeta;
        }
        exports.logger.warn({
            ...this.formatMessage(message, actualMeta),
            error: error?.message,
            stack: error?.stack,
        });
    }
    debug(message, meta) {
        exports.logger.debug(this.formatMessage(message, meta));
    }
    child(additionalContext) {
        return new LoggerContext({
            ...this.context,
            ...additionalContext,
        });
    }
}
exports.LoggerContext = LoggerContext;
//# sourceMappingURL=logger.js.map