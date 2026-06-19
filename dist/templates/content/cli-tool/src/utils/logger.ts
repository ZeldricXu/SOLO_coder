import chalk from 'chalk';

type LogLevel = 'silent' | 'info' | 'debug' | 'error' | 'warn';

class Logger {
  level: LogLevel = 'info';

  private shouldLog(level: Exclude<LogLevel, 'silent'>): boolean {
    const levels: LogLevel[] = ['silent', 'info', 'debug', 'error', 'warn'];
    const currentIndex = levels.indexOf(this.level);
    const targetIndex = levels.indexOf(level);
    return targetIndex <= currentIndex || level === 'error' || level === 'warn';
  }

  info(message: string, ...args: unknown[]): void {
    if (this.shouldLog('info')) {
      console.log(chalk.blue('ℹ'), message, ...args);
    }
  }

  debug(message: string, ...args: unknown[]): void {
    if (this.shouldLog('debug')) {
      console.log(chalk.gray('🔍'), message, ...args);
    }
  }

  error(message: string, ...args: unknown[]): void {
    if (this.shouldLog('error')) {
      console.error(chalk.red('✗'), message, ...args);
    }
  }

  warn(message: string, ...args: unknown[]): void {
    if (this.shouldLog('warn')) {
      console.warn(chalk.yellow('⚠'), message, ...args);
    }
  }

  success(message: string, ...args: unknown[]): void {
    if (this.shouldLog('info')) {
      console.log(chalk.green('✓'), message, ...args);
    }
  }
}

export const logger = new Logger();
