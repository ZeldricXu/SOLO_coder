import { useState, useEffect } from 'react'
import { Layout, Menu, theme, Avatar, Dropdown, Space } from 'antd'
import {
  DashboardOutlined,
  SwitcherOutlined,
  AuditOutlined,
  SettingOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons'
import { useNavigate, useLocation } from 'react-router-dom'
import type { MenuProps } from 'antd'

const { Header, Sider, Content } = Layout

interface AppLayoutProps {
  children: React.ReactNode
}

const AppLayout: React.FC<AppLayoutProps> = ({ children }) => {
  const navigate = useNavigate()
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)
  const {
    token: { colorBgContainer, borderRadiusLG },
  } = theme.useToken()

  const menuItems: MenuProps['items'] = [
    {
      key: '/dashboard',
      icon: <DashboardOutlined />,
      label: '数据总览',
    },
    {
      key: '/switches',
      icon: <SwitcherOutlined />,
      label: '开关管理',
    },
    {
      key: '/approvals',
      icon: <AuditOutlined />,
      label: '审批管理',
    },
    {
      key: '/settings',
      icon: <SettingOutlined />,
      label: '系统设置',
      disabled: true,
    },
  ]

  const selectedKey = location.pathname.startsWith('/switches/') 
    ? '/switches' 
    : location.pathname

  const userMenuItems: MenuProps['items'] = [
    {
      key: 'profile',
      icon: <UserOutlined />,
      label: '个人中心',
    },
    {
      type: 'divider',
    },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
    },
  ]

  const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
    navigate(key)
  }

  const handleUserMenuClick: MenuProps['onClick'] = ({ key }) => {
    if (key === 'logout') {
      localStorage.removeItem('userInfo')
      window.location.reload()
    }
  }

  useEffect(() => {
    const userInfo = localStorage.getItem('userInfo')
    if (!userInfo) {
      localStorage.setItem('userInfo', JSON.stringify({
        id: 'user-001',
        name: '管理员',
        email: 'admin@example.com',
        role: 'admin',
      }))
    }
  }, [])

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider 
        trigger={null} 
        collapsible 
        collapsed={collapsed}
        theme="dark"
        width={220}
      >
        <div style={{ 
          height: 64, 
          display: 'flex', 
          alignItems: 'center', 
          justifyContent: 'center',
          color: '#fff',
          fontSize: collapsed ? 14 : 18,
          fontWeight: 'bold',
          letterSpacing: 2,
        }}>
          {collapsed ? 'FF' : 'FeatureFlag'}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={handleMenuClick}
        />
      </Sider>
      <Layout>
        <Header style={{ 
          padding: '0 24px', 
          background: colorBgContainer,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
        }}>
          <div style={{ fontSize: 18, fontWeight: 500 }}>
            功能开关管理平台
          </div>
          <Space>
            <Dropdown menu={{ items: userMenuItems, onClick: handleUserMenuClick }} placement="bottomRight">
              <Space style={{ cursor: 'pointer' }}>
                <Avatar size="small" icon={<UserOutlined />} />
                <span>管理员</span>
              </Space>
            </Dropdown>
          </Space>
        </Header>
        <Content
          style={{
            margin: '24px',
            padding: 24,
            minHeight: 280,
            background: colorBgContainer,
            borderRadius: borderRadiusLG,
          }}
        >
          {children}
        </Content>
      </Layout>
    </Layout>
  )
}

export default AppLayout
