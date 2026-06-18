import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import 'dayjs/locale/zh-cn';

dayjs.extend(relativeTime);
dayjs.locale('zh-cn');

export interface FormatNumberOptions {
  decimals?: number;
  allowThousands?: boolean;
  suffix?: string;
  prefix?: string;
}

export function formatNumber(value: number | null | undefined, options: FormatNumberOptions = {}): string {
  if (value === null || value === undefined || isNaN(value)) {
    return '-';
  }

  const { decimals = 1, allowThousands = true, suffix = '', prefix = '' } = options;
  const abs = Math.abs(value);
  const sign = value < 0 ? '-' : '';

  let formatted: string;

  if (abs >= 1_000_000_000) {
    formatted = `${sign}${(abs / 1_000_000_000).toFixed(decimals)}B`;
  } else if (abs >= 1_000_000) {
    formatted = `${sign}${(abs / 1_000_000).toFixed(decimals)}M`;
  } else if (allowThousands && abs >= 1_000) {
    formatted = `${sign}${(abs / 1_000).toFixed(decimals)}K`;
  } else if (abs % 1 === 0) {
    formatted = `${sign}${abs.toFixed(0)}`;
  } else {
    formatted = `${sign}${abs.toFixed(decimals)}`;
  }

  return `${prefix}${formatted}${suffix}`;
}

export interface FormatPercentOptions {
  decimals?: number;
  showSign?: boolean;
}

export function formatPercent(value: number | null | undefined, options: FormatPercentOptions = {}): string {
  if (value === null || value === undefined || isNaN(value)) {
    return '-';
  }

  const { decimals = 1, showSign = false } = options;
  const percent = value * 100;
  const sign = showSign && percent > 0 ? '+' : '';

  return `${sign}${percent.toFixed(decimals)}%`;
}

export interface FormatDateOptions {
  format?: string;
  relative?: boolean;
}

export function formatDate(
  value: string | Date | number | null | undefined,
  options: FormatDateOptions = {},
): string {
  if (!value) {
    return '-';
  }

  const { format = 'YYYY-MM-DD HH:mm', relative = false } = options;
  const date = dayjs(value);

  if (!date.isValid()) {
    return '-';
  }

  if (relative) {
    return date.fromNow();
  }

  return date.format(format);
}

export interface FormatChangeRateOptions {
  decimals?: number;
  inverse?: boolean;
  showZero?: boolean;
}

export interface FormattedChangeRate {
  text: string;
  color: string;
  icon: 'up' | 'down' | 'none';
  value: number;
}

export function formatChangeRate(
  value: number | null | undefined,
  options: FormatChangeRateOptions = {},
): FormattedChangeRate {
  const { decimals = 1, inverse = false, showZero = false } = options;

  if (value === null || value === undefined || isNaN(value)) {
    return {
      text: '-',
      color: '#999',
      icon: 'none',
      value: 0,
    };
  }

  if (value === 0 && !showZero) {
    return {
      text: '-',
      color: '#999',
      icon: 'none',
      value: 0,
    };
  }

  const prefix = value > 0 ? '+' : '';
  const text = `${prefix}${(value * 100).toFixed(decimals)}%`;

  let color: string;
  let icon: 'up' | 'down' | 'none';

  if (value > 0) {
    color = inverse ? '#52c41a' : '#f5222d';
    icon = inverse ? 'down' : 'up';
  } else if (value < 0) {
    color = inverse ? '#f5222d' : '#52c41a';
    icon = inverse ? 'up' : 'down';
  } else {
    color = '#999';
    icon = 'none';
  }

  return { text, color, icon, value };
}

export function calcYoY(current: number, previous: number): number {
  if (previous === 0) {
    return current > 0 ? Infinity : current < 0 ? -Infinity : 0;
  }
  return (current - previous) / Math.abs(previous);
}

export function calcMoM(current: number, previous: number): number {
  return calcYoY(current, previous);
}

export function calcGrowthRate(current: number, base: number): number {
  return calcYoY(current, base);
}

export function formatCurrency(value: number | null | undefined, currency: string = '¥'): string {
  if (value === null || value === undefined || isNaN(value)) {
    return '-';
  }
  return `${currency}${value.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

export function formatTimeAgo(value: string | Date | number): string {
  if (!value) return '-';
  return dayjs(value).fromNow();
}

export function formatDuration(seconds: number): string {
  if (seconds < 60) {
    return `${Math.floor(seconds)}秒`;
  }
  if (seconds < 3600) {
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.floor(seconds % 60);
    return `${minutes}分${remainingSeconds > 0 ? `${remainingSeconds}秒` : ''}`;
  }
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const remainingSeconds = Math.floor(seconds % 60);
  return `${hours}时${minutes > 0 ? `${minutes}分` : ''}${remainingSeconds > 0 ? `${remainingSeconds}秒` : ''}`;
}

export function formatBytes(bytes: number, decimals: number = 2): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const dm = decimals < 0 ? 0 : decimals;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(dm))} ${sizes[i]}`;
}

export function padZero(value: number, length: number = 2): string {
  return value.toString().padStart(length, '0');
}

export function truncate(text: string, maxLength: number, suffix: string = '...'): string {
  if (!text || text.length <= maxLength) {
    return text || '';
  }
  return text.substring(0, maxLength) + suffix;
}

export function capitalize(str: string): string {
  if (!str) return '';
  return str.charAt(0).toUpperCase() + str.slice(1);
}

export function formatThousands(value: number | null | undefined, decimals: number = 0): string {
  if (value === null || value === undefined || isNaN(value)) {
    return '-';
  }
  return value.toLocaleString('zh-CN', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}
