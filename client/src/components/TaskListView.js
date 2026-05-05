import React, { useState, useEffect, useCallback } from 'react';
import { 
  Row, 
  Col, 
  Button, 
  Modal, 
  Form, 
  Input, 
  Select, 
  DatePicker, 
  Space,
  Spin,
  message,
  Card,
  Alert,
  Tag,
  Typography
} from 'antd';
import { 
  PlusOutlined, 
  FilterOutlined, 
  ReloadOutlined, 
  WarningOutlined,
  InfoCircleOutlined
} from '@ant-design/icons';
import {
  DndContext,
  closestCorners,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  DragOverlay,
} from '@dnd-kit/core';
import TaskColumn from './TaskColumn';
import TaskCard from './TaskCard';
import { taskAPI } from '../services/api';
import dayjs from 'dayjs';

const { Text, Paragraph } = Typography;

const statusOrder = ['todo', 'in_progress', 'completed', 'cancelled'];

const TaskListView = () => {
  const [tasks, setTasks] = useState([]);
  const [loading, setLoading] = useState(false);
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [editingTask, setEditingTask] = useState(null);
  const [filters, setFilters] = useState({});
  const [activeId, setActiveId] = useState(null);
  const [statusTransitionError, setStatusTransitionError] = useState(null);
  const [form] = Form.useForm();

  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: {
        distance: 8,
      },
    }),
    useSensor(KeyboardSensor)
  );

  const fetchTasks = useCallback(async () => {
    setLoading(true);
    try {
      const response = await taskAPI.getTasks(filters);
      setTasks(response.data.data.tasks || []);
    } catch (error) {
      message.error('获取任务列表失败');
      console.error(error);
    } finally {
      setLoading(false);
    }
  }, [filters]);

  useEffect(() => {
    fetchTasks();
  }, [fetchTasks]);

  const groupTasksByStatus = () => {
    const grouped = {
      todo: [],
      in_progress: [],
      completed: [],
      cancelled: []
    };

    tasks.forEach(task => {
      if (grouped[task.status]) {
        grouped[task.status].push(task);
      }
    });

    return grouped;
  };

  const handleDragStart = (event) => {
    setActiveId(event.active.id);
    setStatusTransitionError(null);
  };

  const handleDragEnd = async (event) => {
    const { active, over } = event;
    setActiveId(null);

    if (!over) return;

    const overId = over.id;
    
    if (overId.startsWith('column-')) {
      const newStatus = overId.replace('column-', '');
      const task = tasks.find(t => t.task_id === active.id);
      
      if (task && task.status !== newStatus) {
        try {
          await taskAPI.updateStatus(active.id, newStatus, task.progress, task.version);
          message.success('任务状态已更新');
          setStatusTransitionError(null);
          fetchTasks();
        } catch (error) {
          const errorData = error.response?.data;
          const errorMessage = errorData?.message || '更新状态失败';
          
          if (errorData?.details) {
            setStatusTransitionError({
              message: errorMessage,
              allowedTransitions: errorData.details.allowed_transitions,
              currentStatus: task.status
            });
          }
          
          message.error({
            content: (
              <div>
                <Text strong>{errorMessage}</Text>
                {errorData?.details?.allowed_transitions && (
                  <div style={{ marginTop: 8 }}>
                    <Text type="secondary">允许的状态转换：</Text>
                    <Space style={{ marginLeft: 8 }}>
                      {errorData.details.allowed_transitions.map((t, i) => (
                        <Tag key={i} color="blue">{t}</Tag>
                      ))}
                    </Space>
                  </div>
                )}
              </div>
            ),
            duration: 5
          });
        }
      }
    }
  };

  const handleCreateTask = async (values) => {
    try {
      const taskData = {
        ...values,
        due_date: values.due_date.format('YYYY-MM-DD'),
      };

      await taskAPI.createTask(taskData);
      message.success('任务创建成功');
      setCreateModalVisible(false);
      form.resetFields();
      fetchTasks();
    } catch (error) {
      message.error(error.response?.data?.message || '创建任务失败');
    }
  };

  const handleStatusChange = async (task, newStatus) => {
    try {
      await taskAPI.updateStatus(task.task_id, newStatus, task.progress, task.version);
      message.success('任务状态已更新');
      setStatusTransitionError(null);
      fetchTasks();
    } catch (error) {
      const errorData = error.response?.data;
      const errorMessage = errorData?.message || '更新状态失败';
      
      if (errorData?.details) {
        setStatusTransitionError({
          message: errorMessage,
          allowedTransitions: errorData.details.allowed_transitions,
          currentStatus: task.status
        });
      }
      
      message.error({
        content: (
          <div>
            <Text strong>{errorMessage}</Text>
            {errorData?.details?.allowed_transitions && (
              <div style={{ marginTop: 8 }}>
                <Text type="secondary">允许的状态转换：</Text>
                <Space style={{ marginLeft: 8 }}>
                  {errorData.details.allowed_transitions.map((t, i) => (
                    <Tag key={i} color="blue">{t}</Tag>
                  ))}
                </Space>
              </div>
            )}
          </div>
        ),
        duration: 5
      });
    }
  };

  const handleEdit = (task) => {
    setEditingTask(task);
    form.setFieldsValue({
      title: task.title,
      description: task.description,
      priority: task.priority,
      due_date: task.due_date ? dayjs(task.due_date) : null,
      assignees: task.assignees || [],
    });
    setEditModalVisible(true);
  };

  const getStatusLabel = (status) => {
    const labels = {
      'todo': '待办',
      'in_progress': '进行中',
      'completed': '已完成',
      'cancelled': '已取消'
    };
    return labels[status] || status;
  };

  const groupedTasks = groupTasksByStatus();
  const activeTask = tasks.find(t => t.task_id === activeId);

  return (
    <div style={{ height: '100%' }}>
      {statusTransitionError && (
        <Card style={{ marginBottom: 16, borderLeft: '4px solid #ff4d4f' }}>
          <Alert
            message="状态转换错误"
            description={
              <div>
                <Paragraph>{statusTransitionError.message}</Paragraph>
                <div>
                  <Text type="secondary">
                    <InfoCircleOutlined style={{ marginRight: 4 }} />
                    当前状态: <Tag color="default">{getStatusLabel(statusTransitionError.currentStatus)}</Tag>
                  </Text>
                </div>
                {statusTransitionError.allowedTransitions && (
                  <div style={{ marginTop: 8 }}>
                    <Text type="secondary">允许转换到：</Text>
                    <Space style={{ marginLeft: 8 }}>
                      {statusTransitionError.allowedTransitions.map((t, i) => (
                        <Tag key={i} color="blue">{t}</Tag>
                      ))}
                    </Space>
                  </div>
                )}
                <Button 
                  type="link" 
                  size="small" 
                  onClick={() => setStatusTransitionError(null)}
                  style={{ marginTop: 8, padding: 0 }}
                >
                  关闭提示
                </Button>
              </div>
            }
            type="error"
            showIcon
            icon={<WarningOutlined />}
          />
        </Card>
      )}

      <Card style={{ marginBottom: 16 }}>
        <Row justify="space-between" align="middle">
          <Col>
            <Space>
              <Button 
                type="primary" 
                icon={<PlusOutlined />} 
                onClick={() => setCreateModalVisible(true)}
              >
                创建任务
              </Button>
              <Select
                placeholder="筛选优先级"
                allowClear
                style={{ width: 120 }}
                onChange={(value) => setFilters(prev => ({ ...prev, priority: value }))}
              >
                <Select.Option value="low">低优先级</Select.Option>
                <Select.Option value="medium">中优先级</Select.Option>
                <Select.Option value="high">高优先级</Select.Option>
                <Select.Option value="urgent">紧急</Select.Option>
              </Select>
              <Button icon={<FilterOutlined />} onClick={() => setFilters({})}>
                清除筛选
              </Button>
            </Space>
          </Col>
          <Col>
            <Button icon={<ReloadOutlined />} onClick={fetchTasks} loading={loading}>
              刷新
            </Button>
          </Col>
        </Row>
      </Card>

      <Spin spinning={loading}>
        <DndContext
          sensors={sensors}
          collisionDetection={closestCorners}
          onDragStart={handleDragStart}
          onDragEnd={handleDragEnd}
        >
          <Row gutter={[16, 16]} style={{ display: 'flex', flexWrap: 'nowrap', overflowX: 'auto' }}>
            {statusOrder.map(status => (
              <TaskColumn
                key={status}
                status={status}
                tasks={groupedTasks[status] || []}
                onStatusChange={handleStatusChange}
                onEdit={handleEdit}
              />
            ))}
          </Row>
          
          <DragOverlay>
            {activeTask ? (
              <div style={{ opacity: 0.8 }}>
                <TaskCard task={activeTask} />
              </div>
            ) : null}
          </DragOverlay>
        </DndContext>
      </Spin>

      <Modal
        title="创建新任务"
        open={createModalVisible}
        onCancel={() => {
          setCreateModalVisible(false);
          form.resetFields();
        }}
        onOk={() => form.submit()}
        okText="创建"
        cancelText="取消"
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleCreateTask}
        >
          <Form.Item
            name="title"
            label="任务标题"
            rules={[{ required: true, message: '请输入任务标题' }]}
          >
            <Input placeholder="请输入任务标题" />
          </Form.Item>

          <Form.Item
            name="description"
            label="任务描述"
          >
            <Input.TextArea rows={4} placeholder="请输入任务描述" />
          </Form.Item>

          <Form.Item
            name="priority"
            label="优先级"
            initialValue="medium"
          >
            <Select>
              <Select.Option value="low">低</Select.Option>
              <Select.Option value="medium">中</Select.Option>
              <Select.Option value="high">高</Select.Option>
              <Select.Option value="urgent">紧急</Select.Option>
            </Select>
          </Form.Item>

          <Form.Item
            name="due_date"
            label="截止日期"
            rules={[{ required: true, message: '请选择截止日期' }]}
          >
            <DatePicker 
              style={{ width: '100%' }} 
              disabledDate={(current) => current && current < dayjs().startOf('day')}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="编辑任务"
        open={editModalVisible}
        onCancel={() => {
          setEditModalVisible(false);
          setEditingTask(null);
          form.resetFields();
        }}
        onOk={() => form.submit()}
        okText="保存"
        cancelText="取消"
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={async (values) => {
            try {
              const taskData = {
                ...values,
                due_date: values.due_date.format('YYYY-MM-DD'),
              };
              await taskAPI.createTask(taskData);
              message.success('任务更新成功');
              setEditModalVisible(false);
              setEditingTask(null);
              form.resetFields();
              fetchTasks();
            } catch (error) {
              message.error(error.response?.data?.message || '更新任务失败');
            }
          }}
        >
          <Form.Item
            name="title"
            label="任务标题"
            rules={[{ required: true, message: '请输入任务标题' }]}
          >
            <Input placeholder="请输入任务标题" />
          </Form.Item>

          <Form.Item
            name="description"
            label="任务描述"
          >
            <Input.TextArea rows={4} placeholder="请输入任务描述" />
          </Form.Item>

          <Form.Item
            name="priority"
            label="优先级"
          >
            <Select>
              <Select.Option value="low">低</Select.Option>
              <Select.Option value="medium">中</Select.Option>
              <Select.Option value="high">高</Select.Option>
              <Select.Option value="urgent">紧急</Select.Option>
            </Select>
          </Form.Item>

          <Form.Item
            name="due_date"
            label="截止日期"
            rules={[{ required: true, message: '请选择截止日期' }]}
          >
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default TaskListView;
