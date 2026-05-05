import React from 'react';
import { Row, Col, Card, Typography, Empty } from 'antd';
import {
  BarChartOutlined,
  PieChartOutlined,
  LineChartOutlined,
  FundProjectionScreenOutlined
} from '@ant-design/icons';
import ProfitPieChart from './ProfitPieChart';
import SectorChart from './SectorChart';
import HoldingsLineChart from './HoldingsLineChart';
import ProfitTrendChart from './ProfitTrendChart';

const { Title, Text } = Typography;

const AnalyticsPage = ({ portfolioSummary, holdings }) => {
  if (!portfolioSummary || holdings.length === 0) {
    return (
      <div className="empty-container">
        <Empty
          description={
            <div>
              <Title level={4}>暂无数据</Title>
              <Text type="secondary">请先添加持仓股票以查看数据分析</Text>
            </div>
          }
        />
      </div>
    );
  }

  const { sector_breakdown, profit_distribution } = portfolioSummary;

  return (
    <div>
      <Title level={4} style={{ marginBottom: 24 }}>
        <BarChartOutlined style={{ marginRight: 8 }} />
        数据分析
      </Title>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card 
            title={
              <span>
                <PieChartOutlined style={{ marginRight: 8 }} />
                持仓市值分布
              </span>
            }
          >
            <div className="small-chart-container">
              <HoldingsLineChart holdings={holdings} />
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card 
            title={
              <span>
                <PieChartOutlined style={{ marginRight: 8 }} />
                行业分布
              </span>
            }
          >
            <div className="small-chart-container">
              <SectorChart sectorBreakdown={sector_breakdown} />
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card 
            title={
              <span>
                <BarChartOutlined style={{ marginRight: 8 }} />
                盈亏分布
              </span>
            }
          >
            <div className="small-chart-container">
              <ProfitPieChart profitDistribution={profit_distribution} />
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card 
            title={
              <span>
                <LineChartOutlined style={{ marginRight: 8 }} />
                持仓盈亏分析
              </span>
            }
          >
            <div className="small-chart-container">
              <ProfitTrendChart holdings={holdings} />
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card 
            title={
              <span>
                <FundProjectionScreenOutlined style={{ marginRight: 8 }} />
                各股盈亏详情
              </span>
            }
          >
            <div className="chart-container">
              <HoldingsBarChart holdings={holdings} />
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

const HoldingsBarChart = ({ holdings }) => {
  const ReactECharts = require('echarts-for-react').default;

  const sortedHoldings = [...holdings].sort((a, b) => (b.profit || 0) - (a.profit || 0));
  
  const option = {
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'shadow'
      },
      formatter: (params) => {
        const data = params[0];
        const holding = sortedHoldings[data.dataIndex];
        const profit = data.value;
        const profitRate = holding?.profit_rate || 0;
        return `${holding?.stock_name || data.name}<br/>
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
      data: sortedHoldings.map(h => h.stock_name || h.stock_code),
      axisLabel: {
        rotate: 30,
        interval: 0
      }
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        formatter: '{value}'
      }
    },
    series: [
      {
        name: '盈亏金额',
        type: 'bar',
        data: sortedHoldings.map(h => h.profit || 0),
        itemStyle: {
          color: (params) => {
            return params.value >= 0 ? '#ff4d4f' : '#52c41a';
          }
        },
        label: {
          show: true,
          position: 'top',
          formatter: (params) => {
            const value = params.value;
            if (value === 0) return '';
            return value >= 0 ? `+${(value / 10000).toFixed(1)}万` : `${(value / 10000).toFixed(1)}万`;
          }
        }
      }
    ]
  };

  return <ReactECharts option={option} style={{ height: '100%', width: '100%' }} />;
};

export default AnalyticsPage;
