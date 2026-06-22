import { useState, useEffect } from 'react'
import {
  Card,
  Form,
  Input,
  Select,
  Switch,
  InputNumber,
  Button,
  Space,
  Row,
  Col,
  message,
  Divider,
  Typography,
} from 'antd'
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { switchApi, serviceApi } from '@/api'
import type { Service, CreateSwitchRequest, Strategy, WhitelistCondition } from '@/types'
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons'
import { List, Table } from 'antd'

const { TextArea } = Input
const { Option } = Select
const { Title } = Typography

const SwitchCreate: React.FC = () => {
  const navigate = useNavigate()
  const [form] = Form.useForm()
  const [services, setServices] = useState<Service[]>([])
  const [strategies, setStrategies] = useState<Strategy[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    loadServices()
  }, [])

  const loadServices = async () => {
    try {
      const res = await serviceApi.list()
      setServices(res.data || [])
    } catch (err) {
      console.error('Load services error:', err)
    }
  }

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields()
      setLoading(true)

      const request: CreateSwitchRequest = {
        ...values,
        strategies: strategies.length > 0 ? strategies : undefined,
      }

      await switchApi.create(request)
      message.success('创建成功')
      navigate('/switches')
    } catch (err) {
      console.error('Create switch error:', err)
    } finally {
      setLoading(false)
    }
  }

  const addStrategy = () => {
    const newStrategy: Strategy = {
      id: `temp-${Date.now()}`,
      switch_id: '',
      name: `策略 ${strategies.length + 1}`,
      description: '',
      operator: 'AND',
      priority: strategies.length,
      enabled: true,
      conditions: [],
      created_at: '',
      updated_at: '',
    }
    setStrategies([...strategies, newStrategy])
  }

  const removeStrategy = (index: number) => {
    const newStrategies = [...strategies]
    newStrategies.splice(index, 1)
    setStrategies(newStrategies)
  }

  const updateStrategy = (index: number, field: keyof Strategy, value: any) => {
    const newStrategies = [...strategies]
    newStrategies[index] = { ...newStrategies[index], [field]: value }
    setStrategies(newStrategies)
  }

  const addCondition = (strategyIndex: number) => {
    const newStrategies = [...strategies]
    const newCondition: WhitelistCondition = {
      id: `temp-${Date.now()}`,
      strategy_id: newStrategies[strategyIndex].id,
      field: 'USER_ID',
      operator: 'IN',
      values: [],
      created_at: '',
    }
    newStrategies[strategyIndex].conditions = [...(newStrategies[strategyIndex].conditions || []), newCondition]
    setStrategies(newStrategies)
  }

  const removeCondition = (strategyIndex: number, conditionIndex: number) => {
    const newStrategies = [...strategies]
    const conditions = [...newStrategies[strategyIndex].conditions!]
    conditions.splice(conditionIndex, 1)
    newStrategies[strategyIndex].conditions = conditions
    setStrategies(newStrategies)
  }

  const updateCondition = (strategyIndex: number, conditionIndex: number, field: keyof WhitelistCondition, value: any) => {
    const newStrategies = [...strategies]
    const conditions = [...newStrategies[strategyIndex].conditions!]
    conditions[conditionIndex] = { ...conditions[conditionIndex], [field]: value }
    newStrategies[strategyIndex].conditions = conditions
    setStrategies(newStrategies)
  }

  return (
    <div>
      <Card
        title={
          <Space>
            <Button
              icon={<ArrowLeftOutlined />}
              onClick={() => navigate('/switches')}
            />
            <Title level={4} style={{ margin: 0 }}>新建功能开关</Title>
          </Space>
        }
        extra={
          <Space>
            <Button onClick={() => navigate('/switches')}>取消</Button>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={loading}
              onClick={handleSubmit}
            >
              创建
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical">
          <Title level={5}>基本信息</Title>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                name="key"
                label="开关标识Key"
                rules={[
                  { required: true, message: '请输入开关标识' },
                  { pattern: /^[a-zA-Z0-9_.]+$/, message: '只能包含字母、数字、下划线和点' },
                ]}
              >
                <Input placeholder="例如: new_checkout_flow" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="name"
                label="开关名称"
                rules={[{ required: true, message: '请输入开关名称' }]}
              >
                <Input placeholder="例如: 新结算流程" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="service_id"
                label="所属服务"
                rules={[{ required: true, message: '请选择所属服务' }]}
              >
                <Select placeholder="选择服务">
                  {services.map(s => (
                    <Option key={s.id} value={s.id}>{s.name}</Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="type"
                label="开关类型"
                rules={[{ required: true, message: '请选择开关类型' }]}
                initialValue="BOOLEAN"
              >
                <Select>
                  <Option value="BOOLEAN">布尔开关</Option>
                  <Option value="PERCENTAGE">百分比灰度</Option>
                  <Option value="WHITELIST">白名单开关</Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="scope"
                label="作用域"
                rules={[{ required: true, message: '请选择作用域' }]}
                initialValue="GLOBAL"
              >
                <Select>
                  <Option value="GLOBAL">全局</Option>
                  <Option value="ENVIRONMENT">按环境</Option>
                  <Option value="TENANT">按租户</Option>
                </Select>
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="owner"
                label="负责人"
                rules={[{ required: true, message: '请输入负责人' }]}
              >
                <Input placeholder="负责人用户名" />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item
            noStyle
            shouldUpdate={(prev, cur) => prev.type !== cur.type}
          >
            {({ getFieldValue }) => {
              const type = getFieldValue('type')
              return (
                <Row gutter={16}>
                  {type === 'BOOLEAN' && (
                    <Col span={8}>
                      <Form.Item name="boolean_value" label="默认值" initialValue={false} valuePropName="checked">
                        <Switch />
                      </Form.Item>
                    </Col>
                  )}
                  {type === 'PERCENTAGE' && (
                    <Col span={8}>
                      <Form.Item
                        name="percentage_value"
                        label="灰度比例(%)"
                        initialValue={0}
                        rules={[{ required: true, message: '请输入灰度比例' }]}
                      >
                        <InputNumber min={0} max={100} style={{ width: '100%' }} />
                      </Form.Item>
                    </Col>
                  )}
                </Row>
              )
            }}
          </Form.Item>

          <Form.Item
            noStyle
            shouldUpdate={(prev, cur) => prev.scope !== cur.scope}
          >
            {({ getFieldValue }) => {
              const scope = getFieldValue('scope')
              return (
                <Row gutter={16}>
                  {(scope === 'ENVIRONMENT' || scope === 'TENANT') && (
                    <Col span={8}>
                      <Form.Item
                        name="environment"
                        label="环境"
                        rules={[{ required: true, message: '请输入环境' }]}
                      >
                        <Input placeholder="例如: production, staging" />
                      </Form.Item>
                    </Col>
                  )}
                  {scope === 'TENANT' && (
                    <Col span={8}>
                      <Form.Item
                        name="tenant_id"
                        label="租户ID"
                        rules={[{ required: true, message: '请输入租户ID' }]}
                      >
                        <Input placeholder="例如: tenant-001" />
                      </Form.Item>
                    </Col>
                  )}
                </Row>
              )
            }}
          </Form.Item>

          <Form.Item name="description" label="描述">
            <TextArea rows={3} placeholder="描述该开关的用途" />
          </Form.Item>

          <Divider />

          <Title level={5}>高级设置</Title>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item name="require_approval" label="需要审批" valuePropName="checked" initialValue={false}>
                <Switch />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="auto_rollback_enabled" label="自动回滚" valuePropName="checked" initialValue={false}>
                <Switch />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                noStyle
                shouldUpdate={(prev, cur) => prev.auto_rollback_enabled !== cur.auto_rollback_enabled}
              >
                {({ getFieldValue }) => {
                  const enabled = getFieldValue('auto_rollback_enabled')
                  if (!enabled) return null
                  return (
                    <Form.Item name="auto_rollback_threshold" label="错误率阈值(%)" initialValue={5}>
                      <InputNumber min={0} max={100} step={0.1} style={{ width: '100%' }} />
                    </Form.Item>
                  )
                }}
              </Form.Item>
            </Col>
          </Row>

          <Divider />

          <Title level={5}>灰度策略配置</Title>
          <Space style={{ marginBottom: 16 }}>
            <Button type="primary" icon={<PlusOutlined />} onClick={addStrategy}>
              添加策略
            </Button>
            <span style={{ color: '#888' }}>
              策略按优先级顺序执行，条件运算支持 AND/OR
            </span>
          </Space>

          {strategies.length === 0 ? (
            <Card type="inner" style={{ borderStyle: 'dashed', textAlign: 'center', color: '#999' }}>
              暂无策略配置，点击上方按钮添加
            </Card>
          ) : (
            <List
              bordered
              dataSource={strategies}
              renderItem={(strategy, sIndex) => (
                <List.Item
                  extra={
                    <Space>
                      <span style={{ color: '#888' }}>优先级: {strategy.priority}</span>
                      <Button
                        type="text"
                        danger
                        icon={<DeleteOutlined />}
                        onClick={() => removeStrategy(sIndex)}
                      />
                    </Space>
                  }
                >
                  <Card size="small" title={
                    <Space>
                      <Input
                        value={strategy.name}
                        onChange={(e) => updateStrategy(sIndex, 'name', e.target.value)}
                        style={{ width: 200 }}
                        placeholder="策略名称"
                      />
                      <Select
                        value={strategy.operator}
                        onChange={(value) => updateStrategy(sIndex, 'operator', value)}
                        style={{ width: 120 }}
                      >
                        <Option value="AND">且 (AND)</Option>
                        <Option value="OR">或 (OR)</Option>
                      </Select>
                      <span>条件运算</span>
                      <Switch
                        checked={strategy.enabled}
                        onChange={(checked) => updateStrategy(sIndex, 'enabled', checked)}
                      />
                    </Space>
                  }>
                    {strategy.conditions && strategy.conditions.length > 0 ? (
                      <Table
                        size="small"
                        dataSource={strategy.conditions}
                        pagination={false}
                        columns={[
                          {
                            title: '字段',
                            dataIndex: 'field',
                            width: 150,
                            render: (value: string, _, cIndex) => (
                              <Select
                                value={value}
                                onChange={(v) => updateCondition(sIndex, cIndex, 'field', v)}
                                style={{ width: '100%' }}
                              >
                                <Option value="USER_ID">用户ID</Option>
                                <Option value="DEPARTMENT">部门</Option>
                                <Option value="TAG">标签</Option>
                              </Select>
                            ),
                          },
                          {
                            title: '操作符',
                            dataIndex: 'operator',
                            width: 150,
                            render: (value: string, _, cIndex) => (
                              <Select
                                value={value}
                                onChange={(v) => updateCondition(sIndex, cIndex, 'operator', v)}
                                style={{ width: '100%' }}
                              >
                                <Option value="IN">属于</Option>
                                <Option value="NOT_IN">不属于</Option>
                                <Option value="CONTAINS">包含</Option>
                                <Option value="NOT_CONTAINS">不包含</Option>
                              </Select>
                            ),
                          },
                          {
                            title: '值(多个用逗号分隔)',
                            dataIndex: 'values',
                            render: (value: string[], _, cIndex) => (
                              <Input
                                value={value.join(',')}
                                onChange={(e) => updateCondition(sIndex, cIndex, 'values', e.target.value.split(',').filter(Boolean))}
                                placeholder="例如: user1,user2,user3"
                              />
                            ),
                          },
                          {
                            title: '操作',
                            width: 80,
                            render: (_, __, cIndex) => (
                              <Button
                                type="text"
                                danger
                                icon={<DeleteOutlined />}
                                onClick={() => removeCondition(sIndex, cIndex)}
                              />
                            ),
                          },
                        ]}
                      />
                    ) : (
                      <div style={{ color: '#999', textAlign: 'center', padding: 20 }}>
                        暂无条件
                      </div>
                    )}
                    <Button
                      type="dashed"
                      icon={<PlusOutlined />}
                      onClick={() => addCondition(sIndex)}
                      style={{ marginTop: 12, width: '100%' }}
                    >
                      添加条件
                    </Button>
                  </Card>
                </List.Item>
              )}
            />
          )}
        </Form>
      </Card>
    </div>
  )
}

export default SwitchCreate
