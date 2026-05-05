import React from 'react';
import { Row, Col, Card, Statistic, Table, Tag, Spin, Empty, Typography, Tooltip } from 'antd';
import { 
  RiseOutlined, 
  FallOutlined, 
  DashboardOutlined,
  FundProjectionScreenOutlined,
  SwapOutlined,
  DollarOutlined,
  QuestionCircleOutlined
} from '@ant-design/icons';
import ProfitPieChart from './ProfitPieChart';
import SectorChart from './SectorChart';
import HoldingsMiniChart from './HoldingsMiniChart';

const { Title, Text } = Typography;

const PortfolioOverview = ({ portfolioSummary, holdings, loading, onRefresh, refreshStatus }) => {
  if (loading && !portfolioSummary) {
    return (
      <div className="loading-container">
        <Spin size="large" tip="加载中..." />
      </div>
    );
  }

  if (!portfolioSummary || portfolioSummary.total_holdings === 0) {
    return (
      <div className="empty-container">
        <Empty 
          description={
            <div>
              <Title level={4}>暂无持仓数据</Title>
              <Text type="secondary">请先添加持仓股票开始管理您的投资组合</Text>
            </div>
          }
        />
      </div>
    );
  }

  const {
    total_market_value,
    total_cost,
    total_cost_without_commission,
    total_commission,
    total_profit,
    total_realized_profit,
    total_profit_rate,
    total_holdings,
    total_up,
    total_down,
    total_flat,
    sector_breakdown
  } = portfolioSummary;

  const columns = [
    {
      title: '股票代码',
      dataIndex: 'stock_code',
      key: 'stock_code',
      width: 100,
      fixed: 'left'
    },
    {
      title: '股票名称',
      dataIndex: 'stock_name',
      key: 'stock_name',
      width: 100
    },
    {
      title: '持仓数量',
      dataIndex: 'shares',
      key: 'shares',
      width: 100,
      render: (text) => <span>{text.toLocaleString()} 股</span>
    },
    {
      title: (
        <span>
          成本价
          <Tooltip title="不含交易佣金的成本价">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'avg_cost',
      key: 'avg_cost',
      width: 100,
      render: (text) => <span>¥{text.toFixed(2)}</span>
    },
    {
      title: (
        <span>
          实际成本
          <Tooltip title="包含交易佣金的实际成本">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'total_cost_with_commission',
      key: 'total_cost_with_commission',
      width: 120,
      render: (text, record) => {
        const shares = record.shares || 0;
        const costPerShare = shares > 0 ? (text || 0) / shares : 0;
        return (
          <Tooltip title={`每股实际成本: ¥${costPerShare.toFixed(2)}`}>
            <span>¥{text?.toFixed(2) || '--'}</span>
          </Tooltip>
        );
      }
    },
    {
      title: '现价',
      dataIndex: 'current_price',
      key: 'current_price',
      width: 100,
      render: (text, record) => {
        const changeRate = record.change_rate || 0;
        const isPositive = changeRate > 0;
        return (
          <span className={isPositive ? 'profit-positive' : changeRate < 0 ? 'profit-negative' : ''}>
            ¥{text?.toFixed(2) || '--'}
          </span>
        );
      }
    },
    {
      title: '涨跌幅',
      dataIndex: 'change_rate',
      key: 'change_rate',
      width: 100,
      render: (text) => {
        if (text === undefined || text === null) return <span>--</span>;
        const isPositive = text > 0;
        const isFlat = text === 0;
        return (
          <span className={isPositive ? 'profit-positive' : isFlat ? 'profit-flat' : 'profit-negative'}>
            {isPositive ? '+' : ''}{text.toFixed(2)}%
          </span>
        );
      }
    },
    {
      title: '市值',
      dataIndex: 'market_value',
      key: 'market_value',
      width: 140,
      render: (text) => <span>¥{text?.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) || '--'}</span>
    },
    {
      title: (
        <span>
          实际盈亏
          <Tooltip title="已扣除交易佣金后的实际盈亏">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'profit',
      key: 'profit',
      width: 140,
      render: (text, record) => {
        if (text === undefined || text === null) return <span>--</span>;
        const isPositive = text > 0;
        const isFlat = text === 0;
        const profitWithoutCommission = record.profit_rate_without_commission;
        
        return (
          <Tooltip title={`未扣佣金盈亏比例: ${profitWithoutCommission >= 0 ? '+' : ''}${profitWithoutCommission?.toFixed(2) || 0}%`}>
            <span className={isPositive ? 'profit-positive' : isFlat ? 'profit-flat' : 'profit-negative'}>
              {isPositive ? '+' : ''}¥{text.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
            </span>
          </Tooltip>
        );
      }
    },
    {
      title: '盈亏比例',
      dataIndex: 'profit_rate',
      key: 'profit_rate',
      width: 100,
      fixed: 'right',
      render: (text) => {
        if (text === undefined || text === null) return <span>--</span>;
        const isPositive = text > 0;
        const isFlat = text === 0;
        return (
          <span className={isPositive ? 'profit-positive' : isFlat ? 'profit-flat' : 'profit-negative'}>
            {isPositive ? '+' : ''}{text.toFixed(2)}%
          </span>
        );
      }
    },
    {
      title: '交易佣金',
      dataIndex: 'total_commission',
      key: 'total_commission',
      width: 110,
      render: (text) => text > 0 ? <span>¥{text?.toFixed(2) || 0}</span> : <Text type="secondary">--</Text>
    },
    {
      title: '行业',
      dataIndex: 'sector',
      key: 'sector',
      width: 100,
      render: (text) => <Tag color="blue">{text || '未分类'}</Tag>
    }
  ];

  return (
    <div>
      <div className="flex-between mb-24">
        <Title level={4} style={{ margin: 0 }}>
          <DashboardOutlined style={{ marginRight: 8 }} />
          投资组合概览
        </Title>
        {refreshStatus && (
          <Text type="secondary" style={{ fontSize: 12 }}>
            刷新间隔: {refreshStatus.current_interval_ms / 1000}秒
            {refreshStatus.is_high_volatility && (
              <Tag color="red" style={{ marginLeft: 8 }}>高波动模式</Tag>
            )}
          </Text>
        )}
      </div>

      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={12} sm={12} md={6}>
          <Card className="stat-card">
            <Statistic
              title={
                <span>
                  总市值
                  <Tooltip title="当前持仓的市场价值总和">
                    <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                  </Tooltip>
                </span>
              }
              value={total_market_value}
              precision={2}
              prefix="¥"
              valueStyle={{ fontSize: 24 }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={12} md={6}>
          <Card className="stat-card">
            <Statistic
              title={
                <span>
                  实际总成本
                  <Tooltip title="包含买入成本和所有交易佣金">
                    <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                  </Tooltip>
                </span>
              }
              value={total_cost}
              precision={2}
              prefix="¥"
              valueStyle={{ fontSize: 24, color: '#1890ff' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={12} md={6}>
          <Card className="stat-card">
            <Statistic
              title={
                <span>
                  实际盈亏
                  <Tooltip title="已扣除所有交易佣金后的实际盈亏">
                    <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                  </Tooltip>
                </span>
              }
              value={total_profit}
              precision={2}
              prefix={<span style={{ color: total_profit >= 0 ? '#ff4d4f' : '#52c41a' }}>{total_profit >= 0 ? '+' : ''}¥</span>}
              valueStyle={{ color: total_profit >= 0 ? '#ff4d4f' : '#52c41a', fontSize: 24 }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={12} md={6}>
          <Card className="stat-card">
            <Statistic
              title="盈亏比例"
              value={total_profit_rate}
              precision={2}
              suffix="%"
              prefix={total_profit_rate >= 0 ? '+' : ''}
              valueStyle={{ color: total_profit_rate >= 0 ? '#ff4d4f' : '#52c41a', fontSize: 24 }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={12} sm={6} md={4}>
          <Card>
            <Statistic
              title="持仓数量"
              value={total_holdings}
              prefix={<FundProjectionScreenOutlined />}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6} md={4}>
          <Card>
            <Statistic
              title={
                <span>
                  累计佣金
                  <Tooltip title="所有交易产生的佣金、印花税、过户费总和">
                    <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                  </Tooltip>
                </span>
              }
              value={total_commission}
              precision={2}
              prefix={<DollarOutlined />}
              valueStyle={{ color: '#fa8c16' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6} md={4}>
          <Card>
            <Statistic
              title={
                <span>
                  实现盈亏
                  <Tooltip title="已卖出股票的实际盈亏（扣除佣金）">
                    <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                  </Tooltip>
                </span>
              }
              value={total_realized_profit || 0}
              precision={2}
              prefix={<span style={{ color: (total_realized_profit || 0) >= 0 ? '#ff4d4f' : '#52c41a' }}>{(total_realized_profit || 0) >= 0 ? '+' : ''}¥</span>}
              valueStyle={{ color: (total_realized_profit || 0) >= 0 ? '#ff4d4f' : '#52c41a' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6} md={4}>
          <Card>
            <Statistic
              title="盈利"
              value={total_up}
              valueStyle={{ color: '#ff4d4f' }}
              prefix={<RiseOutlined />}
              suffix="只"
            />
          </Card>
        </Col>
        <Col xs={12} sm={6} md={4}>
          <Card>
            <Statistic
              title="亏损"
              value={total_down}
              valueStyle={{ color: '#52c41a' }}
              prefix={<FallOutlined />}
              suffix="只"
            />
          </Card>
        </Col>
        <Col xs={12} sm={6} md={4}>
          <Card>
            <Statistic
              title="持平"
              value={total_flat}
              prefix={<SwapOutlined />}
              suffix="只"
            />
          </Card>
        </Col>
      </Row>

      {total_commission > 0 && (
        <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
          <Col span={24}>
            <Card 
              size="small"
              title={
                <Text type="secondary" style={{ fontSize: 12 }}>
                  <DollarOutlined style={{ marginRight: 4 }} />
                  成本明细 (总成本: ¥{total_cost.toLocaleString()} | 买入成本: ¥{total_cost_without_commission?.toLocaleString() || 0} | 佣金费用: ¥{total_commission.toLocaleString()})
                </Text>
              }
            >
              <Text type="secondary" style={{ fontSize: 12 }}>
                提示：实际盈亏已扣除所有交易佣金（佣金、印花税、过户费），盈亏比例基于包含佣金的实际总成本计算。
              </Text>
            </Card>
          </Col>
        </Row>
      )}

      <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
        <Col xs={24} lg={12}>
          <Card title={<span><FundProjectionScreenOutlined style={{ marginRight: 8 }} />持仓占比</span>}>
            <HoldingsMiniChart holdings={holdings} />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card title={<span><DashboardOutlined style={{ marginRight: 8 }} />行业分布</span>}>
            <SectorChart sectorBreakdown={sector_breakdown} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col span={24}>
          <Card 
            title={<span><DashboardOutlined style={{ marginRight: 8 }} />持仓列表</span>}
            extra={
              <Text type="secondary">
                共 {holdings.length} 只股票
              </Text>
            }
          >
            <Table
              className="holding-table"
              columns={columns}
              dataSource={holdings}
              rowKey="holding_id"
              pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
              scroll={{ x: 1600 }}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default PortfolioOverview;
