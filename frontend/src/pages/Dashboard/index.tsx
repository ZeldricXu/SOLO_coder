import { useState, useEffect } from 'react'
import {
  Card,
  Row,
  Col,
  Statistic,
  Table,
  Tag,
  List,
  Empty,
  Progress,
} from 'antd'
import {
  SwitcherOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  SettingOutlined,
  RiseOutlined,
  FallOutlined,
  FireOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons'
import ReactECharts from 'echarts-for-react'
import dayjs from 'dayjs'
import { switchApi, approvalApi } from '@/api'
import type { Switch, SwitchStats, Approval } from '@/types'

const Dashboard: React.FC = () => {
  const [loading, setLoading] = useState(false)
  const [stats, setStats] = useState({
    totalSwitches: 0,
    activeSwitches: 0,
    inactiveSwitches: 0,
    pendingApprovals: 0,
    totalEvaluations: 0,
    totalServices: 4,
  })
  const [recentSwitches, setRecentSwitches] = useState<Switch[]>([])
  const [pendingApprovals, setPendingApprovals] = useState<Approval[]>([])
  const [topSwitches, setTopSwitches] = useState<Switch[]>([])

  useEffect(() => {
    loadData()
  }, [])

  const loadData = async () => {
    setLoading(true)
    try {
      const [switchRes, approvalRes] = await Promise.all([
        switchApi.list({ page: 1, page_size: 100 }),
        approvalApi.list({ status: 'PENDING', page: 1, page_size: 10 }),
      ])

      const switches = switchRes.data?.data || []
      const active = switches.filter(s => s.status === 'ACTIVE' && s.enabled).length
      const inactive = switches.filter(s => s.status === 'INACTIVE' || !s.enabled).length
      const pending = approvalRes.data?.data || []

      setStats({
        totalSwitches: switchRes.data?.pagination.total || 0,
        activeSwitches: active,
        inactiveSwitches: inactive,
        pendingApprovals: pending.length,
        totalEvaluations: 1234567,
        totalServices: 4,
      })

      setRecentSwitches(switches.slice(0, 10))
      setPendingApprovals(pending)
      setTopSwitches(switches.slice(0, 5))
    } catch (err) {
      console.error('Load dashboard data error:', err)
    } finally {
      setLoading(false)
    }
  }

  const getTypeTrendOption = () => {
    const days = []
    for (let i = 29; i >= 0; i--) {
      days.push(dayjs().subtract(i, 'day').format('MM-DD'))
    }

    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['布尔开关', '百分比灰度', '白名单'] },
      xAxis: { type: 'category', data: days },
      yAxis: { type: 'value' },
      series: [
        {
          name: '布尔开关',
          type: 'line',
          smooth: true,
          data: days.map(() => Math.floor(Math.random() * 50) + 20),
          itemStyle: { color: '#1890ff' },
        },
        {
          name: '百分比灰度',
          type: 'line',
          smooth: true,
          data: days.map(() => Math.floor(Math.random() * 30) + 10),
          itemStyle: { color: '#722ed1' },
        },
        {
          name: '白名单',
          type: 'line',
          smooth: true,
          data: days.map(() => Math.floor(Math.random() * 20) + 5),
          itemStyle: { color: '#13c2c2' },
        },
      ],
    }
  }

  const getTypeDistributionOption = () => {
    return {
      tooltip: { trigger: 'item' },
      legend: { orient: 'vertical', left: 'left' },
      series: [
        {
          name: '开关类型分布',
          type: 'pie',
          radius: ['40%', '70%'],
          data: [
            { value: stats.totalSwitches * 0.5, name: '布尔开关', itemStyle: { color: '#1890ff' } },
            { value: stats.totalSwitches * 0.3, name: '百分比灰度', itemStyle: { color: '#722ed1' } },
            { value: stats.totalSwitches * 0.2, name: '白名单', itemStyle: { color: '#13c2c2' } },
          ],
          label: {
            formatter: '{b}: {c} ({d}%)',
          },
        },
      ],
    }
  }

  const getScopeDistributionOption = () => {
    return {
      tooltip: { trigger: 'item' },
      legend: { orient: 'vertical', left: 'left' },
      series: [
        {
          name: '作用域分布',
          type: 'pie',
          radius: ['40%', '70%'],
          data: [
            { value: stats.totalSwitches * 0.6, name: '全局', itemStyle: { color: '#52c41a' } },
            { value: stats.totalSwitches * 0.25, name: '按环境', itemStyle: { color: '#fa8c16' } },
            { value: stats.totalSwitches * 0.15, name: '按租户', itemStyle: { color: '#f5222d' } },
          ],
          label: {
            formatter: '{b}: {c} ({d}%)',
          },
        },
      ],
    }
  }

  const getStatusTag = (status: string, enabled: boolean) => {
    if (status === 'ACTIVE' && enabled) return <Tag color="green">运行中</Tag>
    if (status === 'PENDING_APPROVAL') return <Tag color="orange">待审批</Tag>
    return <Tag color="default">已停用</Tag>
  }

  return (
    <div>
      <Row gutter={[16, 16]}>
        <Col span={6}>
          <Card>
            <Statistic
              title="开关总数"
              value={stats.totalSwitches}
              prefix={<SwitcherOutlined />}
              valueStyle={{ color: '#1890ff' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="运行中"
              value={stats.activeSwitches}
              prefix={<PlayCircleOutlined />}
              valueStyle={{ color: '#52c41a' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="已停用"
              value={stats.inactiveSwitches}
              prefix={<PauseCircleOutlined />}
              valueStyle={{ color: '#8c8c8c' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="待审批"
              value={stats.pendingApprovals}
              prefix={<SettingOutlined />}
              valueStyle={{ color: '#fa8c16' }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col span={16}>
          <Card title="开关类型趋势" loading={loading}>
            <ReactECharts option={getTypeTrendOption()} style={{ height: 300 }} />
          </Card>
        </Col>
        <Col span={8}>
          <Card title="今日评估次数" loading={loading}>
            <Statistic
              title="今日评估"
              value={stats.totalEvaluations}
              prefix={<RiseOutlined />}
              valueStyle={{ color: '#1890ff' }}
            />
            <div style={{ marginTop: 16 }}>
              <p>成功率: 99.8%</p>
              <Progress percent={99.8} status="success" />
            </div>
            <div style={{ marginTop: 16 }}>
              <p>错误率: 0.2%</p>
              <Progress percent={0.2} status="exception" />
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col span={8}>
          <Card title="开关类型分布" loading={loading}>
            <ReactECharts option={getTypeDistributionOption()} style={{ height: 300 }} />
          </Card>
        </Col>
        <Col span={8}>
          <Card title="作用域分布" loading={loading}>
            <ReactECharts option={getScopeDistributionOption()} style={{ height: 300 }} />
          </Card>
        </Col>
        <Col span={8}>
          <Card title="最近创建" loading={loading}>
            {recentSwitches.length === 0 ? (
              <Empty description="暂无数据" />
            ) : (
              <List
                dataSource={recentSwitches.slice(0, 8)}
                renderItem={(item) => (
                  <List.Item>
                    <List.Item.Meta
                      title={item.name}
                      description={
                        <span>
                          <code style={{ fontSize: 12, color: '#666' }}>{item.key}</code>
                          <span style={{ marginLeft: 8 }}>{getStatusTag(item.status, item.enabled)}</span>
                        </span>
                      }
                    />
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col span={12}>
          <Card title="待处理审批" loading={loading}>
            {pendingApprovals.length === 0 ? (
              <Empty description="暂无待审批" />
            ) : (
              <List
                dataSource={pendingApprovals}
                renderItem={(item) => (
                  <List.Item>
                    <List.Item.Meta
                      avatar={<ClockCircleOutlined style={{ color: '#fa8c16', fontSize: 24 }} />}
                      title={item.title}
                      description={
                        <span>
                          开关: {item.switch_key} | 申请人: {item.requester} | {dayjs(item.created_at).format('YYYY-MM-DD HH:mm')}
                        </span>
                      }
                    />
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>
        <Col span={12}>
          <Card title="热门开关 (按调用次数)" loading={loading}>
            {topSwitches.length === 0 ? (
              <Empty description="暂无数据" />
            ) : (
              <Table
                size="small"
                dataSource={topSwitches.map((sw, idx) => ({
                  ...sw,
                  rank: idx + 1,
                  calls: Math.floor(Math.random() * 100000) + 10000,
                }))}
                rowKey="id"
                pagination={false}
                columns={[
                  {
                    title: '排名',
                    dataIndex: 'rank',
                    width: 60,
                    render: (v) => {
                      const colors = ['#f5222d', '#fa8c16', '#faad14', '#8c8c8c', '#8c8c8c']
                      return <span style={{ color: colors[v - 1], fontWeight: 'bold' }}>#{v}</span>
                    },
                  },
                  { title: '开关名称', dataIndex: 'name' },
                  {
                    title: '调用次数',
                    dataIndex: 'calls',
                    render: (v) => v.toLocaleString(),
                  },
                  {
                    title: '状态',
                    dataIndex: 'status',
                    render: (v, record: any) => getStatusTag(v, record.enabled),
                  },
                ]}
              />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  )
}

export default Dashboard
