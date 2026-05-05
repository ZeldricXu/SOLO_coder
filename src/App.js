import React, { useState, useEffect, useCallback } from 'react';
import { Layout, Menu, Button, message, Space, Typography, Badge, Tag } from 'antd';
import {
  LineChartOutlined,
  ShopOutlined,
  TransactionOutlined,
  BellOutlined,
  BarChartOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
  FireOutlined,
  SettingOutlined
} from '@ant-design/icons';
import PortfolioOverview from './components/PortfolioOverview';
import HoldingsPage from './components/HoldingsPage';
import TradesPage from './components/TradesPage';
import AlertsPage from './components/AlertsPage';
import AnalyticsPage from './components/AnalyticsPage';
import SettingsPage from './components/SettingsPage';

const { Header, Sider, Content } = Layout;
const { Title, Text } = Typography;

const menuItems = [
  { key: 'overview', icon: <LineChartOutlined />, label: '投资概览' },
  { key: 'holdings', icon: <ShopOutlined />, label: '持仓管理' },
  { key: 'trades', icon: <TransactionOutlined />, label: '交易记录' },
  { key: 'alerts', icon: <BellOutlined />, label: '预警通知' },
  { key: 'analytics', icon: <BarChartOutlined />, label: '数据分析' },
  { key: 'settings', icon: <SettingOutlined />, label: '系统设置' }
];

function App() {
  const [selectedKey, setSelectedKey] = useState('overview');
  const [portfolioSummary, setPortfolioSummary] = useState(null);
  const [holdings, setHoldings] = useState([]);
  const [loading, setLoading] = useState(false);
  const [alertsCount, setAlertsCount] = useState(0);
  const [refreshStatus, setRefreshStatus] = useState(null);

  const fetchPortfolioSummary = useCallback(async () => {
    try {
      setLoading(true);
      const summary = await window.electronAPI.getPortfolioSummary();
      setPortfolioSummary(summary);
      setHoldings(summary.holdings || []);
      
      const status = await window.electronAPI.getRefreshStatus();
      setRefreshStatus(status);
    } catch (error) {
      message.error('加载投资组合数据失败: ' + error.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const handleManualRefresh = async () => {
    try {
      message.loading({ content: '正在刷新行情...', key: 'refresh' });
      const result = await window.electronAPI.refreshQuotesManual();
      
      if (result.status === 'success') {
        await fetchPortfolioSummary();
        message.success({ content: `行情已更新 (${result.quotes_count}只股票)`, key: 'refresh' });
      } else if (result.status === 'already_in_progress') {
        message.info({ content: '刷新正在进行中，请稍候...', key: 'refresh' });
      } else if (result.status === 'no_holdings') {
        message.warning({ content: '暂无持仓股票', key: 'refresh' });
      }
    } catch (error) {
      message.error('刷新行情失败: ' + error.message);
    }
  };

  useEffect(() => {
    fetchPortfolioSummary();

    const handleQuotesUpdated = (quotes) => {
      fetchPortfolioSummary();
    };

    const handleRefreshIntervalChanged = (data) => {
      setRefreshStatus(prev => ({
        ...prev,
        ...data
      }));
      
      const intervalSeconds = data.interval_ms / 1000;
      if (data.is_high_volatility) {
        message.info({
          content: `检测到市场波动，已切换到快速刷新模式 (${intervalSeconds}秒)`,
          key: 'refresh-mode',
          duration: 3
        });
      } else {
        message.info({
          content: `市场平稳，已切换到正常刷新模式 (${intervalSeconds}秒)`,
          key: 'refresh-mode',
          duration: 3
        });
      }
    };

    window.electronAPI.onQuotesUpdated(handleQuotesUpdated);
    window.electronAPI.onRefreshIntervalChanged(handleRefreshIntervalChanged);

    return () => {
      window.electronAPI.removeQuotesUpdatedListener(handleQuotesUpdated);
      window.electronAPI.removeRefreshIntervalChangedListener(handleRefreshIntervalChanged);
    };
  }, [fetchPortfolioSummary]);

  const handleMenuClick = ({ key }) => {
    setSelectedKey(key);
  };

  const getRefreshStatusTag = () => {
    if (!refreshStatus) return null;

    const intervalSeconds = refreshStatus.current_interval_ms / 1000;
    
    if (refreshStatus.is_high_volatility) {
      return (
        <Tag icon={<FireOutlined />} color="red">
          高波动模式 · {intervalSeconds}秒刷新
        </Tag>
      );
    } else if (refreshStatus.current_interval_ms > 60000) {
      return (
        <Tag icon={<ThunderboltOutlined />} color="green">
          节能模式 · {intervalSeconds}秒刷新
        </Tag>
      );
    }
    return (
      <Tag color="blue">
        正常模式 · {intervalSeconds}秒刷新
      </Tag>
    );
  };

  const renderContent = () => {
    switch (selectedKey) {
      case 'overview':
        return (
          <PortfolioOverview 
            portfolioSummary={portfolioSummary}
            holdings={holdings}
            loading={loading}
            onRefresh={fetchPortfolioSummary}
            refreshStatus={refreshStatus}
          />
        );
      case 'holdings':
        return (
          <HoldingsPage 
            holdings={holdings}
            onHoldingsChange={fetchPortfolioSummary}
          />
        );
      case 'trades':
        return <TradesPage />;
      case 'alerts':
        return <AlertsPage />;
      case 'analytics':
        return (
          <AnalyticsPage 
            portfolioSummary={portfolioSummary}
            holdings={holdings}
          />
        );
      case 'settings':
        return <SettingsPage />;
      default:
        return <PortfolioOverview portfolioSummary={portfolioSummary} holdings={holdings} refreshStatus={refreshStatus} />;
    }
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ height: 64, lineHeight: '64px' }}>
        <div style={{ display: 'flex', alignItems: 'center' }}>
          <Title level={4} style={{ color: '#fff', margin: 0, marginRight: 24 }}>
            <LineChartOutlined style={{ marginRight: 8 }} />
            StockTracker
          </Title>
          {getRefreshStatusTag()}
        </div>
        <Space>
          <Button 
            type="primary" 
            icon={<ReloadOutlined />} 
            onClick={handleManualRefresh}
            loading={loading}
          >
            刷新行情
          </Button>
          <Badge count={alertsCount} size="small">
            <Button icon={<BellOutlined />} onClick={() => setSelectedKey('alerts')}>
              预警
            </Button>
          </Badge>
        </Space>
      </Header>
      <Layout>
        <Sider width={200} theme="dark">
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={[selectedKey]}
            items={menuItems}
            onClick={handleMenuClick}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Sider>
        <Content style={{ padding: 24, margin: 0, minHeight: 280, background: '#f0f2f5' }}>
          {renderContent()}
        </Content>
      </Layout>
    </Layout>
  );
}

export default App;
