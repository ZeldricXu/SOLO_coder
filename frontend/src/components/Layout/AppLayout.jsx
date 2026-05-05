import React from 'react';
import { Layout, Menu, Avatar, Dropdown, Button, theme } from 'antd';
import {
  FileTextOutlined,
  SearchOutlined,
  StarOutlined,
  FolderOpenOutlined,
  HistoryOutlined,
  UserOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined
} from '@ant-design/icons';
import { useNavigate, useLocation } from 'react-router-dom';
import { useApp } from '../../context/AppContext';

const { Header, Sider, Content } = Layout;

const AppLayout = ({ children }) => {
  const navigate = useNavigate();
  const location = useLocation();
  const { sidebarCollapsed, setSidebarCollapsed, currentUser } = useApp();
  const {
    token: { colorBgContainer, borderRadiusLG }
  } = theme.useToken();

  const menuItems = [
    {
      key: '/documents',
      icon: <FileTextOutlined />,
      label: '文档管理',
      onClick: () => navigate('/documents')
    },
    {
      key: '/search',
      icon: <SearchOutlined />,
      label: '文档检索',
      onClick: () => navigate('/search')
    },
    {
      key: '/categories',
      icon: <FolderOpenOutlined />,
      label: '分类管理',
      onClick: () => navigate('/categories')
    },
    {
      key: '/favorites',
      icon: <StarOutlined />,
      label: '我的收藏',
      onClick: () => navigate('/favorites')
    },
    {
      key: '/recent',
      icon: <HistoryOutlined />,
      label: '最近文档',
      onClick: () => navigate('/recent')
    }
  ];

  const userMenu = [
    {
      key: '1',
      label: `当前用户: ${currentUser}`
    },
    {
      key: '2',
      label: '用户设置'
    }
  ];

  const getSelectedKey = () => {
    const path = location.pathname;
    if (path.startsWith('/documents')) return '/documents';
    if (path.startsWith('/search')) return '/search';
    if (path.startsWith('/categories')) return '/categories';
    if (path.startsWith('/favorites')) return '/favorites';
    if (path.startsWith('/recent')) return '/recent';
    if (path.startsWith('/edit')) return '/documents';
    return '/documents';
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        trigger={null}
        collapsible
        collapsed={sidebarCollapsed}
        theme="light"
        width={220}
      >
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontWeight: 'bold',
            fontSize: 18,
            color: '#1890ff'
          }}
        >
          {sidebarCollapsed ? 'Doc' : 'DocHub'}
        </div>
        <Menu
          mode="inline"
          selectedKeys={[getSelectedKey()]}
          items={menuItems}
          style={{ borderRight: 0 }}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            padding: '0 24px',
            background: colorBgContainer,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between'
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center' }}>
            <Button
              type="text"
              icon={sidebarCollapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
            />
            <span style={{ marginLeft: 16, fontSize: 16, fontWeight: 500 }}>
              DocHub 文档协作平台
            </span>
          </div>
          <Dropdown menu={{ items: userMenu }} placement="bottomRight">
            <div style={{ display: 'flex', alignItems: 'center', cursor: 'pointer' }}>
              <Avatar icon={<UserOutlined />} style={{ marginRight: 8 }} />
              <span>{currentUser}</span>
            </div>
          </Dropdown>
        </Header>
        <Content
          style={{
            margin: '24px',
            padding: 24,
            minHeight: 280,
            background: colorBgContainer,
            borderRadius: borderRadiusLG
          }}
        >
          {children}
        </Content>
      </Layout>
    </Layout>
  );
};

export default AppLayout;
