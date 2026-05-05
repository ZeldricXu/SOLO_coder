import React from 'react';
import { Empty } from 'antd';
import dayjs from 'dayjs';

const ProfitTrendChart = ({ holdings }) => {
  const ReactECharts = require('echarts-for-react').default;

  if (!holdings || holdings.length === 0) {
    return <Empty description="暂无数据" />;
  }

  const sortedByProfit = [...holdings].sort((a, b) => (b.profit || 0) - (a.profit || 0));

  const generateMockHistoryData = () => {
    const dates = [];
    const profitData = [];
    const marketValueData = [];
    
    const totalMarketValue = holdings.reduce((sum, h) => sum + (h.market_value || 0), 0);
    const totalProfit = holdings.reduce((sum, h) => sum + (h.profit || 0), 0);
    const baseValue = totalMarketValue - totalProfit;
    
    for (let i = 30; i >= 0; i--) {
      const date = dayjs().subtract(i, 'day').format('MM-DD');
      dates.push(date);
      
      const randomFactor = 1 + (Math.random() - 0.5) * 0.1;
      const dayFactor = (30 - i) / 30;
      
      const marketValue = (baseValue + totalProfit * dayFactor) * randomFactor;
      const profit = marketValue - baseValue;
      
      profitData.push(profit);
      marketValueData.push(marketValue);
    }
    
    return { dates, profitData, marketValueData };
  };

  const { dates, profitData, marketValueData } = generateMockHistoryData();

  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'cross'
      },
      formatter: (params) => {
        let result = `${params[0].axisValue}<br/>`;
        params.forEach((p) => {
          const value = p.value;
          if (p.seriesName === '累计盈亏') {
            const sign = value >= 0 ? '+' : '';
            result += `${p.marker} ${p.seriesName}: ${sign}¥${value.toLocaleString()}<br/>`;
          } else {
            result += `${p.marker} ${p.seriesName}: ¥${value.toLocaleString()}<br/>`;
          }
        });
        return result;
      }
    },
    legend: {
      data: ['累计盈亏', '总市值'],
      top: 0
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      top: 40,
      containLabel: true
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: dates,
      axisLabel: {
        interval: 4,
        fontSize: 10
      }
    },
    yAxis: [
      {
        type: 'value',
        name: '金额',
        position: 'left',
        axisLabel: {
          formatter: (value) => {
            if (Math.abs(value) >= 10000) {
              return (value / 10000).toFixed(0) + '万';
            }
            return value.toFixed(0);
          }
        }
      }
    ],
    series: [
      {
        name: '累计盈亏',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        lineStyle: {
          width: 3,
          color: (params) => {
            const value = params.value;
            return value >= 0 ? '#ff4d4f' : '#52c41a';
          }
        },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              {
                offset: 0,
                color: 'rgba(255, 77, 79, 0.3)'
              },
              {
                offset: 1,
                color: 'rgba(255, 77, 79, 0.05)'
              }
            ]
          }
        },
        data: profitData,
        itemStyle: {
          color: (params) => {
            return params.value >= 0 ? '#ff4d4f' : '#52c41a';
          }
        }
      },
      {
        name: '总市值',
        type: 'line',
        smooth: true,
        symbol: 'diamond',
        symbolSize: 5,
        lineStyle: {
          width: 2,
          color: '#1890ff'
        },
        data: marketValueData,
        itemStyle: {
          color: '#1890ff'
        }
      }
    ]
  };

  return <ReactECharts option={option} style={{ height: '100%', width: '100%' }} />;
};

export default ProfitTrendChart;
