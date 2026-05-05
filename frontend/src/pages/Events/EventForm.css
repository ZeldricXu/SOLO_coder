import React, { useState, useEffect } from 'react';
import {
  Card,
  Form,
  Input,
  DatePicker,
  InputNumber,
  Switch,
  Select,
  Button,
  Row,
  Col,
  Space,
  message,
  Spin,
} from 'antd';
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import dayjs from 'dayjs';
import { eventApi, ticketApi, formFieldApi } from '../../services/api';
import './EventForm.css';

const { TextArea } = Input;
const { RangePicker } = DatePicker;

const EventForm = () => {
  const navigate = useNavigate();
  const { eventId } = useParams();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [tickets, setTickets] = useState([]);
  const [formFields, setFormFields] = useState([]);

  const isEdit = !!eventId;

  useEffect(() => {
    if (isEdit) {
      fetchEventDetails();
    }
  }, [eventId]);

  const fetchEventDetails = async () => {
    try {
      setLoading(true);
      const [eventRes, ticketsRes, fieldsRes] = await Promise.all([
        eventApi.getEvent(eventId),
        ticketApi.getTickets(eventId),
        formFieldApi.getFormFields(eventId),
      ]);
      
      const event = eventRes.data;
      form.setFieldsValue({
        ...event,
        timeRange: [dayjs(event.start_time), dayjs(event.end_time)],
        need_approval: event.need_approval === 1 || event.need_approval === true,
      });
      
      if (ticketsRes.data) {
        setTickets(ticketsRes.data);
      }
      if (fieldsRes.data) {
        setFormFields(fieldsRes.data);
      }
    } catch (error) {
      message.error('加载活动信息失败');
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (values) => {
    try {
      setSubmitting(true);
      
      const eventData = {
        title: values.title,
        description: values.description,
        start_time: values.timeRange[0].toISOString(),
        end_time: values.timeRange[1].toISOString(),
        location: values.location,
        max_attendees: values.max_attendees || 0,
        need_approval: values.need_approval,
        status: isEdit ? undefined : 'draft',
      };

      if (isEdit) {
        await eventApi.updateEvent(eventId, eventData);
        message.success('活动更新成功');
      } else {
        await eventApi.createEvent(eventData);
        message.success('活动创建成功');
      }

      navigate('/events');
    } catch (error) {
      message.error(isEdit ? '更新活动失败' : '创建活动失败');
      console.error(error);
    } finally {
      setSubmitting(false);
    }
  };

  const handlePublish = async () => {
    try {
      await form.validateFields();
      const values = form.getFieldsValue();
      await handleSubmit(values);
    } catch (error) {
      message.error('请先完善表单信息');
    }
  };

  const statusOptions = [
    { label: '草稿', value: 'draft' },
    { label: '已发布', value: 'published' },
    { label: '已关闭', value: 'closed' },
    { label: '已取消', value: 'cancelled' },
  ];

  const getStatusColor = (status) => {
    switch (status) {
      case 'draft': return 'default';
      case 'published': return 'success';
      case 'closed': return 'processing';
      case 'cancelled': return 'error';
      default: return 'default';
    }
  };

  return (
    <div className="event-form-page">
      <Space className="page-header" size="middle">
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/events')}
        >
          返回列表
        </Button>
        <h2>{isEdit ? '编辑活动' : '创建活动'}</h2>
      </Space>

      <Spin spinning={loading}>
        <Form
          form={form}
          layout="vertical"
          onFinish={handleSubmit}
          className="event-form"
        >
          <Row gutter={24}>
            <Col xs={24} lg={16}>
              <Card title="基本信息" className="form-card">
                <Form.Item
                  name="title"
                  label="活动标题"
                  rules={[{ required: true, message: '请输入活动标题' }]}
                >
                  <Input placeholder="请输入活动标题" size="large" />
                </Form.Item>

                <Form.Item
                  name="description"
                  label="活动描述"
                >
                  <TextArea
                    rows={6}
                    placeholder="请输入活动详细描述"
                    showCount
                    maxLength={2000}
                  />
                </Form.Item>

                <Form.Item
                  name="timeRange"
                  label="活动时间"
                  rules={[{ required: true, message: '请选择活动时间' }]}
                >
                  <RangePicker
                    showTime
                    style={{ width: '100%' }}
                    size="large"
                    format="YYYY-MM-DD HH:mm"
                  />
                </Form.Item>

                <Form.Item
                  name="location"
                  label="活动地点"
                  rules={[{ required: true, message: '请输入活动地点' }]}
                >
                  <Input placeholder="请输入活动地点" size="large" />
                </Form.Item>
              </Card>

              {isEdit && (
                <Card title="票务管理" className="form-card">
                  <div className="tickets-section">
                    {tickets.length === 0 ? (
                      <div className="empty-tickets">
                        <p>暂无票务设置</p>
                        <Button type="primary">添加票务</Button>
                      </div>
                    ) : (
                      <div className="tickets-list">
                        {tickets.map((ticket) => (
                          <div key={ticket.ticket_id} className="ticket-item">
                            <div className="ticket-info">
                              <h4>{ticket.ticket_name}</h4>
                              <p className="price">¥{ticket.price}</p>
                              <p className="quota">
                                配额: {ticket.quota} | 已售: {ticket.sold_count}
                              </p>
                            </div>
                            <Space>
                              <Button size="small">编辑</Button>
                              <Button size="small" danger>删除</Button>
                            </Space>
                          </div>
                        ))}
                      </div>
                    )}
                    <Button type="dashed" block className="add-ticket-btn">
                      + 添加票务
                    </Button>
                  </div>
                </Card>
              )}

              {isEdit && (
                <Card title="表单配置" className="form-card">
                  <div className="form-fields-section">
                    {formFields.length === 0 ? (
                      <div className="empty-fields">
                        <p>暂无表单字段</p>
                        <Button type="primary">添加字段</Button>
                      </div>
                    ) : (
                      <div className="fields-list">
                        {formFields.map((field, index) => (
                          <div key={field.field_id} className="field-item">
                            <span className="field-order">{index + 1}</span>
                            <div className="field-info">
                              <h4>{field.field_label}</h4>
                              <p className="field-type">类型: {field.field_type}</p>
                              <p className="field-required">
                                {field.required ? '必填' : '选填'}
                              </p>
                            </div>
                            <Space>
                              <Button size="small">编辑</Button>
                              <Button size="small" danger>删除</Button>
                            </Space>
                          </div>
                        ))}
                      </div>
                    )}
                    <Button type="dashed" block className="add-field-btn">
                      + 添加字段
                    </Button>
                  </div>
                </Card>
              )}
            </Col>

            <Col xs={24} lg={8}>
              <Card title="设置" className="form-card">
                <Form.Item
                  name="max_attendees"
                  label="最大参会人数"
                  tooltip="设置为0表示不限制人数"
                >
                  <InputNumber
                    min={0}
                    style={{ width: '100%' }}
                    placeholder="不限制请填0"
                  />
                </Form.Item>

                <Form.Item
                  name="need_approval"
                  label="需要审核"
                  valuePropName="checked"
                  tooltip="开启后，用户报名需要审核通过才能确认"
                >
                  <Switch checkedChildren="开启" unCheckedChildren="关闭" />
                </Form.Item>

                {isEdit && (
                  <Form.Item
                    name="status"
                    label="活动状态"
                  >
                    <Select options={statusOptions} />
                  </Form.Item>
                )}
              </Card>

              <Card className="form-card">
                <Space direction="vertical" style={{ width: '100%' }}>
                  {isEdit && (
                    <Button
                      type="primary"
                      block
                      size="large"
                      onClick={handlePublish}
                      disabled={submitting}
                    >
                      {submitting ? '处理中...' : '保存并发布'}
                    </Button>
                  )}
                  <Button
                    type="primary"
                    htmlType="submit"
                    block
                    size="large"
                    icon={<SaveOutlined />}
                    loading={submitting}
                  >
                    {isEdit ? '保存修改' : '创建活动'}
                  </Button>
                  <Button
                    block
                    size="large"
                    onClick={() => navigate('/events')}
                  >
                    取消
                  </Button>
                </Space>
              </Card>
            </Col>
          </Row>
        </Form>
      </Spin>
    </div>
  );
};

export default EventForm;
