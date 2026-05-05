import React, { useEffect, useState } from 'react';
import { Layout, Menu, Avatar, Dropdown, Typography, Badge, Space, Button } from 'antd';
import { 
  TeamOutlined, 
  CalendarOutlined, 
  BarChartOutlined, 
  LogoutOutlined,
  UserOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined
} from '@ant-design/icons';
import { useAuth } from '../contexts/AuthContext';
import { useNotifications } from '../contexts/NotificationContext';
import { useNavigate, useLocation } from 'react-router-dom';
import NotificationCenter from './NotificationCenter';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

const AppLayout = ({ children }) => {
  const [collapsed, setCollapsed] = useState(false);
  const { user, logout } = useAuth();
  const { initializeSocket, disconnectSocket } = useNotifications();
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    const token = localStorage.getItem('token');
    if (token) {
      initializeSocket(token);
    }

    return () => {
      disconnectSocket();
    };
  }, []);

  const handleLogout = () => {
    logout();
    disconnectSocket();
    navigate('/login');
  };

  const menuItems = [
    {
      key: '/',
      icon: <TeamOutlined />,
      label: '任务看板',
      onClick: () => navigate('/')
    },
    {
      key: '/gantt',
      icon: <BarChartOutlined />,
      label: '甘特图',
      onClick: () => navigate('/gantt')
    },
    {
      key: '/calendar',
      icon: <CalendarOutlined />,
      label: '日程管理',
      onClick: () => navigate('/calendar')
    }
  ];

  const userMenuItems = [
    {
      key: '1',
      label: (
        <div>
          <Text strong>{user?.username}</Text>
          <br />
          <Text type="secondary">{user?.email}</Text>
        </div>
      ),
      disabled: true
    },
    {
      type: 'divider'
    },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: handleLogout
    }
  ];

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider 
        trigger={null} 
        collapsible 
        collapsed={collapsed}
        theme="dark"
      >
        <div 
          style={{ 
            height: 64, 
            display: 'flex', 
            alignItems: 'center', 
            justifyContent: 'center',
            margin: '8px 0'
          }}
        >
          <TeamOutlined style={{ fontSize: 24, color: '#fff' }} />
          {!collapsed && (
            <span style={{ color: '#fff', fontSize: 18, fontWeight: 'bold', marginLeft: 8 }}>
              TaskFlow
            </span>
          )}
        </div>

        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
        />
      </Sider>

      <Layout>
        <Header 
          style={{ 
            padding: '0 24px', 
            background: '#fff', 
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            boxShadow: '0 1px 4px rgba(0,21,41,0.08)'
          }}
        >
          <Button
            type="text"
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed(!collapsed)}
            style={{ fontSize: 16, width: 64, height: 64 }}
          />

          <Space size={8}>
            <NotificationCenter />
            
            <Dropdown 
              menu={{ items: userMenuItems }} 
              placement="bottomRight"
            >
              <Space style={{ cursor: 'pointer' }}>
                <Avatar 
                  size={32} 
                  icon={<UserOutlined />}
                  style={{ backgroundColor: '#1890ff' }}
                >
                  {user?.username?.charAt(0).toUpperCase()}
                </Avatar>
                <Text>{user?.username}</Text>
              </Space>
            </Dropdown>
          </Space>
        </Header>

        <Content 
          style={{ 
            margin: 24, 
            padding: 24, 
            background: '#fff',
            borderRadius: 6,
            minHeight: 280
          }}
        >
          {children}
        </Content>
      </Layout>
    </Layout>
  );
};

export default AppLayout;
