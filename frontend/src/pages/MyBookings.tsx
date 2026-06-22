import { useState, useEffect } from 'react'
import {
  Card,
  Button,
  Space,
  Tag,
  Table,
  Select,
  Modal,
  message,
  Popconfirm,
} from 'antd'
import { CalendarOutlined, DeleteOutlined, EditOutlined, EyeOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { bookingApi } from '@/api'
import type { Booking } from '@/types'

const { Option } = Select

function MyBookings() {
  const navigate = useNavigate()
  const [bookings, setBookings] = useState<Booking[]>([])
  const [loading, setLoading] = useState(false)
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [cancelModalVisible, setCancelModalVisible] = useState(false)
  const [selectedBooking, setSelectedBooking] = useState<Booking | null>(null)

  useEffect(() => {
    loadBookings()
  }, [statusFilter])

  const loadBookings = async () => {
    setLoading(true)
    try {
      const { data } = await bookingApi.myBookings(statusFilter || undefined)
      setBookings(data)
    } catch (error) {
      message.error('加载预约列表失败')
    } finally {
      setLoading(false)
    }
  }

  const handleCancel = async (id: string) => {
    try {
      await bookingApi.cancel(id)
      message.success('取消成功')
      loadBookings()
    } catch (error: any) {
      message.error(error.response?.data?.error || '取消失败')
    }
  }

  const getStatusTag = (status: string, approvalStatus: string) => {
    if (status === 'cancelled') {
      return <Tag color="default">已取消</Tag>
    }
    if (approvalStatus === 'pending') {
      return <Tag color="orange">待审批</Tag>
    }
    if (approvalStatus === 'rejected') {
      return <Tag color="red">已拒绝</Tag>
    }
    if (dayjs().isAfter(dayjs(status === 'confirmed' ? '' : ''))) {
      // 简单判断
    }
    return <Tag color="green">已确认</Tag>
  }

  const columns = [
    {
      title: '会议主题',
      dataIndex: 'title',
      key: 'title',
      render: (text: string, record: Booking) => (
        <a onClick={() => navigate(`/meeting-docs/${record.id}`)}>{text}</a>
      ),
    },
    {
      title: '会议室',
      dataIndex: ['room', 'name'],
      key: 'room',
      render: (text: string, record: Booking) => (
        <a onClick={() => navigate(`/rooms/${record.room_id}`)}>{text}</a>
      ),
    },
    {
      title: '时间',
      key: 'time',
      render: (_: any, record: Booking) => (
        <div>
          <div>{dayjs(record.start_time).format('YYYY-MM-DD HH:mm')}</div>
          <div style={{ color: '#999', fontSize: 12 }}>
            至 {dayjs(record.end_time).format('HH:mm')}
          </div>
        </div>
      ),
      sorter: (a: Booking, b: Booking) => dayjs(a.start_time).valueOf() - dayjs(b.start_time).valueOf(),
    },
    {
      title: '状态',
      key: 'status',
      render: (_: any, record: Booking) => getStatusTag(record.status, record.approval_status),
    },
    {
      title: '周期',
      dataIndex: 'recurring_rule',
      key: 'recurring',
      render: (rule: string) => {
        if (!rule) return <Tag color="default">单次</Tag>
        const map: Record<string, string> = {
          daily: '每天',
          weekly: '每周',
          biweekly: '每两周',
          monthly: '每月',
        }
        return <Tag color="blue">{map[rule] || rule}</Tag>
      },
    },
    {
      title: '操作',
      key: 'actions',
      render: (_: any, record: Booking) => (
        <Space size="small">
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => navigate(`/meeting-docs/${record.id}`)}>
            纪要
          </Button>
          {record.status === 'confirmed' && (
            <Popconfirm
              title="确定要取消这个预约吗？"
              onConfirm={() => handleCancel(record.id)}
            >
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                取消
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  return (
    <Card
      title="我的预约"
      extra={
        <Select
          placeholder="状态筛选"
          value={statusFilter || undefined}
          onChange={setStatusFilter}
          style={{ width: 140 }}
          allowClear
        >
          <Option value="confirmed">已确认</Option>
          <Option value="pending">待审批</Option>
          <Option value="cancelled">已取消</Option>
        </Select>
      }
    >
      <Table
        dataSource={bookings}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={{ pageSize: 10 }}
      />
    </Card>
  )
}

export default MyBookings
