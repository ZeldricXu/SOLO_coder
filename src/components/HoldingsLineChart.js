import React from 'react';
import { Empty } from 'antd';

const HoldingsLineChart = ({ holdings }) => {
  const ReactECharts = require('echarts-for-react').default;

  if (!holdings || holdings.length === 0) {
    return <Empty description="暂无数据" />;
  }

  const sortedByMarketValue = [...holdings].sort((a, b) => (b.market_value || 0) - (a.market_value || 0));
  const topHoldings = sortedByMarketValue.slice(0, 8);

  const colors = [
    '#ff4d4f', '#52c41a', '#1890ff', '#faad14',
    '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16'
  ];

  const categories = topHoldings.map(h => h.stock_name || h.stock_code);

  const seriesData = topHoldings.map((h, index) => ({
    name: h.stock_name || h.stock_code,
    value: h.market_value || 0,
    itemStyle: { color: colors[index % colors.length] }
  }));

  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'shadow'
      },
      formatter: (params) => {
        const data = params[0];
        const holding = topHoldings.find(h => 
          (h.stock_name || h.stock_code) === data.name
        );
        const profitRate = holding?.profit_rate || 0;
        const profit = holding?.profit || 0;
        return `${data.name}<br/>
                市值: ¥${data.value.toLocaleString()}<br/>
                盈亏: ${profit >= 0 ? '+' : ''}¥${profit.toLocaleString()}<br/>
                盈亏比例: ${profitRate >= 0 ? '+' : ''}${profitRate.toFixed(2)}%`;
      }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: categories,
      axisLabel: {
        rotate: 25,
        interval: 0,
        fontSize: 11
      }
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        formatter: (value) => {
          if (value >= 10000) {
            return (value / 10000).toFixed(0) + '万';
          }
          return value;
        }
      }
    },
    series: [
      {
        name: '市值',
        type: 'bar',
        barWidth: '50%',
        data: seriesData,
        label: {
          show: true,
          position: 'top',
          formatter: (params) => {
            const value = params.value;
            if (value >= 10000) {
              return (value / 10000).toFixed(1) + '万';
            }
            return value.toFixed(0);
          },
          fontSize: 11
        }
      }
    ]
  };

  return <ReactECharts option={option} style={{ height: '100%', width: '100%' }} />;
};

export default HoldingsLineChart;
