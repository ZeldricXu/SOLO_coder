import React from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import Loading from '@/components/Loading';
import type { WidgetProps, FunnelData } from './types';

const FunnelWidget: React.FC<WidgetProps<FunnelData>> = ({
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

  const isAscending = config?.ascending === true;

  const option: EChartsOption = {
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c} ({d}%)',
      ...(config?.tooltip as object),
    },
    legend: {
      data: data?.data.map((item) => item.name) || [],
      bottom: 0,
      ...(config?.legend as object),
    },
    series: [
      {
        type: 'funnel',
        left: '10%',
        top: 60,
        bottom: 60,
        width: '80%',
        min: 0,
        max: 100,
        minSize: '0%',
        maxSize: '100%',
        sort: isAscending ? 'ascending' : 'descending',
        gap: 2,
        label: {
          show: true,
          position: 'inside',
          formatter: '{b}: {c}',
          ...(config?.label as object),
        },
        labelLine: {
          length: 10,
          lineStyle: {
            width: 1,
            type: 'solid',
          },
        },
        itemStyle: {
          borderColor: '#fff',
          borderWidth: 1,
          ...(config?.itemStyle as object),
        },
        emphasis: {
          label: {
            fontSize: 16,
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

export default FunnelWidget;
