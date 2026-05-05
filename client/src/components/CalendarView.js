import React, { useState, useEffect } from 'react';
import { 
  Calendar, 
  Card, Button, Modal, Form, Input, Select, DatePicker, TimePicker, 
  Tag, message, Spin, Popconfirm, Badge, List, Avatar, Tooltip 
} from 'antd';
import { PlusOutlined, ClockCircleOutlined, UserOutlined, EnvironmentOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { eventAPI, taskAPI } from '../services/api';
import dayjs from 'dayjs';

const CalendarView = () => {
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(false);
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [selectedDate, setSelectedDate] = useState(dayjs());
  const [tasks, setTasks] = useState([]);
  const [form] = Form.useForm();

  const fetchEvents = async (date = dayjs()) => {
    setLoading(true);
    try {
      const startDate = date.startOf('month').format('YYYY-MM-DD');
      const endDate = date.endOf('month').format('YYYY-MM-DD');
      
      const response = await eventAPI.getEvents(startDate, endDate);
      setEvents(response.data.data.events || []);
    } catch (error) {
      message.error('获取日程失败');
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  const fetchTasks = async () => {
    try {
      const response = await taskAPI.getTasks({ status: 'in_progress' });
      setTasks(response.data.data.tasks || []);
    } catch (error) {
      console.error('获取任务列表失败:', error);
    }
  };

  useEffect(() => {
    fetchEvents();
    fetchTasks();
  }, []);

  const onPanelChange = (value, mode) => {
    if (mode === 'month') {
      fetchEvents(value);
    }
  };

  const onSelect = (value) => {
    setSelectedDate(value);
  };

  const handleCreateEvent = async (values) => {
    try {
      const eventData = {
        title: values.title,
        description: values.description,
        start_time: values.start_time
          ? dayjs(`${values.start_date.format('YYYY-MM-DD')} ${values.start_time.format('HH:mm')}`).format('YYYY-MM-DDTHH:mm:ss')
          : null,
        end_time: values.end_time
          ? dayjs(`${values.end_date.format('YYYY-MM-DD')} ${values.end_time.format('HH:mm')}`).format('YYYY-MM-DDTHH:mm:ss')
          : null,
        related_task_id: values.related_task_id || null,
        location: values.location,
        participants: values.participants || [],
      };

      await eventAPI.createEvent(eventData);
      message.success('日程创建成功');
      setCreateModalVisible(false);
      form.resetFields();
      fetchEvents();
    } catch (error) {
      message.error(error.response?.data?.message || '创建日程失败');
    }
  };

  const dateCellRender = (value) => {
    const dayEvents = events.filter(event => {
      const eventStart = dayjs(event.start_time);
      const eventEnd = dayjs(event.end_time);
      const cellDate = value.startOf('day');
      return eventStart.isSame(cellDate, 'day') || 
             (eventStart.isBefore(cellDate) && eventEnd.isAfter(cellDate);
    });

    return (
      <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
        {dayEvents.map(event => (
          <li key={event.event_id} style={{ marginTop: 4 }}>
            <Tag 
              color={event.related_task_id ? 'blue' : 'green'}
              style={{ width: '100%', overflow: 'hidden', textOverflow: 'ellipsis' }}
            >
              {event.title}
            </Tag>
          </li>
        ))}
      </ul>
    );
  };

  const getSelectedDateEvents = () => {
    return events.filter(event => {
      const eventStart = dayjs(event.start_time);
      return eventStart.isSame(selectedDate, 'day');
    });
  };

  const selectedDayEvents = getSelectedDateEvents();

  return (
    <div style={{ display: 'flex', gap: 16, height: '100%' }}>
      <Card style={{ flex: 2 }}>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h3 style={{ margin: 0 }}>团队日历</h3>
          <Button 
            type="primary" 
            icon={<PlusOutlined />} 
            onClick={() => {
              form.setFieldsValue({
                start_date: selectedDate,
                end_date: selectedDate,
                start_time: dayjs().hour(9).minute(0),
                end_time: dayjs().hour(10).minute(0),
              });
              setCreateModalVisible(true);
            }}
          >
            创建日程
          </Button>
        </div>
        
        <Spin spinning={loading}>
          <Calendar
            fullscreen={false}
            cellRender={dateCellRender}
            onPanelChange={onPanelChange}
            onSelect={onSelect}
            defaultValue={selectedDate}
          />
        </Spin>
      </Card>

      <Card style={{ flex: 1 }} title={`${selectedDate.format('YYYY-MM-DD')} 日程`}>
        {selectedDayEvents.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '40px 0', color: '#999' }}>
            今日暂无日程
          </div>
        ) : (
          <List
            dataSource={selectedDayEvents}
            renderItem={(event) => (
              <List.Item
                actions={[
                  <Button type="link" size="small" icon={<EditOutlined />}>
                    编辑
                  </Button>,
                ]}
              >
                <List.Item.Meta
                  avatar={
                    <Badge 
                      status={event.related_task_id ? 'processing' : 'success'} 
                      text={null}
                    />
                  }
                  title={
                    <span style={{ fontWeight: 'bold' }}>{event.title}</span>
                  }
                  description={
                    <div style={{ marginTop: 8 }}>
                      {event.start_time && (
                      <div style={{ marginBottom: 4 }}>
                        <ClockCircleOutlined style={{ marginRight: 4 }} />
                        {dayjs(event.start_time).format('HH:mm')} - {dayjs(event.end_time).format('HH:mm')}
                      </div>
                    )}
                    {event.location && (
                      <div style={{ marginBottom: 4 }}>
                        <EnvironmentOutlined style={{ marginRight: 4 }} />
                        {event.location}
                      </div>
                    )}
                    {event.participants && event.participants.length > 0 && (
                      <Avatar.Group maxCount={3}>
                        {event.participants.map((p, index) => (
                          <Tooltip key={index} title={p.username || p.user_id}>
                            <Avatar size="small" icon={<UserOutlined />}>
                              {(p.username || p.user_id).charAt(0).toUpperCase()}
                            </Avatar>
                          </Tooltip>
                        ))}
                      </Avatar.Group>
                    )}
                  </div>
                }
              />
            </List.Item>
          )}
        />
        )}
      </Card>

      <Modal
        title="创建新日程"
        open={createModalVisible}
        onCancel={() => {
          setCreateModalVisible(false);
          form.resetFields();
        }}
        onOk={() => form.submit()}
        okText="创建"
        cancelText="取消"
        width={600}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleCreateEvent}
        >
          <Form.Item
            name="title"
            label="日程标题"
            rules={[{ required: true, message: '请输入日程标题' }]}
          >
            <Input placeholder="请输入日程标题" />
          </Form.Item>

          <Form.Item
            name="description"
            label="日程描述"
          >
            <Input.TextArea rows={3} placeholder="请输入日程描述" />
          </Form.Item>

          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item
              name="start_date"
              label="开始日期"
              rules={[{ required: true, message: '请选择开始日期' }]}
              style={{ flex: 1 }}
            >
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>

            <Form.Item
              name="start_time"
              label="开始时间"
              rules={[{ required: true, message: '请选择开始时间' }]}
              style={{ flex: 1 }}
            >
              <TimePicker style={{ width: '100%' }} format="HH:mm" />
            </Form.Item>
          </div>

          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item
              name="end_date"
              label="结束日期"
              rules={[{ required: true, message: '请选择结束日期' }]}
              style={{ flex: 1 }}
            >
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>

            <Form.Item
              name="end_time"
              label="结束时间"
              rules={[{ required: true, message: '请选择结束时间' }]}
              style={{ flex: 1 }}
            >
              <TimePicker style={{ width: '100%' }} format="HH:mm" />
            </Form.Item>
          </div>

          <Form.Item
            name="related_task_id"
            label="关联任务"
          >
            <Select
              placeholder="选择关联的任务（可选）"
              allowClear
              options={tasks.map(task => ({
                label: task.title,
                value: task.task_id,
              }))}
            />
          </Form.Item>

          <Form.Item
            name="location"
            label="地点"
          >
            <Input prefix={<EnvironmentOutlined />} placeholder="请输入地点" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default CalendarView;
