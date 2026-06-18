import React from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import Loading from '@/components/Loading';
import type { WidgetProps, LineChartData } from './types';

const LineChartWidget: React.FC<WidgetProps<LineChartData>> = ({
  data,
  config,
  title,
  loading = false,
  height = '100%',
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

  const option: EChartsOption = {
    tooltip: {
      trigger: 'axis',
      ...(config?.tooltip as object),
    },
    legend: {
      data: data?.series.map((s) => s.name) || [],
      bottom: 0,
      ...(config?.legend as object),
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '15%',
      top: '10%',
      containLabel: true,
      ...(config?.grid as object),
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: data?.xAxis || [],
      ...(config?.xAxis as object),
    },
    yAxis: {
      type: 'value',
      ...(config?.yAxis as object),
    },
    series:
      data?.series.map((s, index) => ({
        name: s.name,
        type: 'line',
        data: s.data,
        smooth: true,
        showSymbol: false,
        lineStyle: {
          width: 2,
        },
        areaStyle:
          config?.showArea === true
            ? {
                opacity: 0.1,
              }
            : undefined,
        ...(config?.series?.[index] as object),
      })) || [],
    color: config?.colors as string[],
  };

  return (
    <div className="widget-container">
      {title && <div className="widget-title">{title}</div>}
      <div className="widget-content">
        <ReactECharts option={option} style={{ height, width: '100%' }} />
      </div>
    </div>
  );
};

export default LineChartWidget;
