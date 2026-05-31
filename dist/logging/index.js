"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.FileTransport = exports.ConsoleTransport = exports.ChildLogger = exports.LoggerService = exports.logger = void 0;
const events_1 = require("events");
const utils_1 = require("../shared/utils");
const LOG_LEVELS = {
    debug: 0,
    info: 1,
    warn: 2,
    error: 3,
    fatal: 4,
};
class ConsoleTransport {
    name = 'console';
    minLevel = 'debug';
    enabled = true;
    formatEntry(entry) {
        const { timestamp, level, message, trace_id, context } = entry;
        const contextStr = Object.keys(context).length > 0 ? ` ${JSON.stringify(context)}` : '';
        return `[${timestamp}] [${level.toUpperCase()}] [${trace_id}] ${message}${contextStr}`;
    }
    write(entry) {
        const formatted = this.formatEntry(entry);
        switch (entry.level) {
            case 'debug':
                console.debug(formatted);
                break;
            case 'info':
                console.info(formatted);
                break;
            case 'warn':
                console.warn(formatted);
                break;
            case 'error':
            case 'fatal':
                console.error(formatted);
                break;
        }
    }
}
exports.ConsoleTransport = ConsoleTransport;
class FileTransport {
    name = 'file';
    minLevel = 'info';
    enabled = false;
    logs = [];
    maxSize = 10000;
    write(entry) {
        this.logs.push(entry);
        if (this.logs.length > this.maxSize) {
            this.logs.shift();
        }
    }
    getLogs(level, limit) {
        let filtered = this.logs;
        if (level) {
            const minLevel = LOG_LEVELS[level];
            filtered = filtered.filter((l) => LOG_LEVELS[l.level] >= minLevel);
        }
        if (limit) {
            filtered = filtered.slice(-limit);
        }
        return filtered;
    }
    clear() {
        this.logs = [];
    }
}
exports.FileTransport = FileTransport;
class LoggerService extends events_1.EventEmitter {
    currentLevel = 'info';
    transports = [];
    defaultTraceId = 'system';
    moduleLevels = new Map();
    constructor() {
        super();
        this.transports.push(new ConsoleTransport());
        this.transports.push(new FileTransport());
    }
    setLevel(level) {
        this.currentLevel = level;
        this.emit('levelChanged', level);
    }
    getLevel() {
        return this.currentLevel;
    }
    setModuleLevel(module, level) {
        this.moduleLevels.set(module, level);
        this.emit('moduleLevelChanged', module, level);
    }
    getModuleLevel(module) {
        return this.moduleLevels.get(module);
    }
    setTransportLevel(transportName, level) {
        const transport = this.transports.find((t) => t.name === transportName);
        if (transport) {
            transport.minLevel = level;
        }
    }
    enableTransport(transportName) {
        const transport = this.transports.find((t) => t.name === transportName);
        if (transport) {
            transport.enabled = true;
        }
    }
    disableTransport(transportName) {
        const transport = this.transports.find((t) => t.name === transportName);
        if (transport) {
            transport.enabled = false;
        }
    }
    getTransport(transportName) {
        return this.transports.find((t) => t.name === transportName);
    }
    shouldLog(level) {
        return LOG_LEVELS[level] >= LOG_LEVELS[this.currentLevel];
    }
    log(level, message, context = {}, traceId) {
        if (!this.shouldLog(level))
            return;
        const entry = {
            timestamp: (0, utils_1.nowISO)(),
            level,
            message,
            trace_id: traceId || this.defaultTraceId,
            context,
        };
        for (const transport of this.transports) {
            if (transport.enabled && LOG_LEVELS[level] >= LOG_LEVELS[transport.minLevel]) {
                try {
                    transport.write(entry);
                }
                catch (error) {
                    console.error(`Transport ${transport.name} error:`, error);
                }
            }
        }
        this.emit('log', entry);
    }
    debug(message, context, traceId) {
        this.log('debug', message, context, traceId);
    }
    info(message, context, traceId) {
        this.log('info', message, context, traceId);
    }
    warn(message, context, traceId) {
        this.log('warn', message, context, traceId);
    }
    error(message, context, traceId) {
        this.log('error', message, context, traceId);
    }
    fatal(message, context, traceId) {
        this.log('fatal', message, context, traceId);
    }
    child(context) {
        return new ChildLogger(this, context);
    }
}
exports.LoggerService = LoggerService;
class ChildLogger {
    parent;
    context;
    constructor(parent, context) {
        this.parent = parent;
        this.context = context;
    }
    mergeContext(additional) {
        return { ...this.context, ...additional };
    }
    debug(message, additional, traceId) {
        this.parent.debug(message, this.mergeContext(additional), traceId);
    }
    info(message, additional, traceId) {
        this.parent.info(message, this.mergeContext(additional), traceId);
    }
    warn(message, additional, traceId) {
        this.parent.warn(message, this.mergeContext(additional), traceId);
    }
    error(message, additional, traceId) {
        this.parent.error(message, this.mergeContext(additional), traceId);
    }
    fatal(message, additional, traceId) {
        this.parent.fatal(message, this.mergeContext(additional), traceId);
    }
}
exports.ChildLogger = ChildLogger;
exports.logger = new LoggerService();
//# sourceMappingURL=index.js.map