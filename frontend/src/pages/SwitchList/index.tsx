import { useState, useEffect, useRef } from 'react'
import {
  Table,
  Button,
  Input,
  Select,
  Space,
  Tag,
  Modal,
  Popconfirm,
  message,
  Switch,
  Card,
  Row,
  Col,
  Statistic,
  Tooltip,
} from 'antd'
import {
  PlusOutlined,
  SearchOutlined,
  ReloadOutlined,
  EditOutlined,
  DeleteOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  HistoryOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import type { ColumnsType } from 'antd/es/table'
import dayjs from 'dayjs'
import { switchApi, serviceApi, approvalApi } from '@/api'
import type { Switch, Service, ListRequest, BatchOperationRequest } from '@/types'

const { Search } = Input
const { Option } = Select

const SwitchList: React.FC = () => {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [data, setData] = useState<Switch[]>([])
  const [total, setTotal] = useState(0)
  const [services, setServices] = useState<Service[]>([])
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([])
  const [filters, setFilters] = useState<ListRequest>({
    page: 1,
    page_size: 20,
  })
  const [stats, setStats] = useState({
    total: 0,
    active: 0,
    inactive: 0,
    pending: 0,
  })

  useEffect(() => {
    loadServices()
  }, [])

  useEffect(() => {
    loadData()
  }, [filters])

  const loadServices = async () => {
    try {
      const res = await serviceApi.list()
      setServices(res.data || [])
    } catch (err) {
      console.error('Load services error:', err)
    }
  }

  const loadData = async () => {
    setLoading(true)
    try {
      const res = await switchApi.list(filters)
      if (res.data) {
        setData(res.data.data || [])
        setTotal(res.data.pagination.total || 0)

        const switches = res.data.data || []
        setStats({
          total: res.data.pagination.total || 0,
          active: switches.filter(s => s.status === 'ACTIVE' && s.enabled).length,
          inactive: switches.filter(s => s.status === 'INACTIVE' || !s.enabled).length,
          pending: switches.filter(s => s.status === 'PENDING_APPROVAL').length,
        })
      }
    } catch (err) {
      console.error('Load switches error:', err)
    } finally {
      setLoading(false)
    }
  }

  const handleToggle = async (id: string, enabled: boolean) => {
    try {
      if (enabled) {
        await switchApi.enable(id)
        message.success('开关已开启')
      } else {
        await switchApi.disable(id)
        message.success('开关已关闭')
      }
      loadData()
    } catch (err) {
      console.error('Toggle switch error:', err)
    }
  }

  const handleDelete = async (id: string) => {
    try {
      await switchApi.delete(id)
      message.success('删除成功')
      loadData()
    } catch (err) {
      console.error('Delete switch error:', err)
    }
  }

  const handleBatchEnable = async () => {
    if (selectedRowKeys.length === 0) {
      message.warning('请先选择要操作的开关')
      return
    }
    try {
      await switchApi.batchEnable({
        ids: selectedRowKeys as string[],
        operation: 'enable',
      })
      message.success(`已批量开启 ${selectedRowKeys.length} 个开关`)
      setSelectedRowKeys([])
      loadData()
    } catch (err) {
      console.error('Batch enable error:', err)
    }
  }

  const handleBatchDisable = async () => {
    if (selectedRowKeys.length === 0) {
      message.warning('请先选择要操作的开关')
      return
    }
    try {
      await switchApi.batchDisable({
        ids: selectedRowKeys as string[],
        operation: 'disable',
      })
      message.success(`已批量关闭 ${selectedRowKeys.length} 个开关`)
      setSelectedRowKeys([])
      loadData()
    } catch (err) {
      console.error('Batch disable error:', err)
    }
  }

  const handleBatchDisableByService = () => {
    Modal.confirm({
      title: '按服务批量操作',
      content: (
        <div>
          <p>选择要操作的服务：</p>
          <Select
            style={{ width: '100%' }}
            placeholder="选择服务"
            options={services.map(s => ({ label: s.name, value: s.id }))}
            onSelect={async (serviceId) => {
              try {
                await switchApi.batchDisableByService({
                  service_id: serviceId,
                  operation: 'disable',
                })
                message.success('已批量关闭该服务的所有开关')
                loadData()
                Modal.destroyAll()
              } catch (err) {
                console.error('Batch disable by service error:', err)
              }
            }}
          />
        </div>
      ),
      okText: '确认关闭',
      cancelText: '取消',
    })
  }

  const getStatusTag = (status: string, enabled: boolean) => {
    if (status === 'ACTIVE' && enabled) {
      return <Tag color="green">运行中</Tag>
    }
    if (status === 'PENDING_APPROVAL') {
      return <Tag color="orange">待审批</Tag>
    }
    if (status === 'SCHEDULED') {
      return <Tag color="blue">定时中</Tag>
    }
    return <Tag color="default">已停用</Tag>
  }

  const getTypeTag = (type: string) => {
    const colors: Record<string, string> = {
      BOOLEAN: 'blue',
      PERCENTAGE: 'purple',
      WHITELIST: 'cyan',
    }
    const labels: Record<string, string> = {
      BOOLEAN: '布尔开关',
      PERCENTAGE: '百分比灰度',
      WHITELIST: '白名单',
    }
    return <Tag color={colors[type]}>{labels[type]}</Tag>
  }

  const getScopeTag = (scope: string) => {
    const colors: Record<string, string> = {
      GLOBAL: 'green',
      ENVIRONMENT: 'orange',
      TENANT: 'red',
    }
    const labels: Record<string, string> = {
      GLOBAL: '全局',
      ENVIRONMENT: '按环境',
      TENANT: '按租户',
    }
    return <Tag color={colors[scope]}>{labels[scope]}</Tag>
  }

  const columns: ColumnsType<Switch> = [
    {
      title: '开关名称',
      dataIndex: 'name',
      key: 'name',
      width: 180,
      render: (text, record) => (
        <a onClick={() => navigate(`/switches/${record.id}`)}>
          {text}
        </a>
      ),
    },
    {
      title: '标识Key',
      dataIndex: 'key',
      key: 'key',
      width: 200,
      render: (text) => <code style={{ background: '#f5f5f5', padding: '2px 6px', borderRadius: 4 }}>{text}</code>,
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 100,
      render: (text) => getTypeTag(text),
    },
    {
      title: '作用域',
      dataIndex: 'scope',
      key: 'scope',
      width: 100,
      render: (text) => getScopeTag(text),
    },
    {
      title: '所属服务',
      dataIndex: 'service_name',
      key: 'service_name',
      width: 120,
    },
    {
      title: '负责人',
      dataIndex: 'owner',
      key: 'owner',
      width: 100,
    },
    {
      title: '灰度比例',
      dataIndex: 'percentage_value',
      key: 'percentage_value',
      width: 100,
      render: (value, record) => {
        if (record.type === 'PERCENTAGE') {
          return `${value}%`
        }
        if (record.type === 'BOOLEAN') {
          return record.boolean_value ? '开启' : '关闭'
        }
        return '-'
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (text, record) => getStatusTag(text, record.enabled),
    },
    {
      title: '开关控制',
      key: 'toggle',
      width: 100,
      render: (_, record) => (
        <Switch
          checked={record.enabled}
          onChange={(checked) => handleToggle(record.id, checked)}
          disabled={record.status === 'PENDING_APPROVAL'}
        />
      ),
    },
    {
      title: '更新时间',
      dataIndex: 'updated_at',
      key: 'updated_at',
      width: 160,
      render: (text) => dayjs(text).format('YYYY-MM-DD HH:mm:ss'),
    },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          <Tooltip title="编辑">
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => navigate(`/switches/${record.id}`)}
            />
          </Tooltip>
          <Tooltip title="历史">
            <Button
              type="text"
              size="small"
              icon={<HistoryOutlined />}
              onClick={() => navigate(`/switches/${record.id}?tab=history`)}
            />
          </Tooltip>
          <Popconfirm
            title="确定要删除这个开关吗？"
            onConfirm={() => handleDelete(record.id)}
            okText="确定"
            cancelText="取消"
          >
            <Button type="text" size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic
              title="开关总数"
              value={stats.total}
              valueStyle={{ color: '#1890ff' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="运行中"
              value={stats.active}
              valueStyle={{ color: '#52c41a' }}
              prefix={<PlayCircleOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="已停用"
              value={stats.inactive}
              valueStyle={{ color: '#8c8c8c' }}
              prefix={<PauseCircleOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="待审批"
              value={stats.pending}
              valueStyle={{ color: '#fa8c16' }}
              prefix={<SettingOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <Card>
        <Space direction="vertical" style={{ width: '100%' }} size="large">
          <Space wrap style={{ width: '100%' }}>
            <Search
              placeholder="搜索开关名称、Key、描述"
              allowClear
              enterButton={<SearchOutlined />}
              size="middle"
              style={{ width: 300 }}
              onSearch={(value) => setFilters({ ...filters, keyword: value, page: 1 })}
              onChange={(e) => !e.target.value && setFilters({ ...filters, keyword: '', page: 1 })}
            />
            <Select
              placeholder="选择服务"
              allowClear
              style={{ width: 150 }}
              onChange={(value) => setFilters({ ...filters, service_id: value, page: 1 })}
            >
              {services.map(s => (
                <Option key={s.id} value={s.id}>{s.name}</Option>
              ))}
            </Select>
            <Select
              placeholder="选择状态"
              allowClear
              style={{ width: 120 }}
              onChange={(value) => setFilters({ ...filters, status: value, page: 1 })}
            >
              <Option value="ACTIVE">运行中</Option>
              <Option value="INACTIVE">已停用</Option>
              <Option value="PENDING_APPROVAL">待审批</Option>
              <Option value="DRAFT">草稿</Option>
            </Select>
            <Select
              placeholder="选择类型"
              allowClear
              style={{ width: 120 }}
              onChange={(value) => setFilters({ ...filters, type: value, page: 1 })}
            >
              <Option value="BOOLEAN">布尔开关</Option>
              <Option value="PERCENTAGE">百分比灰度</Option>
              <Option value="WHITELIST">白名单</Option>
            </Select>
            <Select
              placeholder="选择作用域"
              allowClear
              style={{ width: 120 }}
              onChange={(value) => setFilters({ ...filters, scope: value, page: 1 })}
            >
              <Option value="GLOBAL">全局</Option>
              <Option value="ENVIRONMENT">按环境</Option>
              <Option value="TENANT">按租户</Option>
            </Select>
            <Input
              placeholder="负责人"
              allowClear
              style={{ width: 120 }}
              onChange={(e) => setFilters({ ...filters, owner: e.target.value, page: 1 })}
            />
            <Button
              icon={<ReloadOutlined />}
              onClick={() => {
                setFilters({ page: 1, page_size: 20 })
                setSelectedRowKeys([])
              }}
            >
              重置
            </Button>
          </Space>

          <Space wrap>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => navigate('/switches/create')}
            >
              新建开关
            </Button>
            <Button
              onClick={handleBatchEnable}
              disabled={selectedRowKeys.length === 0}
            >
              批量开启
            </Button>
            <Button
              onClick={handleBatchDisable}
              disabled={selectedRowKeys.length === 0}
            >
              批量关闭
            </Button>
            <Button onClick={handleBatchDisableByService}>
              按服务批量关闭
            </Button>
          </Space>

          <Table
            rowKey="id"
            loading={loading}
            columns={columns}
            dataSource={data}
            pagination={{
              current: filters.page,
              pageSize: filters.page_size,
              total: total,
              showSizeChanger: true,
              showQuickJumper: true,
              showTotal: (total) => `共 ${total} 条`,
              onChange: (page, pageSize) => setFilters({ ...filters, page, page_size: pageSize }),
            }}
            rowSelection={{
              selectedRowKeys,
              onChange: setSelectedRowKeys,
            }}
            scroll={{ x: 1400 }}
          />
        </Space>
      </Card>
    </div>
  )
}

export default SwitchList
