import { Layout, Menu, Avatar, Dropdown, Badge, Space } from 'antd'
import {
  AppstoreOutlined,
  ScheduleOutlined,
  FileTextOutlined,
  CheckSquareOutlined,
  BarChartOutlined,
  BellOutlined,
  SettingOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons'
import { useNavigate, useLocation, Outlet } from 'react-router-dom'
import { useAuthStore } from '@/store'
import { useEffect, useState } from 'react'
import { notificationApi } from '@/api'

const { Header, Sider, Content } = Layout

function MainLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const user = useAuthStore((state) => state.user)
  const logout = useAuthStore((state) => state.logout)
  const [unreadCount, setUnreadCount] = useState(0)

  useEffect(() => {
    loadUnreadCount()
    const timer = setInterval(loadUnreadCount, 30000)
    return () => clearInterval(timer)
  }, [])

  const loadUnreadCount = async () => {
    try {
      const { data } = await notificationApi.list({ status: 'unread' })
      setUnreadCount(data.length)
    } catch (e) {}
  }

  const menuItems = [
    { key: '/rooms', icon: <AppstoreOutlined />, label: '会议室管理' },
    { key: '/my-bookings', icon: <ScheduleOutlined />, label: '我的预约' },
    { key: '/my-todos', icon: <CheckSquareOutlined />, label: '我的待办' },
    { key: '/statistics', icon: <BarChartOutlined />, label: '数据统计' },
    { key: '/notifications', icon: <BellOutlined />, label: '消息通知' },
    { key: '/settings', icon: <SettingOutlined />, label: '系统设置' },
  ]

  const handleMenuClick = (key: string) => {
    navigate(key)
  }

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  const userMenuItems = [
    { key: 'profile', icon: <UserOutlined />, label: '个人信息' },
    { key: 'settings', icon: <SettingOutlined />, label: '设置' },
    { type: 'divider' as const },
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: handleLogout },
  ]

  const getSelectedKey = () => {
    if (location.pathname.startsWith('/rooms/')) return '/rooms'
    return location.pathname
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="light" width={220}>
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          <h2 style={{ margin: 0, color: '#1677ff', fontSize: 18, fontWeight: 600 }}>
            会议室预约系统
          </h2>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[getSelectedKey()]}
          items={menuItems}
          onClick={({ key }) => handleMenuClick(key)}
          style={{ borderRight: 'none', padding: '8px 0' }}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          <div style={{ fontSize: 18, fontWeight: 500 }}>
            {menuItems.find((item) => item.key === getSelectedKey())?.label || '会议室预约系统'}
          </div>
          <Space size={20}>
            <Badge count={unreadCount} size="small">
              <BellOutlined
                style={{ fontSize: 18, cursor: 'pointer' }}
                onClick={() => navigate('/notifications')}
              />
            </Badge>
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <Space style={{ cursor: 'pointer' }}>
                <Avatar icon={<UserOutlined />} src={user?.avatar} />
                <span>{user?.name || '用户'}</span>
              </Space>
            </Dropdown>
          </Space>
        </Header>
        <Content style={{ padding: 24, background: '#f5f5f5' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}

export default MainLayout
