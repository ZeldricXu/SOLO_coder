import React, { useState, useEffect } from 'react';
import { Layout as AntLayout, Menu, Dropdown, Avatar, Button, theme } from 'antd';
import {
  DashboardOutlined,
  DatabaseOutlined,
  FunctionOutlined,
  BellOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  LogoutOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation, Navigate } from 'react-router-dom';
import { useAuthStore } from '@/store/auth';
import ProtectedRoute from '@/components/ProtectedRoute';

const { Header, Sider, Content } = AntLayout;

const menuItems = [
  {
    key: '/dashboards',
    icon: <DashboardOutlined />,
    label: '仪表盘',
  },
  {
    key: '/data-sources',
    icon: <DatabaseOutlined />,
    label: '数据源',
  },
  {
    key: '/metrics',
    icon: <FunctionOutlined />,
    label: '指标管理',
  },
  {
    key: '/alerts',
    icon: <BellOutlined />,
    label: '告警中心',
  },
];

const Layout: React.FC = () => {
  const [collapsed, setCollapsed] = useState(false);
  const { user, logout, isAuthenticated, loadProfile } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();
  const {
    token: { colorBgContainer },
  } = theme.useToken();

  useEffect(() => {
    if (isAuthenticated && !user) {
      loadProfile();
    }
  }, [isAuthenticated, user, loadProfile]);

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const userMenuItems = [
    {
      key: 'profile',
      icon: <UserOutlined />,
      label: '个人中心',
    },
    {
      key: 'settings',
      icon: <SettingOutlined />,
      label: '系统设置',
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

  const handleMenuClick = ({ key }: { key: string }) => {
    navigate(key);
  };

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  return (
    <ProtectedRoute>
      <AntLayout className="layout-container">
        <Sider
          trigger={null}
          collapsible
          collapsed={collapsed}
          width={240}
          style={{ background: '#001529' }}
        >
          <div
            style={{
              height: 64,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#fff',
              fontSize: collapsed ? 16 : 20,
              fontWeight: 600,
              background: '#002140',
            }}
          >
            {collapsed ? 'BMP' : '业务监控平台'}
          </div>
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={[location.pathname]}
            items={menuItems}
            onClick={handleMenuClick}
            style={{ borderRight: 0 }}
          />
        </Sider>
        <AntLayout>
          <Header
            style={{
              padding: '0 24px',
              background: colorBgContainer,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              height: 64,
            }}
          >
            <Button
              type="text"
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setCollapsed(!collapsed)}
              style={{ fontSize: '16px', width: 64, height: 64 }}
            />
            <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
              <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
                  <Avatar size={32} icon={<UserOutlined />} />
                  <span>{user?.name || '用户'}</span>
                </div>
              </Dropdown>
            </div>
          </Header>
          <Content
            style={{
              overflow: 'auto',
              background: '#f0f2f5',
            }}
          >
            <div className="page-container">
              <Outlet />
            </div>
          </Content>
        </AntLayout>
      </AntLayout>
    </ProtectedRoute>
  );
};

export default Layout;
