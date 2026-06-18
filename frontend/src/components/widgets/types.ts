export interface WidgetProps<T = unknown> {
  data?: T;
  config?: Record<string, unknown>;
  title?: string;
  loading?: boolean;
  height?: number | string;
}

export interface ChartDataPoint {
  name: string;
  value: number;
  [key: string]: unknown;
}

export interface LineChartData {
  xAxis: string[];
  series: {
    name: string;
    data: number[];
  }[];
}

export interface BarChartData {
  xAxis: string[];
  series: {
    name: string;
    data: number[];
  }[];
}

export interface PieChartData {
  data: {
    name: string;
    value: number;
  }[];
}

export interface TableData {
  columns: {
    key: string;
    title: string;
    dataIndex: string;
    width?: number | string;
    align?: 'left' | 'center' | 'right';
  }[];
  data: Record<string, unknown>[];
  pagination?: {
    current: number;
    pageSize: number;
    total: number;
  };
}

export interface NumberCardData {
  value: number;
  unit?: string;
  trend?: {
    value: number;
    type: 'day-over-day' | 'week-over-week' | 'month-over-month' | 'year-over-year';
    label?: string;
  };
  label?: string;
}

export interface FunnelData {
  data: {
    name: string;
    value: number;
  }[];
}
