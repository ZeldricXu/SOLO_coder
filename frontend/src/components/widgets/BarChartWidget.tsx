import React from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import Loading from '@/components/Loading';
import type { WidgetProps, BarChartData } from './types';

const BarChartWidget: React.FC<WidgetProps<BarChartData>> = ({
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

  const isHorizontal = config?.horizontal === true;

  const option: EChartsOption = {
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'shadow',
      },
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
    xAxis: isHorizontal
      ? {
          type: 'value',
          ...(config?.xAxis as object),
        }
      : {
          type: 'category',
          data: data?.xAxis || [],
          ...(config?.xAxis as object),
        },
    yAxis: isHorizontal
      ? {
          type: 'category',
          data: data?.xAxis || [],
          ...(config?.yAxis as object),
        }
      : {
          type: 'value',
          ...(config?.yAxis as object),
        },
    series:
      data?.series.map((s, index) => ({
        name: s.name,
        type: 'bar',
        data: s.data,
        barMaxWidth: 40,
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

export default BarChartWidget;
