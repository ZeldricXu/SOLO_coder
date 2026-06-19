import React, { useState } from 'react';
import { Layout, Menu, Avatar, Dropdown, Button, Typography, Space } from 'antd';
import {
  DashboardOutlined,
  DatabaseOutlined,
  BarChartOutlined,
  BellOutlined,
  UserOutlined,
  LogoutOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/auth';

const { Header, Sider, Content } = Layout;
const { Title } = Typography;

const LayoutPage: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);
  const user = useAuthStore((state) => state.user);
  const logout = useAuthStore((state) => state.logout);
  const navigate = useNavigate();
  const location = useLocation();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const selectedKey = (() => {
    if (location.pathname.startsWith('/dashboards')) return 'dashboards';
    if (location.pathname.startsWith('/data-sources')) return 'data-sources';
    if (location.pathname.startsWith('/metrics')) return 'metrics';
    if (location.pathname.startsWith('/alerts')) return 'alerts';
    return 'dashboards';
  })();

  const userMenuItems = [
    {
      key: 'profile',
      icon: <UserOutlined />,
      label: '个人信息',
    },
    {
      key: 'settings',
      icon: <SettingOutlined />,
      label: '设置',
    },
    {
      type: 'divider' as const,
    },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: handleLogout,
    },
  ];

  const menuItems = [
    {
      key: 'dashboards',
      icon: <DashboardOutlined />,
      label: '看板',
      onClick: () => navigate('/dashboards'),
    },
    {
      key: 'data-sources',
      icon: <DatabaseOutlined />,
      label: '数据源',
      onClick: () => navigate('/data-sources'),
    },
    {
      key: 'metrics',
      icon: <BarChartOutlined />,
      label: '指标',
      onClick: () => navigate('/metrics'),
    },
    {
      key: 'alerts',
      icon: <BellOutlined />,
      label: '告警',
      onClick: () => navigate('/alerts'),
    },
  ];

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        width={220}
        style={{
          background: '#001529',
        }}
      >
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'rgba(255, 255, 255, 0.1)',
          }}
        >
          {!collapsed && (
            <Title level={4} style={{ color: '#fff', margin: 0 }}>
              BizMonitor
            </Title>
          )}
          {collapsed && <DashboardOutlined style={{ color: '#fff', fontSize: 24 }} />}
        </div>

        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          style={{ borderRight: 0, marginTop: 16 }}
        />
      </Sider>

      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            boxShadow: '0 1px 4px rgba(0, 0, 0, 0.08)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <div />

          <Space size={16}>
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <div
                style={{
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                }}
              >
                <Avatar
                  size={36}
                  icon={<UserOutlined />}
                  style={{ backgroundColor: '#1890ff' }}
                />
                {user && <span style={{ color: '#333' }}>{user.name}</span>}
              </div>
            </Dropdown>
          </Space>
        </Header>

        <Content
          style={{
            margin: '24px',
            padding: '24px',
            background: '#fff',
            borderRadius: 8,
            minHeight: 'calc(100vh - 112px)',
          }}
        >
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default LayoutPage;
