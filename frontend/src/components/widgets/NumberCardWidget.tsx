import React from 'react';
import { ArrowUpOutlined, ArrowDownOutlined, MinusOutlined } from '@ant-design/icons';
import Loading from '@/components/Loading';
import { formatNumber, formatChangeRate } from '@/utils/format';
import type { WidgetProps, NumberCardData } from './types';

const trendLabelMap: Record<string, string> = {
  'day-over-day': '日环比',
  'week-over-week': '周环比',
  'month-over-month': '月环比',
  'year-over-year': '年同比',
};

const NumberCardWidget: React.FC<WidgetProps<NumberCardData>> = ({
  data,
  config,
  title,
  loading = false,
}) => {
  if (loading) {
    return (
      <div className="widget-container">
        {title && <div className="widget-title">{title}</div>}
        <div className="widget-content flex-center">
          <Loading />
        </div>
      </div>
    );
  }

  const prefix = config?.prefix as string | undefined;
  const suffix = config?.suffix as string | undefined;
  const valueColor = config?.valueColor as string | undefined;

  const displayValue = data?.value !== undefined ? formatNumber(data.value) : '-';
  const unit = data?.unit || '';

  let trendElement: React.ReactNode = null;
  if (data?.trend) {
    const { text, color } = formatChangeRate(data.trend.value);
    const label = data.trend.label || trendLabelMap[data.trend.type] || '环比';

    let ArrowIcon: React.ReactNode = <MinusOutlined style={{ color: '#999' }} />;
    if (data.trend.value > 0) {
      ArrowIcon = <ArrowUpOutlined style={{ color }} />;
    } else if (data.trend.value < 0) {
      ArrowIcon = <ArrowDownOutlined style={{ color }} />;
    }

    trendElement = (
      <div className="number-card-change">
        {ArrowIcon}
        <span style={{ color }}>{text}</span>
        <span style={{ color: 'var(--text-tertiary)', fontSize: 12, marginLeft: 4 }}>
          {label}
        </span>
      </div>
    );
  }

  return (
    <div className="widget-container">
      {title && <div className="widget-title">{title}</div>}
      <div className="widget-content">
        <div className="number-card">
          <div className="number-card-value" style={{ color: valueColor }}>
            {prefix && <span style={{ fontSize: 18, marginRight: 4 }}>{prefix}</span>}
            {displayValue}
            {unit && <span style={{ fontSize: 16, marginLeft: 4, fontWeight: 400 }}>{unit}</span>}
            {suffix && <span style={{ fontSize: 16, marginLeft: 4, fontWeight: 400 }}>{suffix}</span>}
          </div>
          {trendElement}
          {data?.label && <div className="number-card-label">{data.label}</div>}
        </div>
      </div>
    </div>
  );
};

export default NumberCardWidget;
