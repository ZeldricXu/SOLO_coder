import { useState, useEffect } from 'react'
import {
  Card,
  List,
  Tag,
  Button,
  Space,
  Tabs,
  Empty,
  message,
} from 'antd'
import {
  BellOutlined,
  CheckOutlined,
  CalendarOutlined,
  FileTextOutlined,
  CheckSquareOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { notificationApi } from '@/api'
import type { Notification } from '@/types'

function Notifications() {
  const navigate = useNavigate()
  const [notifications, setNotifications] = useState<Notification[]>([])
  const [loading, setLoading] = useState(false)
  const [activeTab, setActiveTab] = useState('all')

  useEffect(() => {
    loadNotifications()
  }, [activeTab])

  const loadNotifications = async () => {
    setLoading(true)
    try {
      const params: any = {}
      if (activeTab === 'unread') {
        params.status = 'unread'
      }
      const { data } = await notificationApi.list(params)
      setNotifications(data)
    } catch (error) {
      message.error('加载通知列表失败')
    } finally {
      setLoading(false)
    }
  }

  const handleMarkRead = async (id: string) => {
    try {
      await notificationApi.markRead(id)
      loadNotifications()
    } catch (error: any) {
      message.error(error.response?.data?.error || '操作失败')
    }
  }

  const handleMarkAllRead = async () => {
    try {
      await notificationApi.markAllRead()
      message.success('已全部标为已读')
      loadNotifications()
    } catch (error: any) {
      message.error(error.response?.data?.error || '操作失败')
    }
  }

  const getTypeIcon = (type: string) => {
    switch (type) {
      case 'booking_confirm':
        return <CalendarOutlined style={{ color: '#52c41a' }} />
      case 'upcoming_remind':
        return <ClockCircleOutlined style={{ color: '#fa8c16' }} />
      case 'minutes_release':
        return <FileTextOutlined style={{ color: '#1677ff' }} />
      case 'todo_assign':
        return <CheckSquareOutlined style={{ color: '#722ed1' }} />
      default:
        return <BellOutlined />
    }
  }

  const getTypeName = (type: string) => {
    const map: Record<string, string> = {
      booking_confirm: '预订确认',
      upcoming_remind: '会议提醒',
      minutes_release: '纪要发布',
      todo_assign: '待办分配',
    }
    return map[type] || type
  }

  const tabItems = [
    { key: 'all', label: '全部' },
    { key: 'unread', label: '未读' },
    { key: 'booking_confirm', label: '预订确认' },
    { key: 'upcoming_remind', label: '会议提醒' },
    { key: 'minutes_release', label: '纪要发布' },
    { key: 'todo_assign', label: '待办分配' },
  ]

  return (
    <Card
      title="消息通知"
      extra={
        <Button onClick={handleMarkAllRead}>
          <CheckOutlined /> 全部标为已读
        </Button>
      }
    >
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={tabItems}
      />

      <List
        dataSource={notifications}
        loading={loading}
        locale={{ emptyText: <Empty description="暂无通知" /> }}
        renderItem={(item) => (
          <List.Item
            key={item.id}
            style={{
              background: item.status === 'unread' ? '#f0f7ff' : '#fff',
              padding: '16px',
              marginBottom: 8,
              borderRadius: 8,
              cursor: 'pointer',
            }}
            onClick={() => {
              if (item.status === 'unread') {
                handleMarkRead(item.id)
              }
              if (item.booking_id) {
                navigate(`/meeting-docs/${item.booking_id}`)
              }
            }}
          >
            <List.Item.Meta
              avatar={
                <div
                  style={{
                    width: 40,
                    height: 40,
                    borderRadius: 20,
                    background: '#f0f5ff',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: 20,
                  }}
                >
                  {getTypeIcon(item.type)}
                </div>
              }
              title={
                <Space>
                  <span style={{ fontWeight: item.status === 'unread' ? 600 : 400 }}>
                    {item.title}
                  </span>
                  <Tag color={item.status === 'unread' ? 'blue' : 'default'}>
                    {getTypeName(item.type)}
                  </Tag>
                  {item.status === 'unread' && <Tag color="red">新</Tag>}
                </Space>
              }
              description={
                <div>
                  <p style={{ marginBottom: 4, color: '#666' }}>{item.content}</p>
                  <span style={{ color: '#999', fontSize: 12 }}>
                    {dayjs(item.created_at).format('YYYY-MM-DD HH:mm')}
                  </span>
                </div>
              }
            />
          </List.Item>
        )}
      />
    </Card>
  )
}

export default Notifications
