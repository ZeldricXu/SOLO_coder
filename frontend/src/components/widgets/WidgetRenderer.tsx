import React from 'react';
import { WidgetType } from '@/types';
import LineChartWidget from './LineChartWidget';
import BarChartWidget from './BarChartWidget';
import PieChartWidget from './PieChartWidget';
import TableWidget from './TableWidget';
import NumberCardWidget from './NumberCardWidget';
import FunnelWidget from './FunnelWidget';
import type { WidgetProps } from './types';

interface WidgetRendererProps extends WidgetProps {
  type: WidgetType;
}

const widgetMap: Record<WidgetType, React.FC<WidgetProps>> = {
  [WidgetType.LINE_CHART]: LineChartWidget,
  [WidgetType.BAR_CHART]: BarChartWidget,
  [WidgetType.PIE_CHART]: PieChartWidget,
  [WidgetType.TABLE]: TableWidget,
  [WidgetType.NUMBER_CARD]: NumberCardWidget,
  [WidgetType.FUNNEL]: FunnelWidget,
};

const WidgetRenderer: React.FC<WidgetRendererProps> = ({ type, ...props }) => {
  const WidgetComponent = widgetMap[type];

  if (!WidgetComponent) {
    return (
      <div className="widget-container flex-center" style={{ color: 'var(--text-tertiary)' }}>
        不支持的组件类型: {type}
      </div>
    );
  }

  return <WidgetComponent {...props} />;
};

export default WidgetRenderer;
