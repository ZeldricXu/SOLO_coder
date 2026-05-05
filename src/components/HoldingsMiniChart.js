import React from 'react';
import { Empty } from 'antd';

const HoldingsMiniChart = ({ holdings }) => {
  const ReactECharts = require('echarts-for-react').default;

  if (!holdings || holdings.length === 0) {
    return <Empty description="暂无数据" />;
  }

  const sortedHoldings = [...holdings].sort((a, b) => (b.market_value || 0) - (a.market_value || 0));
  
  const colors = [
    '#ff4d4f', '#52c41a', '#1890ff', '#faad14',
    '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16',
    '#a0d911', '#2f54eb'
  ];

  const pieData = sortedHoldings.slice(0, 10).map((holding, index) => ({
    value: holding.market_value || 0,
    name: holding.stock_name || holding.stock_code,
    itemStyle: { color: colors[index % colors.length] }
  }));

  const option = {
    tooltip: {
      trigger: 'item',
      formatter: (params) => {
        const holding = sortedHoldings.find(h => 
          (h.stock_name || h.stock_code) === params.name
        );
        const profitRate = holding?.profit_rate || 0;
        return `${params.name}<br/>
                市值: ¥${params.value.toLocaleString()}<br/>
                占比: ${params.percent}%<br/>
                盈亏比例: ${profitRate >= 0 ? '+' : ''}${profitRate.toFixed(2)}%`;
      }
    },
    legend: {
      type: 'scroll',
      orient: 'vertical',
      right: '2%',
      top: 'center',
      itemWidth: 12,
      itemHeight: 12,
      textStyle: {
        fontSize: 11
      }
    },
    series: [
      {
        name: '持仓占比',
        type: 'pie',
        radius: ['30%', '60%'],
        center: ['35%', '50%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 6,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: {
          show: false
        },
        emphasis: {
          label: {
            show: true,
            fontSize: 12,
            fontWeight: 'bold'
          }
        },
        labelLine: {
          show: false
        },
        data: pieData
      }
    ]
  };

  return <ReactECharts option={option} style={{ height: '100%', width: '100%' }} />;
};

export default HoldingsMiniChart;
