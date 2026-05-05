import React, { useState, useEffect } from 'react';
import {
  Card,
  Table,
  Tag,
  Button,
  Space,
  Select,
  Input,
  Modal,
  message,
  Badge,
  Spin,
  Row,
  Col,
  Statistic,
  Timeline,
  Divider,
} from 'antd';
import {
  SearchOutlined,
  ScanOutlined,
  ReloadOutlined,
  CheckOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { eventApi, registrationApi, checkInApi } from '../../services/api';
import dayjs from 'dayjs';
import './CheckInPage.css';

const { Option } = Select;

const CheckInPage = () => {
  const [loading, setLoading] = useState(false);
  const [events, setEvents] = useState([]);
  const [selectedEvent, setSelectedEvent] = useState(null);
  const [registrations, setRegistrations] = useState([]);
  const [searchText, setSearchText] = useState('');
  const [checkInFilter, setCheckInFilter] = useState('all');
  const [checkInModalVisible, setCheckInModalVisible] = useState(false);
  const [selectedRegistration, setSelectedRegistration] = useState(null);
  const [checkInLogs, setCheckInLogs] = useState([]);
  const [processing, setProcessing] = useState(false);

  useEffect(() => {
    fetchEvents();
  }, []);

  useEffect(() => {
    fetchRegistrations();
    fetchCheckInStats();
  }, [selectedEvent]);

  const fetchEvents = async () => {
    try {
      const res = await eventApi.getEvents({ status: 'published' });
      setEvents(res.data || []);
      if (res.data && res.data.length > 0) {
        setSelectedEvent(res.data[0].event_id);
      }
    } catch (error) {
      console.error('获取活动列表失败', error);
    }
  };

  const fetchRegistrations = async () => {
    if (!selectedEvent) return;
    
    try {
      setLoading(true);
      const res = await registrationApi.getRegistrations(selectedEvent, {
        status: 'approved',
      });
      setRegistrations(res.data || []);
    } catch (error) {
      message.error('获取报名列表失败');
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  const fetchCheckInStats = async () => {
    if (!selectedEvent) return;
    
    try {
      const logsRes = await checkInApi.getCheckIns(selectedEvent);
      setCheckInLogs(logsRes.data || []);
    } catch (error) {
      console.error('获取签到记录失败', error);
    }
  };

  const handleCheckIn = async (registration) => {
    try {
      setProcessing(true);
      await checkInApi.checkIn({
        registration_id: registration.registration_id,
        check_in_method: 'manual',
      });
      message.success('签到成功');
      fetchRegistrations();
      fetchCheckInStats();
    } catch (error) {
      message.error('签到失败');
      console.error(error);
    } finally {
      setProcessing(false);
    }
  };

  const parseFormData = (formData) => {
    try {
      return typeof formData === 'string' ? JSON.parse(formData) : formData;
    } catch {
      return formData || {};
    }
  };

  const filteredRegistrations = registrations.filter((reg) => {
    if (checkInFilter === 'checked' && !reg.check_in_status) return false;
    if (checkInFilter === 'unchecked' && reg.check_in_status) return false;
    
    if (!searchText) return true;
    const formData = parseFormData(reg.form_data);
    const searchLower = searchText.toLowerCase();
    return Object.values(formData).some(
      (value) => String(value).toLowerCase().includes(searchLower)
    );
  });

  const stats = {
    total: registrations.length,
    checked: registrations.filter((r) => r.check_in_status).length,
    unchecked: registrations.filter((r) => !r.check_in_status).length,
  };

  const columns = [
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
      title: '姓名',
      key: 'name',
      width: 120,
      render: (_, record) => {
        const formData = parseFormData(record.form_data);
        return formData.name || formData.姓名 || '-';
      },
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
    {
      title: '签到时间',
      dataIndex: 'check_in_time',
      key: 'check_in_time',
      width: 160,
      render: (text) => text ? dayjs(text).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          {!record.check_in_status ? (
            <Button
              type="primary"
              size="small"
              icon={<CheckOutlined />}
              onClick={() => handleCheckIn(record)}
              loading={processing}
            >
              签到
            </Button>
          ) : (
            <Tag color="success">已签到</Tag>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div className="checkin-page">
      <div className="page-header">
        <Space size="middle">
          <h2>签到管理</h2>
          <Button
            type="primary"
            icon={<ReloadOutlined />}
            onClick={() => {
              fetchRegistrations();
              fetchCheckInStats();
            }}
          >
            刷新
          </Button>
        </Space>
      </div>

      <Row gutter={16} className="stats-cards">
        <Col span={8}>
          <Card>
            <Statistic
              title="总报名人数"
              value={stats.total}
              prefix={<UserOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title="已签到"
              value={stats.checked}
              valueStyle={{ color: '#52c41a' }}
              prefix={<CheckOutlined />}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title="未签到"
              value={stats.unchecked}
              valueStyle={{ color: '#fa8c16' }}
              prefix={<Badge status="warning" />}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col xs={24} lg={16}>
          <Card className="filter-card">
            <Space wrap size="middle">
              <Select
                style={{ width: 250 }}
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

              <Select
                style={{ width: 120 }}
                value={checkInFilter}
                onChange={setCheckInFilter}
              >
                <Option value="all">全部</Option>
                <Option value="checked">已签到</Option>
                <Option value="unchecked">未签到</Option>
              </Select>

              <Input.Search
                placeholder="搜索姓名/手机号"
                allowClear
                style={{ width: 250 }}
                onChange={(e) => setSearchText(e.target.value)}
              />

              <Button
                type="primary"
                icon={<ScanOutlined />}
                onClick={() => setCheckInModalVisible(true)}
              >
                扫码签到
              </Button>
            </Space>
          </Card>

          <Card className="table-card">
            <Spin spinning={loading}>
              <Table
                columns={columns}
                dataSource={filteredRegistrations}
                rowKey="registration_id"
                scroll={{ x: 800 }}
                pagination={{
                  pageSize: 10,
                  showSizeChanger: true,
                  showTotal: (total) => `共 ${total} 条记录`,
                }}
              />
            </Spin>
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card title="签到记录" className="timeline-card">
            <Timeline mode="left">
              {checkInLogs.length === 0 ? (
                <Timeline.Item color="gray">
                  <p>暂无签到记录</p>
                </Timeline.Item>
              ) : (
                checkInLogs.slice(0, 10).map((log, index) => (
                  <Timeline.Item
                    key={log.check_in_id}
                    color={index < 3 ? 'green' : 'gray'}
                  >
                    <div className="timeline-item">
                      <p className="timeline-name">
                        {parseFormData(log.form_data)?.name || '用户'}
                      </p>
                      <p className="timeline-time">
                        {dayjs(log.check_in_time).format('HH:mm:ss')}
                      </p>
                      <Tag size="small">
                        {log.check_in_method === 'qr_code' ? '扫码签到' : '手动签到'}
                      </Tag>
                    </div>
                  </Timeline.Item>
                ))
              )}
            </Timeline>
          </Card>
        </Col>
      </Row>

      <Modal
        title="扫码签到"
        open={checkInModalVisible}
        onCancel={() => setCheckInModalVisible(false)}
        footer={null}
        width={500}
      >
        <div className="qr-checkin-modal">
          <div className="qr-placeholder">
            <ScanOutlined style={{ fontSize: 120, color: '#d9d9d9' }} />
            <p>请将二维码放入框内</p>
            <p style={{ color: '#999', fontSize: 12 }}>
              或输入报名ID进行手动签到
            </p>
          </div>
          <Divider>手动签到</Divider>
          <Space.Compact style={{ width: '100%' }}>
            <Input placeholder="请输入报名ID" />
            <Button type="primary">确认签到</Button>
          </Space.Compact>
        </div>
      </Modal>
    </div>
  );
};

export default CheckInPage;
