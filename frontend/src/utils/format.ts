import dayjs from 'dayjs';

export function formatNumber(value: number): string {
  const abs = Math.abs(value);
  const sign = value < 0 ? '-' : '';

  if (abs >= 1_000_000_000) {
    return `${sign}${(abs / 1_000_000_000).toFixed(1)}B`;
  }
  if (abs >= 1_000_000) {
    return `${sign}${(abs / 1_000_000).toFixed(1)}M`;
  }
  if (abs >= 1_000) {
    return `${sign}${(abs / 1_000).toFixed(1)}K`;
  }
  return `${sign}${abs.toFixed(abs % 1 === 0 ? 0 : 1)}`;
}

export function formatPercent(value: number, decimals: number = 1): string {
  return `${(value * 100).toFixed(decimals)}%`;
}

export function formatDate(value: string | Date, format: string = 'YYYY-MM-DD HH:mm'): string {
  return dayjs(value).format(format);
}

export function formatChangeRate(value: number): { text: string; color: string } {
  const prefix = value > 0 ? '+' : '';
  const text = `${prefix}${(value * 100).toFixed(1)}%`;
  const color = value > 0 ? '#f5222d' : value < 0 ? '#52c41a' : '#999';
  return { text, color };
}
