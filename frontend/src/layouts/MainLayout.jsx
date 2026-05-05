import React, { useState } from 'react';
import { Layout, Menu, Button, Avatar, Dropdown, Badge } from 'antd';
import {
  CalendarOutlined,
  FormOutlined,
  AuditOutlined,
  ScanOutlined,
  BarChartOutlined,
  PieChartOutlined,
  SettingOutlined,
  LogoutOutlined,
  UserOutlined,
  BellOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import './MainLayout.css';

const { Header, Sider, Content } = Layout;

const MainLayout = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(false);

  const menuItems = [
    {
      key: '/events',
      icon: <CalendarOutlined />,
      label: '活动管理',
      onClick: () => navigate('/events'),
    },
    {
      key: '/form-config',
      icon: <FormOutlined />,
      label: '表单配置',
      onClick: () => navigate('/form-config'),
    },
    {
      key: '/reviews',
      icon: <AuditOutlined />,
      label: '报名审核',
      onClick: () => navigate('/reviews'),
    },
    {
      key: '/check-in',
      icon: <ScanOutlined />,
      label: '签到管理',
      onClick: () => navigate('/check-in'),
    },
    {
      key: '/analytics',
      icon: <BarChartOutlined />,
      label: '数据报表',
      onClick: () => navigate('/analytics'),
    },
    {
      key: '/report-config',
      icon: <PieChartOutlined />,
      label: '报表配置',
      onClick: () => navigate('/report-config'),
    },
  ];

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
      type: 'divider',
    },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
    },
  ];

  const handleMenuClick = ({ key }) => {
    if (key === 'logout') {
      localStorage.removeItem('token');
      navigate('/login');
    }
  };

  const getSelectedKey = () => {
    const path = location.pathname;
    if (path.startsWith('/events')) return '/events';
    if (path.startsWith('/form-config')) return '/form-config';
    if (path.startsWith('/reviews')) return '/reviews';
    if (path.startsWith('/check-in')) return '/check-in';
    if (path.startsWith('/analytics')) return '/analytics';
    if (path.startsWith('/report-config')) return '/report-config';
    return '/events';
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider 
        trigger={null} 
        collapsible 
        collapsed={collapsed}
        theme="dark"
        className="main-sider"
      >
        <div className="logo">
          <h2>{collapsed ? 'E' : 'EventHub'}</h2>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[getSelectedKey()]}
          items={menuItems}
        />
      </Sider>
      <Layout>
        <Header className="main-header">
          <div className="header-left">
            <Button
              type="text"
              icon={collapsed ? <span>☰</span> : <span>◀</span>}
              onClick={() => setCollapsed(!collapsed)}
            />
            <span className="header-title">
              {getSelectedKey() === '/events' && '活动管理'}
              {getSelectedKey() === '/form-config' && '报名表单配置'}
              {getSelectedKey() === '/reviews' && '报名审核'}
              {getSelectedKey() === '/check-in' && '签到管理'}
              {getSelectedKey() === '/analytics' && '数据报表'}
              {getSelectedKey() === '/report-config' && '报表配置'}
            </span>
          </div>
          <div className="header-right">
            <Badge count={3} dot>
              <Button type="text" icon={<BellOutlined />} />
            </Badge>
            <Dropdown
              menu={{ items: userMenuItems, onClick: handleMenuClick }}
              placement="bottomRight"
            >
              <div className="user-info">
                <Avatar icon={<UserOutlined />} />
                <span className="username">管理员</span>
              </div>
            </Dropdown>
          </div>
        </Header>
        <Content className="main-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
