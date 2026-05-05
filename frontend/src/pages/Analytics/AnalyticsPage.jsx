import React, { useState, useEffect } from 'react';
import {
  Card,
  Row,
  Col,
  Statistic,
  Select,
  DatePicker,
  Button,
  Table,
  Tag,
  Space,
  message,
  Spin,
  Divider,
} from 'antd';
import {
  UserOutlined,
  CheckOutlined,
  DollarOutlined,
  BarChartOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
  LineChart,
  Line,
  Area,
  AreaChart,
} from 'recharts';
import { eventApi, analyticsApi, registrationApi, ticketApi } from '../../services/api';
import dayjs from 'dayjs';
import './AnalyticsPage.css';

const { Option } = Select;
const { RangePicker } = DatePicker;

const COLORS = ['#0088FE', '#00C49F', '#FFBB28', '#FF8042', '#8884d8', '#82ca9d'];

const AnalyticsPage = () => {
  const [loading, setLoading] = useState(false);
  const [events, setEvents] = useState([]);
  const [selectedEvent, setSelectedEvent] = useState(null);
  const [overview, setOverview] = useState(null);
  const [registrationTrend, setRegistrationTrend] = useState([]);
  const [ticketSales, setTicketSales] = useState([]);
  const [checkInStats, setCheckInStats] = useState(null);
  const [registrations, setRegistrations] = useState([]);

  useEffect(() => {
    fetchEvents();
  }, []);

  useEffect(() => {
    if (selectedEvent) {
      fetchAnalytics();
    }
  }, [selectedEvent]);

  const fetchEvents = async () => {
    try {
      const res = await eventApi.getEvents();
      setEvents(res.data || []);
      if (res.data && res.data.length > 0) {
        setSelectedEvent(res.data[0].event_id);
      }
    } catch (error) {
      console.error('获取活动列表失败', error);
    }
  };

  const fetchAnalytics = async () => {
    if (!selectedEvent) return;

    try {
      setLoading(true);
      
      const [overviewRes, trendRes, ticketRes, checkInRes, regRes] = await Promise.all([
        analyticsApi.getEventOverview(selectedEvent),
        analyticsApi.getRegistrationTrend(selectedEvent),
        analyticsApi.getTicketSales(selectedEvent),
        analyticsApi.getCheckInStats(selectedEvent),
        registrationApi.getRegistrations(selectedEvent, { status: 'approved' }),
      ]);

      setOverview(overviewRes.data);
      setRegistrationTrend(trendRes.data || []);
      setTicketSales(ticketRes.data || []);
      setCheckInStats(checkInRes.data);
      setRegistrations(regRes.data || []);
    } catch (error) {
      message.error('获取数据失败');
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  const defaultStats = {
    total_registrations: 0,
    approved_registrations: 0,
    pending_review: 0,
    total_revenue: 0,
    checked_in_count: 0,
  };

  const stats = overview || defaultStats;

  const pieData = ticketSales.length > 0
    ? ticketSales.map((t) => ({
        name: t.ticket_name || '免费票',
        value: t.sold_count || 0,
      }))
    : [{ name: '暂无数据', value: 0 }];

  const trendData = registrationTrend.length > 0
    ? registrationTrend
    : [
        { date: dayjs().subtract(6, 'day').format('MM-DD'), registrations: 0 },
        { date: dayjs().subtract(5, 'day').format('MM-DD'), registrations: 0 },
        { date: dayjs().subtract(4, 'day').format('MM-DD'), registrations: 0 },
        { date: dayjs().subtract(3, 'day').format('MM-DD'), registrations: 0 },
        { date: dayjs().subtract(2, 'day').format('MM-DD'), registrations: 0 },
        { date: dayjs().subtract(1, 'day').format('MM-DD'), registrations: 0 },
        { date: dayjs().format('MM-DD'), registrations: 0 },
      ];

  const registrationColumns = [
    {
      title: '序号',
      width: 80,
      render: (_, __, index) => index + 1,
    },
    {
      title: '报名时间',
      dataIndex: 'registered_at',
      key: 'registered_at',
      width: 160,
      render: (text) => dayjs(text).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: '票务类型',
      dataIndex: 'ticket_name',
      key: 'ticket_name',
      width: 120,
      render: (text) => text || '免费',
    },
    {
      title: '签到状态',
      dataIndex: 'check_in_status',
      key: 'check_in_status',
      width: 100,
      render: (checked) => (
        checked ? <Tag color="success">已签到</Tag> : <Tag color="default">未签到</Tag>
      ),
    },
  ];

  return (
    <div className="analytics-page">
      <div className="page-header">
        <Space size="middle">
          <h2>数据报表</h2>
          <Select
            style={{ width: 300 }}
            placeholder="选择活动"
            value={selectedEvent}
            onChange={setSelectedEvent}
            allowClear
          >
            {events.map((event) => (
              <Option key={event.event_id} value={event.event_id}>
                {event.title}
              </Option>
            ))}
          </Select>
          <Button
            type="primary"
            icon={<ReloadOutlined />}
            onClick={fetchAnalytics}
          >
            刷新数据
          </Button>
        </Space>
      </div>

      <Spin spinning={loading}>
        <Row gutter={16} className="stats-cards">
          <Col xs={12} lg={6}>
            <Card>
              <Statistic
                title="总报名人数"
                value={stats.total_registrations || 0}
                prefix={<UserOutlined />}
              />
            </Card>
          </Col>
          <Col xs={12} lg={6}>
            <Card>
              <Statistic
                title="已通过"
                value={stats.approved_registrations || 0}
                valueStyle={{ color: '#52c41a' }}
                prefix={<CheckOutlined />}
              />
            </Card>
          </Col>
          <Col xs={12} lg={6}>
            <Card>
              <Statistic
                title="待审核"
                value={stats.pending_review || 0}
                valueStyle={{ color: '#fa8c16' }}
              />
            </Card>
          </Col>
          <Col xs={12} lg={6}>
            <Card>
              <Statistic
                title="销售收入"
                value={stats.total_revenue || 0}
                precision={2}
                prefix="¥"
                valueStyle={{ color: '#1890ff' }}
              />
            </Card>
          </Col>
        </Row>

        <Row gutter={16}>
          <Col xs={24} lg={16}>
            <Card title="报名趋势" className="chart-card">
              <ResponsiveContainer width="100%" height={300}>
                <AreaChart data={trendData}>
                  <defs>
                    <linearGradient id="colorRegistrations" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#1890ff" stopOpacity={0.8} />
                      <stop offset="95%" stopColor="#1890ff" stopOpacity={0.1} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="date" />
                  <YAxis />
                  <Tooltip />
                  <Legend />
                  <Area
                    type="monotone"
                    dataKey="registrations"
                    stroke="#1890ff"
                    fillOpacity={1}
                    fill="url(#colorRegistrations)"
                    name="报名人数"
                  />
                </AreaChart>
              </ResponsiveContainer>
            </Card>
          </Col>

          <Col xs={24} lg={8}>
            <Card title="票务销售统计" className="chart-card">
              <ResponsiveContainer width="100%" height={300}>
                <PieChart>
                  <Pie
                    data={pieData}
                    cx="50%"
                    cy="50%"
                    labelLine={false}
                    label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
                    outerRadius={80}
                    fill="#8884d8"
                    dataKey="value"
                  >
                    {pieData.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip />
                </PieChart>
              </ResponsiveContainer>
            </Card>
          </Col>
        </Row>

        <Row gutter={16}>
          <Col xs={24} lg={12}>
            <Card title="签到统计" className="chart-card">
              {checkInStats ? (
                <div className="checkin-stats">
                  <Row gutter={16}>
                    <Col span={8}>
                      <Statistic
                        title="已签到"
                        value={checkInStats.checked_in || 0}
                        valueStyle={{ color: '#52c41a' }}
                      />
                    </Col>
                    <Col span={8}>
                      <Statistic
                        title="未签到"
                        value={checkInStats.not_checked_in || 0}
                        valueStyle={{ color: '#fa8c16' }}
                      />
                    </Col>
                    <Col span={8}>
                      <Statistic
                        title="签到率"
                        value={checkInStats.check_in_rate || 0}
                        suffix="%"
                      />
                    </Col>
                  </Row>
                  <Divider />
                  <ResponsiveContainer width="100%" height={200}>
                    <BarChart
                      data={[
                        { name: '签到情况', 已签到: checkInStats.checked_in || 0, 未签到: checkInStats.not_checked_in || 0 },
                      ]}
                    >
                      <CartesianGrid strokeDasharray="3 3" />
                      <XAxis dataKey="name" />
                      <YAxis />
                      <Tooltip />
                      <Legend />
                      <Bar dataKey="已签到" fill="#52c41a" />
                      <Bar dataKey="未签到" fill="#fa8c16" />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <div style={{ textAlign: 'center', padding: '40px' }}>
                  暂无数据
                </div>
              )}
            </Card>
          </Col>

          <Col xs={24} lg={12}>
            <Card title="票务销售详情" className="chart-card">
              <Table
                dataSource={ticketSales}
                rowKey="ticket_id"
                pagination={false}
                size="small"
              >
                <Table.Column title="票务名称" dataKey="ticket_name" key="ticket_name" />
                <Table.Column
                  title="价格"
                  dataKey="price"
                  key="price"
                  render={(price) => `¥${price}`}
                />
                <Table.Column title="配额" dataKey="quota" key="quota" />
                <Table.Column title="已售" dataKey="sold_count" key="sold_count" />
                <Table.Column
                  title="剩余"
                  key="remaining"
                  render={(r) => (r.quota || 0) - (r.sold_count || 0)}
                />
                <Table.Column
                  title="销售额"
                  key="revenue"
                  render={(r) => `¥${((r.price || 0) * (r.sold_count || 0)).toFixed(2)}`}
                />
              </Table>
            </Card>
          </Col>
        </Row>

        <Card title="报名列表" className="table-card">
          <Table
            columns={registrationColumns}
            dataSource={registrations}
            rowKey="registration_id"
            scroll={{ x: 500 }}
            pagination={{
              pageSize: 10,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 条记录`,
            }}
          />
        </Card>
      </Spin>
    </div>
  );
};

export default AnalyticsPage;
