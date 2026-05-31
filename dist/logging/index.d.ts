import { EventEmitter } from 'events';
import { LogLevel, LogEntry } from '../types';
interface LogTransport {
    name: string;
    minLevel: LogLevel;
    enabled: boolean;
    write: (entry: LogEntry) => void;
}
declare class ConsoleTransport implements LogTransport {
    name: string;
    minLevel: LogLevel;
    enabled: boolean;
    private formatEntry;
    write(entry: LogEntry): void;
}
declare class FileTransport implements LogTransport {
    name: string;
    minLevel: LogLevel;
    enabled: boolean;
    private logs;
    private maxSize;
    write(entry: LogEntry): void;
    getLogs(level?: LogLevel, limit?: number): LogEntry[];
    clear(): void;
}
declare class LoggerService extends EventEmitter {
    private currentLevel;
    private transports;
    private defaultTraceId;
    private moduleLevels;
    constructor();
    setLevel(level: LogLevel): void;
    getLevel(): LogLevel;
    setModuleLevel(module: string, level: LogLevel): void;
    getModuleLevel(module: string): LogLevel | undefined;
    setTransportLevel(transportName: string, level: LogLevel): void;
    enableTransport(transportName: string): void;
    disableTransport(transportName: string): void;
    getTransport<T extends LogTransport>(transportName: string): T | undefined;
    private shouldLog;
    log(level: LogLevel, message: string, context?: Record<string, unknown>, traceId?: string): void;
    debug(message: string, context?: Record<string, unknown>, traceId?: string): void;
    info(message: string, context?: Record<string, unknown>, traceId?: string): void;
    warn(message: string, context?: Record<string, unknown>, traceId?: string): void;
    error(message: string, context?: Record<string, unknown>, traceId?: string): void;
    fatal(message: string, context?: Record<string, unknown>, traceId?: string): void;
    child(context: Record<string, unknown>): ChildLogger;
}
declare class ChildLogger {
    private parent;
    private context;
    constructor(parent: LoggerService, context: Record<string, unknown>);
    private mergeContext;
    debug(message: string, additional?: Record<string, unknown>, traceId?: string): void;
    info(message: string, additional?: Record<string, unknown>, traceId?: string): void;
    warn(message: string, additional?: Record<string, unknown>, traceId?: string): void;
    error(message: string, additional?: Record<string, unknown>, traceId?: string): void;
    fatal(message: string, additional?: Record<string, unknown>, traceId?: string): void;
}
export declare const logger: LoggerService;
export { LoggerService, ChildLogger, ConsoleTransport, FileTransport, LogTransport, LogLevel, LogEntry };
//# sourceMappingURL=index.d.ts.map