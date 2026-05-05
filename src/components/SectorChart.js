import React from 'react';
import { Empty } from 'antd';

const SectorChart = ({ sectorBreakdown }) => {
  const ReactECharts = require('echarts-for-react').default;

  if (!sectorBreakdown || sectorBreakdown.length === 0) {
    return <Empty description="暂无数据" />;
  }

  const colors = [
    '#ff4d4f', '#52c41a', '#1890ff', '#faad14',
    '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16',
    '#a0d911', '#2f54eb'
  ];

  const pieData = sectorBreakdown.map((sector, index) => ({
    value: sector.market_value,
    name: sector.sector,
    itemStyle: { color: colors[index % colors.length] },
    percentage: sector.percentage
  }));

  const option = {
    tooltip: {
      trigger: 'item',
      formatter: (params) => {
        return `${params.name}<br/>
                市值: ¥${params.value.toLocaleString()}<br/>
                占比: ${params.percent}%`;
      }
    },
    legend: {
      type: 'scroll',
      orient: 'vertical',
      right: '5%',
      top: 'center',
      pageButtonPosition: 'end'
    },
    series: [
      {
        name: '行业分布',
        type: 'pie',
        radius: ['35%', '65%'],
        center: ['35%', '50%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 8,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: {
          show: true,
          formatter: '{b}\n{d}%'
        },
        emphasis: {
          label: {
            show: true,
            fontSize: 14,
            fontWeight: 'bold'
          },
          itemStyle: {
            shadowBlur: 10,
            shadowOffsetX: 0,
            shadowColor: 'rgba(0, 0, 0, 0.5)'
          }
        },
        labelLine: {
          show: true,
          length: 15,
          length2: 10
        },
        data: pieData
      }
    ]
  };

  return <ReactECharts option={option} style={{ height: '100%', width: '100%' }} />;
};

export default SectorChart;
