import { useState, useEffect } from 'react'
import {
  Card,
  Descriptions,
  Tag,
  Button,
  Space,
  Tabs,
  Table,
  Form,
  Input,
  Select,
  Switch,
  InputNumber,
  Modal,
  List,
  Statistic,
  Row,
  Col,
  Timeline,
  message,
  Popconfirm,
  Empty,
} from 'antd'
import {
  EditOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  ClockCircleOutlined,
  SaveOutlined,
  PlusOutlined,
  DeleteOutlined,
  BarChartOutlined,
  SendOutlined,
  HistoryOutlined,
} from '@ant-design/icons'
import { useParams, useSearchParams } from 'react-router-dom'
import ReactECharts from 'echarts-for-react'
import dayjs from 'dayjs'
import { switchApi, approvalApi, serviceApi } from '@/api'
import type {
  Switch,
  Strategy,
  WhitelistCondition,
  Service,
  SwitchStats,
  StatsSummary,
  SwitchHistory,
  SwitchIntegration,
  ScheduledTask,
  ScheduleRequest,
  ApprovalRequest,
  StrategyOperator,
  WhitelistField,
  WhitelistOperator,
} from '@/types'

const { TextArea } = Input
const { Option } = Select
const { TabPane } = Tabs

const SwitchDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>()
  const [searchParams, setSearchParams] = useSearchParams()
  const [loading, setLoading] = useState(false)
  const [switchData, setSwitchData] = useState<Switch | null>(null)
  const [services, setServices] = useState<Service[]>([])
  const [editMode, setEditMode] = useState(false)
  const [form] = Form.useForm()
  const [strategies, setStrategies] = useState<Strategy[]>([])
  const [stats, setStats] = useState<SwitchStats[]>([])
  const [statsSummary, setStatsSummary] = useState<StatsSummary | null>(null)
  const [history, setHistory] = useState<SwitchHistory[]>([])
  const [integrations, setIntegrations] = useState<SwitchIntegration[]>([])
  const [schedules, setSchedules] = useState<ScheduledTask[]>([])
  const [scheduleModal, setScheduleModal] = useState(false)
  const [approvalModal, setApprovalModal] = useState(false)

  const defaultTab = searchParams.get('tab') || 'basic'

  useEffect(() => {
    loadServices()
  }, [])

  useEffect(() => {
    if (id) {
      loadData()
    }
  }, [id])

  const loadServices = async () => {
    try {
      const res = await serviceApi.list()
      setServices(res.data || [])
    } catch (err) {
      console.error('Load services error:', err)
    }
  }

  const loadData = async () => {
    if (!id) return
    setLoading(true)
    try {
      const [switchRes, statsRes, summaryRes, historyRes, integrationRes, scheduleRes] = await Promise.all([
        switchApi.getById(id),
        switchApi.getStats(id, dayjs().subtract(30, 'day').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')),
        switchApi.getStatsSummary(id),
        switchApi.getHistory(id, 1, 50),
        switchApi.getIntegrations(id),
        switchApi.listSchedules(id),
      ])

      const sw = switchRes.data!
      setSwitchData(sw)
      setStrategies(sw.strategies || [])
      setStats(statsRes.data || [])
      setStatsSummary(summaryRes.data || null)
      setHistory(historyRes.data?.data || [])
      setIntegrations(integrationRes.data || [])
      setSchedules(scheduleRes.data?.data || [])

      form.setFieldsValue({
        name: sw.name,
        description: sw.description,
        type: sw.type,
        scope: sw.scope,
        service_id: sw.service_id,
        owner: sw.owner,
        boolean_value: sw.boolean_value,
        percentage_value: sw.percentage_value,
        environment: sw.environment,
        tenant_id: sw.tenant_id,
        require_approval: sw.require_approval,
        auto_rollback_enabled: sw.auto_rollback_enabled,
        auto_rollback_threshold: sw.auto_rollback_threshold,
      })
    } catch (err) {
      console.error('Load data error:', err)
    } finally {
      setLoading(false)
    }
  }

  const handleSave = async () => {
    try {
      const values = await form.validateFields()
      await switchApi.update(id!, values)
      message.success('保存成功')
      setEditMode(false)
      loadData()
    } catch (err) {
      console.error('Save error:', err)
    }
  }

  const handleToggle = async (enabled: boolean) => {
    try {
      if (enabled) {
        await switchApi.enable(id!)
        message.success('开关已开启')
      } else {
        await switchApi.disable(id!)
        message.success('开关已关闭')
      }
      loadData()
    } catch (err) {
      console.error('Toggle error:', err)
    }
  }

  const handleSaveStrategies = async () => {
    try {
      await switchApi.saveStrategies(id!, strategies)
      message.success('策略保存成功')
      loadData()
    } catch (err) {
      console.error('Save strategies error:', err)
    }
  }

  const addStrategy = () => {
    const newStrategy: Strategy = {
      id: `temp-${Date.now()}`,
      switch_id: id!,
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

  const handleCreateSchedule = async (values: ScheduleRequest) => {
    try {
      await switchApi.createSchedule(id!, { ...values, switch_id: id! })
      message.success('定时任务创建成功')
      setScheduleModal(false)
      loadData()
    } catch (err) {
      console.error('Create schedule error:', err)
    }
  }

  const handleCreateApproval = async (values: ApprovalRequest) => {
    try {
      await approvalApi.create({ ...values, switch_id: id! })
      message.success('审批申请已提交')
      setApprovalModal(false)
      loadData()
    } catch (err) {
      console.error('Create approval error:', err)
    }
  }

  const getStatusTag = (status: string, enabled: boolean) => {
    if (status === 'ACTIVE' && enabled) return <Tag color="green">运行中</Tag>
    if (status === 'PENDING_APPROVAL') return <Tag color="orange">待审批</Tag>
    if (status === 'SCHEDULED') return <Tag color="blue">定时中</Tag>
    return <Tag color="default">已停用</Tag>
  }

  const getTypeTag = (type: string) => {
    const colors: Record<string, string> = { BOOLEAN: 'blue', PERCENTAGE: 'purple', WHITELIST: 'cyan' }
    const labels: Record<string, string> = { BOOLEAN: '布尔开关', PERCENTAGE: '百分比灰度', WHITELIST: '白名单' }
    return <Tag color={colors[type]}>{labels[type]}</Tag>
  }

  const getScopeTag = (scope: string) => {
    const colors: Record<string, string> = { GLOBAL: 'green', ENVIRONMENT: 'orange', TENANT: 'red' }
    const labels: Record<string, string> = { GLOBAL: '全局', ENVIRONMENT: '按环境', TENANT: '按租户' }
    return <Tag color={colors[scope]}>{labels[scope]}</Tag>
  }

  const getStatsChartOption = () => {
    const dates = stats.map(s => s.date)
    const totalData = stats.map(s => s.total_evaluations)
    const trueData = stats.map(s => s.true_count)
    const falseData = stats.map(s => s.false_count)
    const errorData = stats.map(s => s.error_count)

    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['总评估次数', '通过次数', '拒绝次数', '错误次数'] },
      xAxis: { type: 'category', data: dates },
      yAxis: { type: 'value' },
      series: [
        { name: '总评估次数', type: 'line', smooth: true, data: totalData },
        { name: '通过次数', type: 'line', smooth: true, data: trueData },
        { name: '拒绝次数', type: 'line', smooth: true, data: falseData },
        { name: '错误次数', type: 'line', smooth: true, data: errorData },
      ],
    }
  }

  const getLatencyChartOption = () => {
    const dates = stats.map(s => s.date)
    const avgData = stats.map(s => s.avg_latency_ms)
    const p99Data = stats.map(s => s.p99_latency_ms)

    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['平均延迟', 'P99延迟'] },
      xAxis: { type: 'category', data: dates },
      yAxis: { type: 'value', name: 'ms' },
      series: [
        { name: '平均延迟', type: 'bar', data: avgData },
        { name: 'P99延迟', type: 'bar', data: p99Data },
      ],
    }
  }

  const getFlowRateChartOption = () => {
    const trueCount = statsSummary?.true_count || 0
    const falseCount = statsSummary?.false_count || 0

    return {
      tooltip: { trigger: 'item' },
      legend: { orient: 'vertical', left: 'left' },
      series: [
        {
          name: '流量分布',
          type: 'pie',
          radius: ['40%', '70%'],
          data: [
            { value: trueCount, name: '通过流量', itemStyle: { color: '#52c41a' } },
            { value: falseCount, name: '拒绝流量', itemStyle: { color: '#ff4d4f' } },
          ],
          label: {
            formatter: '{b}: {c} ({d}%)',
          },
        },
      ],
    }
  }

  const getEventTypeLabel = (type: string) => {
    const labels: Record<string, string> = {
      SWITCH_CREATED: '创建开关',
      SWITCH_UPDATED: '更新开关',
      SWITCH_DELETED: '删除开关',
      SWITCH_ENABLED: '开启开关',
      SWITCH_DISABLED: '关闭开关',
      STRATEGY_UPDATED: '更新策略',
      APPROVAL_REQUESTED: '提交审批',
      APPROVAL_APPROVED: '审批通过',
      APPROVAL_REJECTED: '审批拒绝',
      AUTO_ROLLBACK: '自动回滚',
    }
    return labels[type] || type
  }

  const getEventTypeColor = (type: string) => {
    const colors: Record<string, string> = {
      SWITCH_CREATED: 'green',
      SWITCH_ENABLED: 'green',
      APPROVAL_APPROVED: 'green',
      SWITCH_DISABLED: 'red',
      SWITCH_DELETED: 'red',
      AUTO_ROLLBACK: 'red',
      APPROVAL_REJECTED: 'orange',
      SWITCH_UPDATED: 'blue',
      STRATEGY_UPDATED: 'blue',
      APPROVAL_REQUESTED: 'orange',
    }
    return colors[type] || 'default'
  }

  if (!switchData) {
    return <Empty description="加载中..." />
  }

  return (
    <div>
      <Card
        title={
          <Space>
            <span>{switchData.name}</span>
            {getStatusTag(switchData.status, switchData.enabled)}
            {getTypeTag(switchData.type)}
            {getScopeTag(switchData.scope)}
          </Space>
        }
        extra={
          <Space>
            {switchData.status !== 'PENDING_APPROVAL' && (
              <>
                <Switch
                  checked={switchData.enabled}
                  onChange={handleToggle}
                  checkedChildren={<PlayCircleOutlined />}
                  unCheckedChildren={<PauseCircleOutlined />}
                />
                {switchData.require_approval && (
                  <Button icon={<SendOutlined />} onClick={() => setApprovalModal(true)}>
                    提交审批
                  </Button>
                )}
              </>
            )}
            <Button icon={<ClockCircleOutlined />} onClick={() => setScheduleModal(true)}>
              定时任务
            </Button>
            {!editMode ? (
              <Button type="primary" icon={<EditOutlined />} onClick={() => setEditMode(true)}>
                编辑
              </Button>
            ) : (
              <Space>
                <Button onClick={() => setEditMode(false)}>取消</Button>
                <Button type="primary" icon={<SaveOutlined />} onClick={handleSave}>
                  保存
                </Button>
              </Space>
            )}
          </Space>
        }
        loading={loading}
      >
        {!editMode ? (
          <Descriptions column={3} bordered>
            <Descriptions.Item label="开关标识">{switchData.key}</Descriptions.Item>
            <Descriptions.Item label="所属服务">{switchData.service_name}</Descriptions.Item>
            <Descriptions.Item label="负责人">{switchData.owner}</Descriptions.Item>
            <Descriptions.Item label="描述" span={3}>{switchData.description || '-'}</Descriptions.Item>
            {switchData.type === 'BOOLEAN' && (
              <Descriptions.Item label="布尔值">
                <Tag color={switchData.boolean_value ? 'green' : 'default'}>
                  {switchData.boolean_value ? '开启' : '关闭'}
                </Tag>
              </Descriptions.Item>
            )}
            {switchData.type === 'PERCENTAGE' && (
              <Descriptions.Item label="灰度比例">{switchData.percentage_value}%</Descriptions.Item>
            )}
            <Descriptions.Item label="需要审批">
              <Tag color={switchData.require_approval ? 'orange' : 'green'}>
                {switchData.require_approval ? '是' : '否'}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="自动回滚">
              <Tag color={switchData.auto_rollback_enabled ? 'red' : 'green'}>
                {switchData.auto_rollback_enabled ? '开启' : '关闭'}
              </Tag>
            </Descriptions.Item>
            {switchData.auto_rollback_enabled && (
              <Descriptions.Item label="错误率阈值">{switchData.auto_rollback_threshold}%</Descriptions.Item>
            )}
            {switchData.scope === 'ENVIRONMENT' && (
              <Descriptions.Item label="环境">{switchData.environment}</Descriptions.Item>
            )}
            {switchData.scope === 'TENANT' && (
              <>
                <Descriptions.Item label="环境">{switchData.environment}</Descriptions.Item>
                <Descriptions.Item label="租户">{switchData.tenant_id}</Descriptions.Item>
              </>
            )}
            <Descriptions.Item label="创建人">{switchData.created_by}</Descriptions.Item>
            <Descriptions.Item label="创建时间">{dayjs(switchData.created_at).format('YYYY-MM-DD HH:mm:ss')}</Descriptions.Item>
            <Descriptions.Item label="更新时间">{dayjs(switchData.updated_at).format('YYYY-MM-DD HH:mm:ss')}</Descriptions.Item>
          </Descriptions>
        ) : (
          <Form form={form} layout="vertical">
            <Row gutter={16}>
              <Col span={8}>
                <Form.Item name="name" label="开关名称" rules={[{ required: true }]}>
                  <Input />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="type" label="开关类型" rules={[{ required: true }]}>
                  <Select>
                    <Option value="BOOLEAN">布尔开关</Option>
                    <Option value="PERCENTAGE">百分比灰度</Option>
                    <Option value="WHITELIST">白名单</Option>
                  </Select>
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="scope" label="作用域" rules={[{ required: true }]}>
                  <Select>
                    <Option value="GLOBAL">全局</Option>
                    <Option value="ENVIRONMENT">按环境</Option>
                    <Option value="TENANT">按租户</Option>
                  </Select>
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="service_id" label="所属服务" rules={[{ required: true }]}>
                  <Select>
                    {services.map(s => (
                      <Option key={s.id} value={s.id}>{s.name}</Option>
                    ))}
                  </Select>
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="owner" label="负责人" rules={[{ required: true }]}>
                  <Input />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item
                  noStyle
                  shouldUpdate={(prev, cur) => prev.type !== cur.type}
                >
                  {({ getFieldValue }) => {
                    const type = getFieldValue('type')
                    if (type === 'BOOLEAN') {
                      return (
                        <Form.Item name="boolean_value" label="布尔值">
                          <Switch />
                        </Form.Item>
                      )
                    }
                    if (type === 'PERCENTAGE') {
                      return (
                        <Form.Item name="percentage_value" label="灰度比例(%)">
                          <InputNumber min={0} max={100} style={{ width: '100%' }} />
                        </Form.Item>
                      )
                    }
                    return null
                  }}
                </Form.Item>
              </Col>
              <Col span={24}>
                <Form.Item name="description" label="描述">
                  <TextArea rows={3} />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="require_approval" label="需要审批" valuePropName="checked">
                  <Switch />
                </Form.Item>
              </Col>
              <Col span={8}>
                <Form.Item name="auto_rollback_enabled" label="自动回滚" valuePropName="checked">
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
                      <Form.Item name="auto_rollback_threshold" label="错误率阈值(%)">
                        <InputNumber min={0} max={100} step={0.1} style={{ width: '100%' }} />
                      </Form.Item>
                    )
                  }}
                </Form.Item>
              </Col>
            </Row>
          </Form>
        )}
      </Card>

      <Card style={{ marginTop: 16 }}>
        <Tabs defaultActiveKey={defaultTab} onChange={(key) => setSearchParams({ tab: key })}>
          <TabPane tab="灰度策略" key="strategies">
            <Space direction="vertical" style={{ width: '100%' }} size="large">
              <Space>
                <Button type="primary" icon={<PlusOutlined />} onClick={addStrategy}>
                  添加策略
                </Button>
                <Button icon={<SaveOutlined />} onClick={handleSaveStrategies}>
                  保存策略
                </Button>
              </Space>

              {strategies.length === 0 ? (
                <Empty description="暂无策略配置" />
              ) : (
                <List
                  bordered
                  dataSource={strategies}
                  renderItem={(strategy, sIndex) => (
                    <List.Item
                      extra={
                        <Popconfirm
                          title="确定删除这个策略吗？"
                          onConfirm={() => removeStrategy(sIndex)}
                        >
                          <Button type="text" danger icon={<DeleteOutlined />} />
                        </Popconfirm>
                      }
                    >
                      <Card size="small" title={
                        <Space>
                          <Input
                            value={strategy.name}
                            onChange={(e) => updateStrategy(sIndex, 'name', e.target.value)}
                            style={{ width: 200 }}
                          />
                          <Select
                            value={strategy.operator}
                            onChange={(value) => updateStrategy(sIndex, 'operator', value)}
                            style={{ width: 120 }}
                          >
                            <Option value="AND">且 (AND)</Option>
                            <Option value="OR">或 (OR)</Option>
                          </Select>
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
                                render: (value: WhitelistField, _, cIndex) => (
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
                                render: (value: WhitelistOperator, _, cIndex) => (
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
                                title: '值(逗号分隔)',
                                dataIndex: 'values',
                                render: (value: string[], _, cIndex) => (
                                  <Input
                                    value={value.join(',')}
                                    onChange={(e) => updateCondition(sIndex, cIndex, 'values', e.target.value.split(',').filter(Boolean))}
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
                          <Empty description="暂无条件" />
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
            </Space>
          </TabPane>

          <TabPane tab={<span><BarChartOutlined /> 使用统计</span>} key="stats">
            {statsSummary && (
              <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
                <Col span={6}>
                  <Card>
                    <Statistic title="总评估次数" value={statsSummary.total_evaluations} />
                  </Card>
                </Col>
                <Col span={6}>
                  <Card>
                    <Statistic title="通过次数" value={statsSummary.true_count} valueStyle={{ color: '#52c41a' }} />
                  </Card>
                </Col>
                <Col span={6}>
                  <Card>
                    <Statistic title="拒绝次数" value={statsSummary.false_count} valueStyle={{ color: '#faad14' }} />
                  </Card>
                </Col>
                <Col span={6}>
                  <Card>
                    <Statistic title="错误次数" value={statsSummary.error_count} valueStyle={{ color: '#ff4d4f' }} />
                  </Card>
                </Col>
                <Col span={12}>
                  <Card>
                    <Statistic title="平均延迟" value={statsSummary.avg_latency_ms} suffix="ms" />
                  </Card>
                </Col>
                <Col span={12}>
                  <Card>
                    <Statistic title="P99延迟" value={statsSummary.p99_latency_ms} suffix="ms" valueStyle={{ color: '#fa8c16' }} />
                  </Card>
                </Col>
              </Row>
            )}
            <Row gutter={[16, 16]}>
              <Col span={16}>
                <Card title="评估次数趋势">
                  <ReactECharts option={getStatsChartOption()} style={{ height: 300 }} />
                </Card>
              </Col>
              <Col span={8}>
                <Card title="流量分布">
                  <ReactECharts option={getFlowRateChartOption()} style={{ height: 300 }} />
                </Card>
              </Col>
              <Col span={24}>
                <Card title="延迟统计">
                  <ReactECharts option={getLatencyChartOption()} style={{ height: 300 }} />
                </Card>
              </Col>
            </Row>

            <Card title="集成服务" style={{ marginTop: 16 }}>
              {integrations.length === 0 ? (
                <Empty description="暂无服务集成" />
              ) : (
                <List
                  dataSource={integrations}
                  renderItem={(item) => (
                    <List.Item>
                      <List.Item.Meta
                        title={item.service_name}
                        description={`SDK版本: ${item.sdk_version || '未知'} | 最后拉取: ${dayjs(item.last_poll_at).format('YYYY-MM-DD HH:mm:ss')}`}
                      />
                    </List.Item>
                  )}
                />
              )}
            </Card>
          </TabPane>

          <TabPane tab={<span><HistoryOutlined /> 变更历史</span>} key="history">
            <Timeline
              mode="left"
              items={history.map(h => ({
                color: getEventTypeColor(h.event_type),
                label: dayjs(h.created_at).format('YYYY-MM-DD HH:mm:ss'),
                children: (
                  <Card size="small">
                    <Space direction="vertical" size="small" style={{ width: '100%' }}>
                      <Space>
                        <Tag color={getEventTypeColor(h.event_type)}>
                          {getEventTypeLabel(h.event_type)}
                        </Tag>
                        <span>操作人: {h.operator_user}</span>
                      </Space>
                      {h.remark && <div>{h.remark}</div>}
                    </Space>
                  </Card>
                ),
              }))}
            />
          </TabPane>

          <TabPane tab={<span><ClockCircleOutlined /> 定时任务</span>} key="schedules">
            {schedules.length === 0 ? (
              <Empty description="暂无定时任务" />
            ) : (
              <Table
                dataSource={schedules}
                rowKey="id"
                columns={[
                  { title: '任务类型', dataIndex: 'task_type' },
                  {
                    title: '目标状态',
                    dataIndex: 'target_enabled',
                    render: (v) => <Tag color={v ? 'green' : 'default'}>{v ? '开启' : '关闭'}</Tag>,
                  },
                  { title: '执行时间', dataIndex: 'execute_at', render: (v) => dayjs(v).format('YYYY-MM-DD HH:mm:ss') },
                  { title: '状态', dataIndex: 'status', render: (v) => {
                    const colors: Record<string, string> = { PENDING: 'blue', SUCCESS: 'green', FAILED: 'red' }
                    return <Tag color={colors[v] || 'default'}>{v}</Tag>
                  }},
                  { title: '执行时间', dataIndex: 'executed_at', render: (v) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
                  { title: '错误信息', dataIndex: 'error_message' },
                  { title: '创建人', dataIndex: 'created_by' },
                ]}
              />
            )}
          </TabPane>
        </Tabs>
      </Card>

      <Modal
        title="创建定时任务"
        open={scheduleModal}
        onCancel={() => setScheduleModal(false)}
        footer={null}
      >
        <Form layout="vertical" onFinish={handleCreateSchedule}>
          <Form.Item name="task_type" label="任务类型" rules={[{ required: true }]} initialValue="TOGGLE">
            <Select>
              <Option value="TOGGLE">切换开关状态</Option>
            </Select>
          </Form.Item>
          <Form.Item name="target_enabled" label="目标状态" rules={[{ required: true }]} initialValue={true}>
            <Select>
              <Option value={true}>开启</Option>
              <Option value={false}>关闭</Option>
            </Select>
          </Form.Item>
          <Form.Item name="execute_at" label="执行时间" rules={[{ required: true }]}>
            <Input type="datetime-local" />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button onClick={() => setScheduleModal(false)}>取消</Button>
              <Button type="primary" htmlType="submit">创建</Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="提交审批"
        open={approvalModal}
        onCancel={() => setApprovalModal(false)}
        footer={null}
      >
        <Form layout="vertical" onFinish={handleCreateApproval}>
          <Form.Item name="title" label="审批标题" rules={[{ required: true }]}>
            <Input placeholder="请输入审批标题" />
          </Form.Item>
          <Form.Item name="description" label="审批说明">
            <TextArea rows={3} placeholder="请输入审批说明" />
          </Form.Item>
          <Form.Item name="approver" label="审批人" rules={[{ required: true }]}>
            <Input placeholder="请输入审批人用户名" />
          </Form.Item>
          <Form.Item name="target_enabled" label="目标状态" rules={[{ required: true }]} initialValue={true}>
            <Select>
              <Option value={true}>开启</Option>
              <Option value={false}>关闭</Option>
            </Select>
          </Form.Item>
          <Form.Item>
            <Space>
              <Button onClick={() => setApprovalModal(false)}>取消</Button>
              <Button type="primary" htmlType="submit">提交</Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}

export default SwitchDetail
