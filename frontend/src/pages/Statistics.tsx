import { useState, useEffect } from 'react'
import {
  Card,
  Row,
  Col,
  Statistic,
  DatePicker,
  Tabs,
  Table,
  message,
} from 'antd'
import {
  TeamOutlined,
  ClockCircleOutlined,
  BarChartOutlined,
  CheckCircleOutlined,
  FireOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import ReactECharts from 'echarts-for-react'
import { statsApi, roomApi } from '@/api'
import type { RoomUsageStat, MeetingHoursStat, HeatmapData, EfficiencyStat, Room } from '@/types'

const { RangePicker } = DatePicker

function Statistics() {
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs, dayjs.Dayjs]>([
    dayjs().subtract(30, 'day'),
    dayjs(),
  ])
  const [roomUsage, setRoomUsage] = useState<RoomUsageStat[]>([])
  const [meetingHours, setMeetingHours] = useState<MeetingHoursStat[]>([])
  const [heatmapData, setHeatmapData] = useState<HeatmapData[]>([])
  const [efficiency, setEfficiency] = useState<EfficiencyStat[]>([])
  const [rooms, setRooms] = useState<Room[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    loadAllStats()
    loadRooms()
  }, [])

  const loadRooms = async () => {
    try {
      const { data } = await roomApi.list()
      setRooms(data)
    } catch (error) {
      // ignore
    }
  }

  const loadAllStats = async () => {
    setLoading(true)
    try {
      const start = dateRange[0].format('YYYY-MM-DD')
      const end = dateRange[1].format('YYYY-MM-DD')

      const [roomUsageRes, meetingHoursRes, heatmapRes, efficiencyRes] = await Promise.all([
        statsApi.roomUsage(start, end),
        statsApi.meetingHours(start, end),
        statsApi.heatmap(start, end),
        statsApi.efficiency(start, end),
      ])

      setRoomUsage(roomUsageRes.data)
      setMeetingHours(meetingHoursRes.data)
      setHeatmapData(heatmapRes.data)
      setEfficiency(efficiencyRes.data)
    } catch (error) {
      message.error('加载统计数据失败')
    } finally {
      setLoading(false)
    }
  }

  const totalStats = {
    totalMeetings: roomUsage.reduce((sum, r) => sum + r.booking_count, 0),
    totalHours: roomUsage.reduce((sum, r) => sum + r.total_hours, 0),
    avgUsageRate: roomUsage.length > 0 ? roomUsage.reduce((sum, r) => sum + r.usage_rate, 0) / roomUsage.length : 0,
    totalRooms: rooms.length,
  }

  const roomUsageChart = {
    title: { text: '会议室使用率', left: 'center' },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'category', data: roomUsage.map((r) => r.room_name) },
    yAxis: { type: 'value', name: '使用率(%)' },
    series: [
      {
        name: '使用率',
        type: 'bar',
        data: roomUsage.map((r) => r.usage_rate.toFixed(1)),
        itemStyle: { color: '#1677ff' },
      },
    ],
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
  }

  const deptHoursChart = {
    title: { text: '各部门会议时长', left: 'center' },
    tooltip: { trigger: 'item' },
    legend: { orient: 'vertical', left: 'left' },
    series: [
      {
        name: '会议时长',
        type: 'pie',
        radius: ['40%', '70%'],
        data: meetingHours.map((m) => ({ name: m.department, value: m.total_hours.toFixed(1) })),
      },
    ],
  }

  const heatmapChart = (() => {
    const days = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
    const hours = Array.from({ length: 12 }, (_, i) => `${i + 8}:00`)

    const data = heatmapData.map((d) => [d.day_of_week, d.hour - 8, d.count])
    const maxCount = Math.max(...heatmapData.map((d) => d.count), 1)

    return {
      title: { text: '会议热力图', left: 'center' },
      tooltip: {
        position: 'top',
        formatter: (params: any) => {
          return `${days[params.value[0]]} ${hours[params.value[1]]}<br/>会议数: ${params.value[2]}`
        },
      },
      grid: { height: '50%', top: '10%' },
      xAxis: { type: 'category', data: hours, splitArea: { show: true } },
      yAxis: { type: 'category', data: days, splitArea: { show: true } },
      visualMap: {
        min: 0,
        max: maxCount,
        calculable: true,
        orient: 'horizontal',
        left: 'center',
        bottom: '0%',
        inRange: {
          color: ['#e6f7ff', '#91d5ff', '#1890ff', '#0050b3'],
        },
      },
      series: [
        {
          name: '会议数',
          type: 'heatmap',
          data: data,
          label: { show: true, fontSize: 10 },
          emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0, 0, 0, 0.5)' } },
        },
      ],
    }
  })()

  const tabItems = [
    { key: 'room', label: '会议室使用' },
    { key: 'dept', label: '部门统计' },
    { key: 'heatmap', label: '热力图' },
    { key: 'efficiency', label: '效率分析' },
  ]

  return (
    <div>
      <Card title="数据统计" loading={loading}>
        <div style={{ marginBottom: 24 }}>
          <RangePicker
            value={dateRange}
            onChange={(dates) => dates && setDateRange(dates as [dayjs.Dayjs, dayjs.Dayjs])}
            onChangeCapture={loadAllStats}
          />
        </div>

        <Row gutter={16} style={{ marginBottom: 24 }}>
          <Col span={6}>
            <Card>
              <Statistic
                title="会议室总数"
                value={totalStats.totalRooms}
                prefix={<TeamOutlined />}
                valueStyle={{ color: '#1677ff' }}
              />
            </Card>
          </Col>
          <Col span={6}>
            <Card>
              <Statistic
                title="会议总场次"
                value={totalStats.totalMeetings}
                prefix={<BarChartOutlined />}
                valueStyle={{ color: '#52c41a' }}
              />
            </Card>
          </Col>
          <Col span={6}>
            <Card>
              <Statistic
                title="会议总时长"
                value={totalStats.totalHours.toFixed(1)}
                suffix="小时"
                prefix={<ClockCircleOutlined />}
                valueStyle={{ color: '#fa8c16' }}
              />
            </Card>
          </Col>
          <Col span={6}>
            <Card>
              <Statistic
                title="平均使用率"
                value={totalStats.avgUsageRate.toFixed(1)}
                suffix="%"
                prefix={<FireOutlined />}
                valueStyle={{ color: '#f5222d' }}
              />
            </Card>
          </Col>
        </Row>

        <Tabs items={tabItems} defaultActiveKey="room">
          {{
            key: 'room',
            children: (
              <div>
                <ReactECharts option={roomUsageChart} style={{ height: 400 }} />
                <Table
                  dataSource={roomUsage}
                  rowKey="room_id"
                  pagination={false}
                  columns={[
                    { title: '会议室', dataIndex: 'room_name', key: 'room_name' },
                    { title: '预订次数', dataIndex: 'booking_count', key: 'booking_count' },
                    {
                      title: '使用时长(小时)',
                      dataIndex: 'total_hours',
                      key: 'total_hours',
                      render: (v: number) => v.toFixed(1),
                    },
                    {
                      title: '使用率',
                      dataIndex: 'usage_rate',
                      key: 'usage_rate',
                      render: (v: number) => `${v.toFixed(1)}%`,
                    },
                  ]}
                  style={{ marginTop: 24 }}
                />
              </div>
            ),
          }}
          {{
            key: 'dept',
            children: (
              <div>
                <ReactECharts option={deptHoursChart} style={{ height: 400 }} />
                <Table
                  dataSource={meetingHours}
                  rowKey="department"
                  pagination={false}
                  columns={[
                    { title: '部门', dataIndex: 'department', key: 'department' },
                    { title: '会议场次', dataIndex: 'total_meetings', key: 'total_meetings' },
                    {
                      title: '总时长(小时)',
                      dataIndex: 'total_hours',
                      key: 'total_hours',
                      render: (v: number) => v.toFixed(1),
                    },
                    {
                      title: '平均时长(小时)',
                      dataIndex: 'avg_hours',
                      key: 'avg_hours',
                      render: (v: number) => v.toFixed(1),
                    },
                  ]}
                  style={{ marginTop: 24 }}
                />
              </div>
            ),
          }}
          {{
            key: 'heatmap',
            children: <ReactECharts option={heatmapChart} style={{ height: 450 }} />,
          }}
          {{
            key: 'efficiency',
            children: (
              <Table
                dataSource={efficiency}
                rowKey="booking_id"
                columns={[
                  { title: '会议', dataIndex: 'title', key: 'title' },
                  {
                    title: '计划时长(分钟)',
                    dataIndex: 'planned_minutes',
                    key: 'planned_minutes',
                    render: (v: number) => v.toFixed(0),
                  },
                  {
                    title: '实际时长(分钟)',
                    dataIndex: 'actual_minutes',
                    key: 'actual_minutes',
                    render: (v: number) => v.toFixed(0),
                  },
                  {
                    title: '效率',
                    dataIndex: 'efficiency_rate',
                    key: 'efficiency_rate',
                    render: (v: number) => {
                      const color = v >= 90 ? '#52c41a' : v >= 70 ? '#fa8c16' : '#f5222d'
                      return <span style={{ color }}>{v.toFixed(0)}%</span>
                    },
                  },
                ]}
              />
            ),
          }}
        </Tabs>
      </Card>
    </div>
  )
}

export default Statistics
