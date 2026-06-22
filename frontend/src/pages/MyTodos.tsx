import { useState, useEffect } from 'react'
import { Card, List, Tag, Select, Radio, Button, message, Popconfirm, Avatar, Space } from 'antd'
import { CheckOutlined, ClockCircleOutlined, DeleteOutlined, UserOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { todoApi } from '@/api'
import type { Todo } from '@/types'

function MyTodos() {
  const [todos, setTodos] = useState<Todo[]>([])
  const [loading, setLoading] = useState(false)
  const [statusFilter, setStatusFilter] = useState<string>('')

  useEffect(() => {
    loadTodos()
  }, [statusFilter])

  const loadTodos = async () => {
    setLoading(true)
    try {
      const { data } = await todoApi.myTodos(statusFilter || undefined)
      setTodos(data)
    } catch (error) {
      message.error('加载待办列表失败')
    } finally {
      setLoading(false)
    }
  }

  const handleComplete = async (id: string) => {
    try {
      await todoApi.update(id, { status: 'completed' })
      message.success('已完成')
      loadTodos()
    } catch (error: any) {
      message.error(error.response?.data?.error || '操作失败')
    }
  }

  const handleDelete = async (id: string) => {
    try {
      await todoApi.delete(id)
      message.success('删除成功')
      loadTodos()
    } catch (error: any) {
      message.error(error.response?.data?.error || '删除失败')
    }
  }

  const getPriorityTag = (priority: number) => {
    switch (priority) {
      case 3:
        return <Tag color="red">紧急</Tag>
      case 2:
        return <Tag color="orange">重要</Tag>
      default:
        return <Tag color="default">普通</Tag>
    }
  }

  const stats = {
    total: todos.length,
    pending: todos.filter((t) => t.status === 'pending').length,
    completed: todos.filter((t) => t.status === 'completed').length,
  }

  return (
    <div>
      <Card
        title="我的待办"
        extra={
          <Space>
            <Radio.Group value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} buttonStyle="solid">
              <Radio.Button value="">全部 ({stats.total})</Radio.Button>
              <Radio.Button value="pending">待完成 ({stats.pending})</Radio.Button>
              <Radio.Button value="completed">已完成 ({stats.completed})</Radio.Button>
            </Radio.Group>
          </Space>
        }
      >
        <List
          dataSource={todos}
          loading={loading}
          locale={{ emptyText: '暂无待办事项' }}
          renderItem={(todo) => (
            <List.Item
              key={todo.id}
              actions={[
                todo.status !== 'completed' ? (
                  <Button
                    type="link"
                    size="small"
                    icon={<CheckOutlined />}
                    onClick={() => handleComplete(todo.id)}
                  >
                    完成
                  </Button>
                ) : null,
                <Popconfirm title="确定删除？" onConfirm={() => handleDelete(todo.id)}>
                  <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>,
              ].filter(Boolean) as any[]}
            >
              <List.Item.Meta
                avatar={<Avatar icon={<UserOutlined />} src={todo.assignee?.avatar} />}
                title={
                  <Space>
                    <span
                      style={{
                        textDecoration: todo.status === 'completed' ? 'line-through' : 'none',
                        color: todo.status === 'completed' ? '#999' : 'inherit',
                      }}
                    >
                      {todo.content}
                    </span>
                    {getPriorityTag(todo.priority)}
                    {todo.status === 'completed' && <Tag color="green">已完成</Tag>}
                  </Space>
                }
                description={
                  <Space size="middle">
                    {todo.due_date && (
                      <span>
                        <ClockCircleOutlined style={{ marginRight: 4 }} />
                        截止：{dayjs(todo.due_date).format('YYYY-MM-DD')}
                        {dayjs(todo.due_date).isBefore(dayjs()) && todo.status !== 'completed' && (
                          <span style={{ color: '#ff4d4f', marginLeft: 8 }}>已逾期</span>
                        )}
                      </span>
                    )}
                    <span style={{ color: '#999' }}>
                      创建于 {dayjs(todo.created_at).format('YYYY-MM-DD')}
                    </span>
                  </Space>
                }
              />
            </List.Item>
          )}
        />
      </Card>
    </div>
  )
}

export default MyTodos
