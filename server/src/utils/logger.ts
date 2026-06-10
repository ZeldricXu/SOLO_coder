export enum LogLevel {
  DEBUG = 0,
  INFO = 1,
  WARN = 2,
  ERROR = 3,
  SILENT = 4
}

export interface LogEntry {
  timestamp: number;
  level: LogLevel;
  levelName: string;
  module: string;
  message: string;
  data?: Record<string, unknown>;
}

const levelNames: Record<LogLevel, string> = {
  [LogLevel.DEBUG]: 'DEBUG',
  [LogLevel.INFO]: 'INFO',
  [LogLevel.WARN]: 'WARN',
  [LogLevel.ERROR]: 'ERROR',
  [LogLevel.SILENT]: 'SILENT'
};

const levelColors: Record<LogLevel, string> = {
  [LogLevel.DEBUG]: '\x1b[36m',
  [LogLevel.INFO]: '\x1b[32m',
  [LogLevel.WARN]: '\x1b[33m',
  [LogLevel.ERROR]: '\x1b[31m',
  [LogLevel.SILENT]: ''
};

const RESET_COLOR = '\x1b[0m';

export class Logger {
  private module: string;
  private static globalLevel: LogLevel = LogLevel.INFO;
  private static listeners: Set<(entry: LogEntry) => void> = new Set();

  constructor(module: string) {
    this.module = module;
  }

  static setLevel(level: LogLevel): void {
    Logger.globalLevel = level;
  }

  static addListener(listener: (entry: LogEntry) => void): void {
    Logger.listeners.add(listener);
  }

  static removeListener(listener: (entry: LogEntry) => void): void {
    Logger.listeners.delete(listener);
  }

  private log(level: LogLevel, message: string, data?: Record<string, unknown>): void {
    if (level < Logger.globalLevel) {
      return;
    }

    const entry: LogEntry = {
      timestamp: Date.now(),
      level,
      levelName: levelNames[level],
      module: this.module,
      message,
      data
    };

    this.outputToConsole(entry);
    this.notifyListeners(entry);
  }

  private outputToConsole(entry: LogEntry): void {
    const color = levelColors[entry.level];
    const time = new Date(entry.timestamp).toISOString();
    const prefix = `${color}[${time}] [${entry.levelName}] [${entry.module}]${RESET_COLOR}`;

    if (entry.data) {
      console.log(`${prefix} ${entry.message}`, entry.data);
    } else {
      console.log(`${prefix} ${entry.message}`);
    }
  }

  private notifyListeners(entry: LogEntry): void {
    for (const listener of Logger.listeners) {
      try {
        listener(entry);
      } catch {
      }
    }
  }

  debug(message: string, data?: Record<string, unknown>): void {
    this.log(LogLevel.DEBUG, message, data);
  }

  info(message: string, data?: Record<string, unknown>): void {
    this.log(LogLevel.INFO, message, data);
  }

  warn(message: string, data?: Record<string, unknown>): void {
    this.log(LogLevel.WARN, message, data);
  }

  error(message: string, data?: Record<string, unknown>): void {
    this.log(LogLevel.ERROR, message, data);
  }
}

export function createLogger(module: string): Logger {
  return new Logger(module);
}
