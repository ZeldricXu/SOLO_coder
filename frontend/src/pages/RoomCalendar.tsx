import { useState, useEffect, useMemo } from 'react'
import {
  Card,
  Button,
  Space,
  Tag,
  Modal,
  Form,
  Input,
  DatePicker,
  Select,
  message,
  Tooltip,
  Radio,
} from 'antd'
import {
  ArrowLeftOutlined,
  PlusOutlined,
  LeftOutlined,
  RightOutlined,
  CalendarOutlined,
  UserOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { roomApi, bookingApi } from '@/api'
import type { Room, Booking } from '@/types'

const { RangePicker } = DatePicker
const { Option } = Select
const { TextArea } = Input

type ViewType = 'day' | 'week' | 'month'

function RoomCalendar() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [room, setRoom] = useState<Room | null>(null)
  const [bookings, setBookings] = useState<Booking[]>([])
  const [currentDate, setCurrentDate] = useState(dayjs())
  const [viewType, setViewType] = useState<ViewType>('week')
  const [modalVisible, setModalVisible] = useState(false)
  const [selectedTime, setSelectedTime] = useState<{ start: dayjs.Dayjs; end: dayjs.Dayjs } | null>(null)
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (id) {
      loadRoom()
      loadBookings()
    }
  }, [id, currentDate, viewType])

  const loadRoom = async () => {
    try {
      const { data } = await roomApi.get(id!)
      setRoom(data)
    } catch (error) {
      message.error('加载会议室信息失败')
    }
  }

  const loadBookings = async () => {
    if (!id) return
    setLoading(true)
    try {
      let start, end
      if (viewType === 'day') {
        start = currentDate.format('YYYY-MM-DD')
        end = currentDate.add(1, 'day').format('YYYY-MM-DD')
      } else if (viewType === 'week') {
        start = currentDate.startOf('week').format('YYYY-MM-DD')
        end = currentDate.endOf('week').add(1, 'day').format('YYYY-MM-DD')
      } else {
        start = currentDate.startOf('month').format('YYYY-MM-DD')
        end = currentDate.endOf('month').add(1, 'day').format('YYYY-MM-DD')
      }
      const { data } = await roomApi.calendar(id, start, end)
      setBookings(data)
    } catch (error) {
      message.error('加载预约数据失败')
    } finally {
      setLoading(false)
    }
  }

  const handleTimeSlotClick = (date: dayjs.Dayjs, hour: number) => {
    const start = date.hour(hour).minute(0).second(0)
    const end = start.add(1, 'hour')
    setSelectedTime({ start, end })
    form.setFieldsValue({
      title: '',
      description: '',
      timeRange: [start, end],
    })
    setModalVisible(true)
  }

  const handleBooking = async (values: any) => {
    if (!selectedTime && !values.timeRange) {
      message.error('请选择时间')
      return
    }

    const startTime = values.timeRange ? values.timeRange[0] : selectedTime?.start
    const endTime = values.timeRange ? values.timeRange[1] : selectedTime?.end

    try {
      await bookingApi.create({
        room_id: id!,
        title: values.title,
        description: values.description,
        start_time: startTime.format('YYYY-MM-DD HH:mm:ss'),
        end_time: endTime.format('YYYY-MM-DD HH:mm:ss'),
        recurring_rule: values.recurring_rule,
      })
      message.success('预约成功')
      setModalVisible(false)
      loadBookings()
    } catch (error: any) {
      message.error(error.response?.data?.error || '预约失败')
    }
  }

  const getTitleText = () => {
    if (viewType === 'day') {
      return currentDate.format('YYYY年M月D日 dddd')
    } else if (viewType === 'week') {
      const start = currentDate.startOf('week')
      const end = currentDate.endOf('week')
      return `${start.format('YYYY年M月D日')} - ${end.format('M月D日')}`
    } else {
      return currentDate.format('YYYY年M月')
    }
  }

  const navigateDate = (direction: number) => {
    if (viewType === 'day') {
      setCurrentDate(currentDate.add(direction, 'day'))
    } else if (viewType === 'week') {
      setCurrentDate(currentDate.add(direction, 'week'))
    } else {
      setCurrentDate(currentDate.add(direction, 'month'))
    }
  }

  const getBookingsForDate = (date: dayjs.Dayjs) => {
    return bookings.filter((b) => {
      const bookingStart = dayjs(b.start_time)
      const bookingEnd = dayjs(b.end_time)
      return date.isSame(bookingStart, 'day') || date.isSame(bookingEnd, 'day') ||
        (date.isAfter(bookingStart.startOf('day')) && date.isBefore(bookingEnd.startOf('day')))
    })
  }

  const renderDayView = () => {
    const hours = Array.from({ length: 14 }, (_, i) => i + 7)
    const dateBookings = getBookingsForDate(currentDate)

    return (
      <div style={{ background: '#fff', borderRadius: 8, overflow: 'hidden', border: '1px solid #e8e8e8' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '80px 1fr' }}>
          <div style={{ background: '#fafafa', padding: '12px 0', borderRight: '1px solid #e8e8e8', fontWeight: 500 }}>
            时间
          </div>
          <div style={{ padding: '12px 16px', fontWeight: 500, background: '#fafafa' }}>
            {room?.name}
          </div>
        </div>
        {hours.map((hour) => {
          const hourBookings = dateBookings.filter((b) => {
            const start = dayjs(b.start_time)
            const end = dayjs(b.end_time)
            return start.hour() < hour + 1 && end.hour() >= hour
          })

          return (
            <div
              key={hour}
              style={{
                display: 'grid',
                gridTemplateColumns: '80px 1fr',
                borderTop: '1px solid #f0f0f0',
                minHeight: 60,
              }}
              onClick={() => handleTimeSlotClick(currentDate, hour)}
            >
              <div
                style={{
                  padding: '8px 12px',
                  fontSize: 13,
                  color: '#999',
                  background: '#fafafa',
                  borderRight: '1px solid #e8e8e8',
                }}
              >
                {hour}:00
              </div>
              <div style={{ position: 'relative', padding: 4 }}>
                {hourBookings.map((booking) => {
                  const start = dayjs(booking.start_time)
                  const end = dayjs(booking.end_time)
                  const top = (start.minute() / 60) * 60
                  const height = ((end.hour() - start.hour()) * 60 + end.minute() - start.minute()) / 60 * 60 - 8

                  return (
                    <Tooltip
                      key={booking.id}
                      title={
                        <div>
                          <div style={{ fontWeight: 500 }}>{booking.title}</div>
                          <div style={{ fontSize: 12 }}>
                            {start.format('HH:mm')} - {end.format('HH:mm')}
                          </div>
                          <div style={{ fontSize: 12 }}>组织者：{booking.user?.name}</div>
                        </div>
                      }
                    >
                      <div
                        style={{
                          position: 'absolute',
                          left: 4,
                          right: 4,
                          top: top + 4,
                          height: Math.max(height, 24),
                          background: booking.approval_status === 'pending' ? '#faad14' : '#1677ff',
                          color: '#fff',
                          padding: '4px 8px',
                          borderRadius: 4,
                          fontSize: 12,
                          overflow: 'hidden',
                          cursor: 'pointer',
                        }}
                        onClick={(e) => {
                          e.stopPropagation()
                          navigate(`/meeting-docs/${booking.id}`)
                        }}
                      >
                        <div style={{ fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {booking.title}
                        </div>
                      </div>
                    </Tooltip>
                  )
                })}
              </div>
            </div>
          )
        })}
      </div>
    )
  }

  const renderWeekView = () => {
    const days = Array.from({ length: 7 }, (_, i) => currentDate.startOf('week').add(i, 'day'))
    const hours = Array.from({ length: 12 }, (_, i) => i + 8)

    return (
      <div style={{ background: '#fff', borderRadius: 8, overflow: 'hidden', border: '1px solid #e8e8e8' }}>
        <div style={{ display: 'grid', gridTemplateColumns: '80px repeat(7, 1fr)', background: '#fafafa' }}>
          <div style={{ padding: '12px 0', textAlign: 'center', borderRight: '1px solid #e8e8e8', fontWeight: 500 }}>
            时间
          </div>
          {days.map((day) => (
            <div
              key={day.format('YYYY-MM-DD')}
              style={{
                padding: '12px 8px',
                textAlign: 'center',
                fontWeight: 500,
                background: day.isToday() ? '#e6f4ff' : undefined,
                borderRight: '1px solid #e8e8e8',
              }}
            >
              <div style={{ fontSize: 12, color: '#999' }}>{day.format('ddd')}</div>
              <div style={{ fontSize: 16 }}>{day.format('D')}</div>
            </div>
          ))}
        </div>
        {hours.map((hour) => (
          <div
            key={hour}
            style={{ display: 'grid', gridTemplateColumns: '80px repeat(7, 1fr)', borderTop: '1px solid #f0f0f0' }}
          >
            <div
              style={{
                padding: '8px',
                fontSize: 12,
                color: '#999',
                textAlign: 'center',
                background: '#fafafa',
                borderRight: '1px solid #e8e8e8',
              }}
            >
              {hour}:00
            </div>
            {days.map((day) => {
              const dayBookings = getBookingsForDate(day).filter((b) => {
                const start = dayjs(b.start_time)
                const end = dayjs(b.end_time)
                return start.hour() < hour + 1 && end.hour() >= hour
              })

              return (
                <div
                  key={day.format('YYYY-MM-DD') + hour}
                  style={{
                    borderRight: '1px solid #f0f0f0',
                    minHeight: 40,
                    padding: 2,
                    cursor: 'pointer',
                    position: 'relative',
                  }}
                  onClick={() => handleTimeSlotClick(day, hour)}
                >
                  {dayBookings.slice(0, 2).map((booking) => (
                    <Tooltip
                      key={booking.id}
                      title={
                        <div>
                          <div style={{ fontWeight: 500 }}>{booking.title}</div>
                          <div style={{ fontSize: 12 }}>
                            {dayjs(booking.start_time).format('HH:mm')} - {dayjs(booking.end_time).format('HH:mm')}
                          </div>
                        </div>
                      }
                    >
                      <div
                        style={{
                          background: booking.approval_status === 'pending' ? '#faad14' : '#1677ff',
                          color: '#fff',
                          padding: '2px 6px',
                          borderRadius: 3,
                          fontSize: 11,
                          marginBottom: 2,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                        onClick={(e) => {
                          e.stopPropagation()
                          navigate(`/meeting-docs/${booking.id}`)
                        }}
                      >
                        {booking.title}
                      </div>
                    </Tooltip>
                  ))}
                  {dayBookings.length > 2 && (
                    <div style={{ fontSize: 11, color: '#999', textAlign: 'center' }}>
                      +{dayBookings.length - 2} 更多
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        ))}
      </div>
    )
  }

  const renderMonthView = () => {
    const daysInMonth = currentDate.daysInMonth()
    const firstDay = currentDate.startOf('month').day()
    const days = Array.from({ length: 42 }, (_, i) => {
      return currentDate.startOf('month').subtract(firstDay, 'day').add(i, 'day')
    })

    return (
      <div style={{ background: '#fff', borderRadius: 8, overflow: 'hidden', border: '1px solid #e8e8e8' }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', background: '#fafafa' }}>
          {['周日', '周一', '周二', '周三', '周四', '周五', '周六'].map((day) => (
            <div key={day} style={{ padding: '12px', textAlign: 'center', fontWeight: 500, borderRight: '1px solid #e8e8e8' }}>
              {day}
            </div>
          ))}
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)' }}>
          {days.map((day, idx) => {
            const isCurrentMonth = day.month() === currentDate.month()
            const isToday = day.isToday()
            const dayBookings = getBookingsForDate(day)

            return (
              <div
                key={idx}
                style={{
                  minHeight: 100,
                  padding: 8,
                  borderRight: idx % 7 !== 6 ? '1px solid #f0f0f0' : 'none',
                  borderBottom: '1px solid #f0f0f0',
                  background: isToday ? '#e6f4ff' : isCurrentMonth ? '#fff' : '#fafafa',
                  cursor: 'pointer',
                }}
                onClick={() => {
                  setCurrentDate(day)
                  setViewType('day')
                }}
              >
                <div style={{ fontSize: 14, fontWeight: isToday ? 600 : 400, color: isCurrentMonth ? '#333' : '#ccc' }}>
                  {day.format('D')}
                </div>
                <div style={{ marginTop: 4 }}>
                  {dayBookings.slice(0, 3).map((booking) => (
                    <Tooltip key={booking.id} title={booking.title}>
                      <div
                        style={{
                          fontSize: 11,
                          padding: '2px 6px',
                          marginBottom: 2,
                          background: booking.approval_status === 'pending' ? '#fff7e6' : '#e6f4ff',
                          color: booking.approval_status === 'pending' ? '#fa8c16' : '#1677ff',
                          borderRadius: 3,
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                        onClick={(e) => {
                          e.stopPropagation()
                          navigate(`/meeting-docs/${booking.id}`)
                        }}
                      >
                        {dayjs(booking.start_time).format('HH:mm')} {booking.title}
                      </div>
                    </Tooltip>
                  ))}
                  {dayBookings.length > 3 && (
                    <div style={{ fontSize: 11, color: '#999' }}>+{dayBookings.length - 3} 更多</div>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      </div>
    )
  }

  return (
    <div>
      <Card
        title={
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/rooms')}>
              返回
            </Button>
            {room?.name}
            {room?.need_approval && <Tag color="orange">需审批</Tag>}
            <span style={{ color: '#999', fontSize: 14, fontWeight: 'normal' }}>
              {room?.floor}楼 · {room?.capacity}人 · {room?.equipment}
            </span>
          </Space>
        }
        extra={
          <Space>
            <Radio.Group value={viewType} onChange={(e) => setViewType(e.target.value)}>
              <Radio.Button value="day">日</Radio.Button>
              <Radio.Button value="week">周</Radio.Button>
              <Radio.Button value="month">月</Radio.Button>
            </Radio.Group>
            <Button icon={<LeftOutlined />} onClick={() => navigateDate(-1)} />
            <Button onClick={() => setCurrentDate(dayjs())}>今天</Button>
            <Button icon={<RightOutlined />} onClick={() => navigateDate(1)} />
            <span style={{ fontWeight: 500, minWidth: 200, textAlign: 'center' }}>{getTitleText()}</span>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => {
              setSelectedTime(null)
              form.resetFields()
              setModalVisible(true)
            }}>
              新建预约
            </Button>
          </Space>
        }
      >
        {viewType === 'day' && renderDayView()}
        {viewType === 'week' && renderWeekView()}
        {viewType === 'month' && renderMonthView()}
      </Card>

      <Modal
        title="新建预约"
        open={modalVisible}
        onCancel={() => setModalVisible(false)}
        footer={null}
        width={500}
      >
        <Form form={form} layout="vertical" onFinish={handleBooking}>
          <Form.Item name="title" label="会议主题" rules={[{ required: true, message: '请输入会议主题' }]}>
            <Input placeholder="请输入会议主题" />
          </Form.Item>
          <Form.Item name="timeRange" label="预约时间" rules={[{ required: true, message: '请选择时间' }]}>
            <RangePicker
              showTime={{ format: 'HH:mm', minuteStep: 15 }}
              format="YYYY-MM-DD HH:mm"
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item name="recurring_rule" label="周期会议">
            <Select placeholder="不重复">
              <Option value="">不重复</Option>
              <Option value="daily">每天</Option>
              <Option value="weekly">每周</Option>
              <Option value="biweekly">每两周</Option>
              <Option value="monthly">每月</Option>
            </Select>
          </Form.Item>
          <Form.Item name="description" label="会议描述">
            <TextArea rows={3} placeholder="请输入会议描述" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                确认预约
              </Button>
              <Button onClick={() => setModalVisible(false)}>取消</Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

export default RoomCalendar
