import React from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import Loading from '@/components/Loading';
import type { WidgetProps, PieChartData } from './types';

const PieChartWidget: React.FC<WidgetProps<PieChartData>> = ({
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

  const isDonut = config?.donut === true;

  const option: EChartsOption = {
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c} ({d}%)',
      ...(config?.tooltip as object),
    },
    legend: {
      orient: 'horizontal',
      bottom: 0,
      ...(config?.legend as object),
    },
    series: [
      {
        type: 'pie',
        radius: isDonut ? ['40%', '70%'] : '70%',
        center: ['50%', '45%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: config?.rounded === true ? 6 : 0,
          borderColor: '#fff',
          borderWidth: 2,
        },
        label: {
          show: config?.showLabel !== false,
          formatter: '{b}: {d}%',
          ...(config?.label as object),
        },
        emphasis: {
          label: {
            show: true,
            fontSize: 16,
            fontWeight: 'bold',
          },
        },
        data: data?.data || [],
        ...(config?.series as object),
      },
    ],
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

export default PieChartWidget;
