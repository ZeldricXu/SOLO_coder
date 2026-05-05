import React from 'react';
import { Empty } from 'antd';

const ProfitPieChart = ({ profitDistribution }) => {
  const ReactECharts = require('echarts-for-react').default;

  if (!profitDistribution) {
    return <Empty description="暂无数据" />;
  }

  const { positive_count, negative_count, flat_count, positive_value, negative_value } = profitDistribution;

  const pieData = [];
  if (positive_count > 0) {
    pieData.push({
      value: positive_count,
      name: '盈利',
      itemStyle: { color: '#ff4d4f' }
    });
  }
  if (negative_count > 0) {
    pieData.push({
      value: negative_count,
      name: '亏损',
      itemStyle: { color: '#52c41a' }
    });
  }
  if (flat_count > 0) {
    pieData.push({
      value: flat_count,
      name: '持平',
      itemStyle: { color: '#8c8c8c' }
    });
  }

  if (pieData.length === 0) {
    return <Empty description="暂无数据" />;
  }

  const option = {
    tooltip: {
      trigger: 'item',
      formatter: '{a} <br/>{b}: {c} 只 ({d}%)'
    },
    legend: {
      orient: 'vertical',
      right: '5%',
      top: 'center'
    },
    series: [
      {
        name: '持仓盈亏分布',
        type: 'pie',
        radius: ['40%', '70%'],
        center: ['35%', '50%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 10,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: {
          show: true,
          formatter: '{b}\n{c}只'
        },
        emphasis: {
          label: {
            show: true,
            fontSize: 16,
            fontWeight: 'bold'
          }
        },
        labelLine: {
          show: true
        },
        data: pieData
      }
    ]
  };

  return <ReactECharts option={option} style={{ height: '100%', width: '100%' }} />;
};

export default ProfitPieChart;
