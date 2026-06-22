import { useState, useEffect } from 'react'
import {
  Card,
  Button,
  Space,
  Tabs,
  Input,
  List,
  Tag,
  Modal,
  Form,
  Select,
  DatePicker,
  message,
  Popconfirm,
  Avatar,
} from 'antd'
import {
  ArrowLeftOutlined,
  FileTextOutlined,
  EditOutlined,
  SaveOutlined,
  CheckSquareOutlined,
  UserOutlined,
  PlusOutlined,
  DeleteOutlined,
  CheckOutlined,
  ClockCircleOutlined,
  CalendarOutlined,
  InboxOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { meetingDocApi, todoApi, userApi, bookingApi, checkInApi } from '@/api'
import type { MeetingDoc, Todo, User, Booking, CheckIn } from '@/types'

const { TextArea } = Input
const { Option } = Select

function MeetingDoc() {
  const { bookingId } = useParams<{ bookingId: string }>()
  const navigate = useNavigate()
  const [doc, setDoc] = useState<MeetingDoc | null>(null)
  const [booking, setBooking] = useState<Booking | null>(null)
  const [todos, setTodos] = useState<Todo[]>([])
  const [users, setUsers] = useState<User[]>([])
  const [agenda, setAgenda] = useState('')
  const [content, setContent] = useState('')
  const [summary, setSummary] = useState('')
  const [editing, setEditing] = useState(false)
  const [activeTab, setActiveTab] = useState('agenda')
  const [todoModalVisible, setTodoModalVisible] = useState(false)
  const [checkIns, setCheckIns] = useState<CheckIn[]>([])
  const [form] = Form.useForm()

  useEffect(() => {
    if (bookingId) {
      loadBooking()
      loadDoc()
      loadUsers()
      loadCheckIns()
    }
  }, [bookingId])

  const loadBooking = async () => {
    try {
      const { data } = await bookingApi.get(bookingId!)
      setBooking(data)
    } catch (error) {
      message.error('加载预约信息失败')
    }
  }

  const loadDoc = async () => {
    try {
      const { data } = await meetingDocApi.getByBooking(bookingId!)
      setDoc(data)
      setAgenda(data.agenda || '')
      setContent(data.content || '')
      setSummary(data.summary || '')
      if (data.id) {
        loadTodos(data.id)
      }
    } catch (error) {
      message.error('加载会议文档失败')
    }
  }

  const loadTodos = async (docId: string) => {
    try {
      const { data } = await todoApi.listByDoc(docId)
      setTodos(data)
    } catch (error) {
      message.error('加载待办列表失败')
    }
  }

  const loadUsers = async () => {
    try {
      const { data } = await userApi.list()
      setUsers(data)
    } catch (error) {
      // ignore
    }
  }

  const loadCheckIns = async () => {
    try {
      const { data } = await checkInApi.getCheckInList(bookingId!)
      setCheckIns(data)
    } catch (error) {
      // ignore
    }
  }

  const handleSave = async () => {
    if (!doc) return
    try {
      await meetingDocApi.update(doc.id, { agenda, content, summary })
      message.success('保存成功')
      setEditing(false)
      loadDoc()
    } catch (error: any) {
      message.error(error.response?.data?.error || '保存失败')
    }
  }

  const handleArchive = async () => {
    if (!doc) return
    try {
      await meetingDocApi.archive(doc.id)
      message.success('纪要已归档')
      loadDoc()
    } catch (error: any) {
      message.error(error.response?.data?.error || '归档失败')
    }
  }

  const handleAddTodo = async (values: any) => {
    if (!doc) return
    try {
      await todoApi.create(doc.id, {
        content: values.content,
        assignee_id: values.assignee_id,
        due_date: values.due_date?.format('YYYY-MM-DD'),
        priority: values.priority || 1,
      })
      message.success('添加成功')
      setTodoModalVisible(false)
      loadTodos(doc.id)
    } catch (error: any) {
      message.error(error.response?.data?.error || '添加失败')
    }
  }

  const handleTodoStatusChange = async (todoId: string, status: string) => {
    try {
      await todoApi.update(todoId, { status })
      loadTodos(doc!.id)
    } catch (error) {
      message.error('更新失败')
    }
  }

  const handleDeleteTodo = async (todoId: string) => {
    try {
      await todoApi.delete(todoId)
      message.success('删除成功')
      loadTodos(doc!.id)
    } catch (error: any) {
      message.error(error.response?.data?.error || '删除失败')
    }
  }

  const tabItems = [
    { key: 'agenda', label: '议程', icon: <FileTextOutlined /> },
    { key: 'content', label: '会议纪要', icon: <EditOutlined /> },
    { key: 'todos', label: `待办事项 (${todos.length})`, icon: <CheckSquareOutlined /> },
    { key: 'checkin', label: `签到 (${checkIns.length})`, icon: <InboxOutlined /> },
    { key: 'summary', label: '摘要', icon: <FileTextOutlined /> },
  ]

  return (
    <div>
      <Card
        title={
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/my-bookings')}>
              返回
            </Button>
            {booking?.title || '会议文档'}
          </Space>
        }
        extra={
          <Space>
            <Tag icon={<CalendarOutlined />}>
              {booking?.room?.name}
            </Tag>
            <Tag icon={<ClockCircleOutlined />}>
              {dayjs(booking?.start_time).format('YYYY-MM-DD HH:mm')} - {dayjs(booking?.end_time).format('HH:mm')}
            </Tag>
            {!doc?.is_archived && (
              <>
                {editing ? (
                  <Button type="primary" icon={<SaveOutlined />} onClick={handleSave}>
                    保存
                  </Button>
                ) : (
                  <Button icon={<EditOutlined />} onClick={() => setEditing(true)}>
                    编辑
                  </Button>
                )}
                <Popconfirm title="确定要归档吗？归档后将无法编辑。" onConfirm={handleArchive}>
                  <Button type="primary" ghost>
                    归档
                  </Button>
                </Popconfirm>
              </>
            )}
            {doc?.is_archived && <Tag color="default">已归档</Tag>}
          </Space>
        }
      >
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={tabItems}
        />

        {activeTab === 'agenda' && (
          <div style={{ padding: '16px 0' }}>
            {editing && !doc?.is_archived ? (
              <TextArea
                value={agenda}
                onChange={(e) => setAgenda(e.target.value)}
                rows={12}
                placeholder="请输入会议议程，支持 Markdown 格式"
              />
            ) : (
              <div className="markdown-content" style={{ padding: '8px 0' }}>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                  {agenda || '暂无议程'}
                </ReactMarkdown>
              </div>
            )}
          </div>
        )}

        {activeTab === 'content' && (
          <div style={{ padding: '16px 0' }}>
            {editing && !doc?.is_archived ? (
              <TextArea
                value={content}
                onChange={(e) => setContent(e.target.value)}
                rows={20}
                placeholder="请输入会议纪要，支持 Markdown 格式。使用 '- [ ] 待办内容' 格式添加待办事项。"
              />
            ) : (
              <div className="markdown-content" style={{ padding: '8px 0' }}>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                  {content || '暂无会议纪要'}
                </ReactMarkdown>
              </div>
            )}
          </div>
        )}

        {activeTab === 'todos' && (
          <div style={{ padding: '16px 0' }}>
            <div style={{ marginBottom: 16, textAlign: 'right' }}>
              {!doc?.is_archived && (
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={() => {
                    form.resetFields()
                    setTodoModalVisible(true)
                  }}
                >
                  添加待办
                </Button>
              )}
            </div>
            <List
              dataSource={todos}
              locale={{ emptyText: '暂无待办事项' }}
              renderItem={(todo) => (
                <List.Item
                  key={todo.id}
                  actions={[
                    todo.status !== 'completed' && !doc?.is_archived ? (
                      <Button
                        type="link"
                        size="small"
                        icon={<CheckOutlined />}
                        onClick={() => handleTodoStatusChange(todo.id, 'completed')}
                      >
                        完成
                      </Button>
                    ) : null,
                    !doc?.is_archived ? (
                      <Popconfirm title="确定删除？" onConfirm={() => handleDeleteTodo(todo.id)}>
                        <Button
                          type="link"
                          size="small"
                          danger
                          icon={<DeleteOutlined />}
                        >
                          删除
                        </Button>
                      </Popconfirm>
                    ) : null,
                  ].filter(Boolean) as any[]}
                >
                  <List.Item.Meta
                    avatar={<Avatar icon={<UserOutlined />} src={todo.assignee?.avatar} />}
                    title={
                      <Space>
                        <span style={{ textDecoration: todo.status === 'completed' ? 'line-through' : 'none', color: todo.status === 'completed' ? '#999' : 'inherit' }}>
                          {todo.content}
                        </span>
                        {todo.status === 'completed' && <Tag color="green">已完成</Tag>}
                        {todo.priority > 1 && <Tag color="red">高优先级</Tag>}
                      </Space>
                    }
                    description={
                      <Space size="middle">
                        <span>负责人：{todo.assignee?.name}</span>
                        {todo.due_date && (
                          <span>截止：{dayjs(todo.due_date).format('YYYY-MM-DD')}</span>
                        )}
                      </Space>
                    }
                  />
                </List.Item>
              )}
            />
          </div>
        )}

        {activeTab === 'checkin' && (
          <div style={{ padding: '16px 0' }}>
            <List
              dataSource={checkIns}
              locale={{ emptyText: '暂无签到记录' }}
              renderItem={(checkin) => (
                <List.Item key={checkin.id}>
                  <List.Item.Meta
                    avatar={<Avatar icon={<UserOutlined />} src={checkin.user?.avatar} />}
                    title={checkin.user?.name}
                    description={`签到时间：${dayjs(checkin.check_in_at).format('YYYY-MM-DD HH:mm:ss')}`}
                  />
                </List.Item>
              )}
            />
          </div>
        )}

        {activeTab === 'summary' && (
          <div style={{ padding: '16px 0' }}>
            {editing && !doc?.is_archived ? (
              <TextArea
                value={summary}
                onChange={(e) => setSummary(e.target.value)}
                rows={6}
                placeholder="请输入会议摘要"
              />
            ) : (
              <div className="markdown-content" style={{ padding: '8px 0' }}>
                <p>{summary || '暂无摘要'}</p>
              </div>
            )}
          </div>
        )}
      </Card>

      <Modal
        title="添加待办"
        open={todoModalVisible}
        onCancel={() => setTodoModalVisible(false)}
        footer={null}
      >
        <Form form={form} layout="vertical" onFinish={handleAddTodo}>
          <Form.Item name="content" label="待办内容" rules={[{ required: true, message: '请输入待办内容' }]}>
            <TextArea rows={3} placeholder="请输入待办事项内容" />
          </Form.Item>
          <Form.Item name="assignee_id" label="负责人" rules={[{ required: true, message: '请选择负责人' }]}>
            <Select placeholder="选择负责人">
              {users.map((user) => (
                <Option key={user.id} value={user.id}>
                  {user.name} ({user.department})
                </Option>
              ))}
            </Select>
          </Form.Item>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <Form.Item name="due_date" label="截止日期">
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="priority" label="优先级" initialValue={1}>
              <Select>
                <Option value={1}>普通</Option>
                <Option value={2}>重要</Option>
                <Option value={3}>紧急</Option>
              </Select>
            </Form.Item>
          </div>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                添加
              </Button>
              <Button onClick={() => setTodoModalVisible(false)}>取消</Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

export default MeetingDoc
