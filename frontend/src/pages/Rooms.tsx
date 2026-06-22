import { useState, useEffect } from 'react'
import {
  Card,
  Button,
  Input,
  Select,
  Space,
  Tag,
  Modal,
  Form,
  InputNumber,
  Switch,
  message,
  Popconfirm,
} from 'antd'
import {
  PlusOutlined,
  SearchOutlined,
  EditOutlined,
  DeleteOutlined,
  CalendarOutlined,
  TeamOutlined,
  EnvironmentOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { roomApi } from '@/api'
import type { Room } from '@/types'
import { useAuthStore } from '@/store'

const { Option } = Select

function Rooms() {
  const navigate = useNavigate()
  const user = useAuthStore((state) => state.user)
  const isAdmin = user?.role === 'admin'

  const [rooms, setRooms] = useState<Room[]>([])
  const [loading, setLoading] = useState(false)
  const [searchText, setSearchText] = useState('')
  const [floorFilter, setFloorFilter] = useState<number | undefined>()
  const [modalVisible, setModalVisible] = useState(false)
  const [editingRoom, setEditingRoom] = useState<Room | null>(null)
  const [form] = Form.useForm()

  useEffect(() => {
    loadRooms()
  }, [searchText, floorFilter])

  const loadRooms = async () => {
    setLoading(true)
    try {
      const { data } = await roomApi.list({
        search: searchText || undefined,
        floor: floorFilter,
      })
      setRooms(data)
    } catch (error) {
      message.error('加载会议室列表失败')
    } finally {
      setLoading(false)
    }
  }

  const handleAdd = () => {
    setEditingRoom(null)
    form.resetFields()
    setModalVisible(true)
  }

  const handleEdit = (room: Room) => {
    setEditingRoom(room)
    form.setFieldsValue(room)
    setModalVisible(true)
  }

  const handleDelete = async (id: string) => {
    try {
      await roomApi.delete(id)
      message.success('删除成功')
      loadRooms()
    } catch (error: any) {
      message.error(error.response?.data?.error || '删除失败')
    }
  }

  const handleSubmit = async (values: any) => {
    try {
      if (editingRoom) {
        await roomApi.update(editingRoom.id, values)
        message.success('更新成功')
      } else {
        await roomApi.create(values)
        message.success('创建成功')
      }
      setModalVisible(false)
      loadRooms()
    } catch (error: any) {
      message.error(error.response?.data?.error || '操作失败')
    }
  }

  return (
    <div>
      <Card
        title="会议室列表"
        extra={
          <Space>
            <Input
              placeholder="搜索会议室"
              prefix={<SearchOutlined />}
              value={searchText}
              onChange={(e) => setSearchText(e.target.value)}
              style={{ width: 200 }}
              allowClear
            />
            <Select
              placeholder="按楼层筛选"
              value={floorFilter}
              onChange={setFloorFilter}
              style={{ width: 120 }}
              allowClear
            >
              {[2, 3, 5].map((floor) => (
                <Option key={floor} value={floor}>
                  {floor}楼
                </Option>
              ))}
            </Select>
            {isAdmin && (
              <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
                新增会议室
              </Button>
            )}
          </Space>
        }
      >
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 16 }}>
          {rooms.map((room) => (
            <Card
              key={room.id}
              hoverable
              onClick={() => navigate(`/rooms/${room.id}`)}
              styles={{ body: { padding: 20 } }}
              actions={[
                <CalendarOutlined key="calendar" onClick={(e) => { e.stopPropagation(); navigate(`/rooms/${room.id}`) }} />,
                isAdmin && <EditOutlined key="edit" onClick={(e) => { e.stopPropagation(); handleEdit(room) }} />,
                isAdmin && (
                  <Popconfirm
                    title="确定要删除这个会议室吗？"
                    onConfirm={(e) => { e?.stopPropagation(); handleDelete(room.id) }}
                    onCancel={(e) => e?.stopPropagation()}
                  >
                    <DeleteOutlined
                      key="delete"
                      onClick={(e) => e.stopPropagation()}
                      style={{ color: '#ff4d4f' }}
                    />
                  </Popconfirm>
                ),
              ].filter(Boolean) as any[]}
            >
              <Card.Meta
                title={
                  <Space>
                    {room.name}
                    {room.status === 'inactive' && <Tag color="default">已下架</Tag>}
                    {room.need_approval && <Tag color="orange">需审批</Tag>}
                  </Space>
                }
                description={
                  <div style={{ marginTop: 12 }}>
                    <p style={{ marginBottom: 8 }}>
                      <EnvironmentOutlined style={{ marginRight: 8 }} />
                      {room.location || `${room.floor}楼`}
                    </p>
                    <p style={{ marginBottom: 8 }}>
                      <TeamOutlined style={{ marginRight: 8 }} />
                      容纳 {room.capacity} 人
                    </p>
                    <p style={{ marginBottom: 0, color: '#666', fontSize: 13 }}>
                      {room.equipment || '暂无设备信息'}
                    </p>
                  </div>
                }
              />
            </Card>
          ))}
        </div>
      </Card>

      <Modal
        title={editingRoom ? '编辑会议室' : '新增会议室'}
        open={modalVisible}
        onCancel={() => setModalVisible(false)}
        footer={null}
        width={600}
      >
        <Form form={form} layout="vertical" onFinish={handleSubmit}>
          <Form.Item name="name" label="会议室名称" rules={[{ required: true, message: '请输入会议室名称' }]}>
            <Input placeholder="请输入会议室名称" />
          </Form.Item>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <Form.Item name="floor" label="楼层" rules={[{ required: true, message: '请输入楼层' }]}>
              <InputNumber min={1} style={{ width: '100%' }} placeholder="请输入楼层" />
            </Form.Item>
            <Form.Item name="capacity" label="容纳人数" rules={[{ required: true, message: '请输入容纳人数' }]}>
              <InputNumber min={1} style={{ width: '100%' }} placeholder="请输入容纳人数" />
            </Form.Item>
          </div>
          <Form.Item name="location" label="位置描述">
            <Input placeholder="例如：3楼东侧" />
          </Form.Item>
          <Form.Item name="equipment" label="设备设施">
            <Input.TextArea rows={2} placeholder="请输入设备，用逗号分隔" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} placeholder="请输入会议室描述" />
          </Form.Item>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <Form.Item name="need_approval" label="需要审批" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item name="status" label="状态">
              <Select>
                <Option value="active">上架</Option>
                <Option value="inactive">下架</Option>
              </Select>
            </Form.Item>
          </div>
          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                确定
              </Button>
              <Button onClick={() => setModalVisible(false)}>取消</Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

export default Rooms
