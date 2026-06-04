const LOG_LEVELS = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
} as const;

type LogLevel = keyof typeof LOG_LEVELS;

const currentLevel: LogLevel = (process.env.LOG_LEVEL as LogLevel) || 'info';

const shouldLog = (level: LogLevel): boolean => {
  return LOG_LEVELS[level] >= LOG_LEVELS[currentLevel];
};

const formatLog = (level: LogLevel, message: string, data?: any): string => {
  const timestamp = new Date().toISOString();
  const logData = data ? ` ${JSON.stringify(data)}` : '';
  return `[${timestamp}] [${level.toUpperCase()}] ${message}${logData}`;
};

export const logger = {
  debug: (message: string, data?: any) => {
    if (shouldLog('debug')) {
      console.debug(formatLog('debug', message, data));
    }
  },
  info: (message: string, data?: any) => {
    if (shouldLog('info')) {
      console.info(formatLog('info', message, data));
    }
  },
  warn: (message: string, data?: any) => {
    if (shouldLog('warn')) {
      console.warn(formatLog('warn', message, data));
    }
  },
  error: (message: string, data?: any) => {
    if (shouldLog('error')) {
      console.error(formatLog('error', message, data));
    }
  },
};
