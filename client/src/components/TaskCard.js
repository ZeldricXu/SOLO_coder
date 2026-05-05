import React from 'react';
import { Card, Tag, Progress, Avatar, Tooltip, Dropdown, Button, Space } from 'antd';
import { 
  MoreOutlined, 
  ClockCircleOutlined, 
  UserOutlined,
  EditOutlined,
  CheckCircleOutlined,
  PauseOutlined,
  CloseCircleOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';

const priorityColors = {
  low: 'green',
  medium: 'blue',
  high: 'orange',
  urgent: 'red'
};

const priorityLabels = {
  low: '低',
  medium: '中',
  high: '高',
  urgent: '紧急'
};

const statusColors = {
  todo: 'default',
  in_progress: 'processing',
  completed: 'success',
  cancelled: 'error'
};

const statusLabels = {
  todo: '待办',
  in_progress: '进行中',
  completed: '已完成',
  cancelled: '已取消'
};

const TaskCard = ({ task, onStatusChange, onEdit, style }) => {
  const isOverdue = task.due_date && dayjs(task.due_date).isBefore(dayjs(), 'day');
  const isDueSoon = task.due_date && dayjs(task.due_date).diff(dayjs(), 'day') <= 3 && dayjs(task.due_date).diff(dayjs(), 'day') >= 0;

  const getMenuItems = () => {
    const items = [];
    
    if (task.status !== 'in_progress') {
      items.push({
        key: 'start',
        icon: <PauseOutlined />,
        label: '开始任务',
        onClick: () => onStatusChange && onStatusChange(task, 'in_progress')
      });
    }
    
    if (task.status !== 'completed') {
      items.push({
        key: 'complete',
        icon: <CheckCircleOutlined />,
        label: '标记完成',
        onClick: () => onStatusChange && onStatusChange(task, 'completed')
      });
    }
    
    if (task.status !== 'cancelled') {
      items.push({
        key: 'cancel',
        icon: <CloseCircleOutlined />,
        label: '取消任务',
        onClick: () => onStatusChange && onStatusChange(task, 'cancelled')
      });
    }

    items.push({ type: 'divider' });
    
    items.push({
      key: 'edit',
      icon: <EditOutlined />,
      label: '编辑任务',
      onClick: () => onEdit && onEdit(task)
    });

    return items;
  };

  return (
    <Card
      size="small"
      style={{ 
        marginBottom: 12, 
        cursor: 'move',
        ...style 
      }}
      hoverable
      actions={[
        <Dropdown menu={{ items: getMenuItems() }} placement="bottomRight">
          <Button type="text" icon={<MoreOutlined />} />
        </Dropdown>
      ]}
    >
      <div style={{ marginBottom: 8 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 8 }}>
          <h4 style={{ margin: 0, flex: 1, marginRight: 8 }}>
            {task.title}
          </h4>
          <Space size={4}>
            <Tag color={priorityColors[task.priority]}>
              {priorityLabels[task.priority]}
            </Tag>
            <Tag color={statusColors[task.status]}>
              {statusLabels[task.status]}
            </Tag>
          </Space>
        </div>
        
        {task.description && (
          <p style={{ 
            margin: '0 0 12px 0', 
            fontSize: 13, 
            color: '#666',
            display: '-webkit-box',
            WebkitLineClamp: 2,
            WebkitBoxOrient: 'vertical',
            overflow: 'hidden'
          }}>
            {task.description}
          </p>
        )}
      </div>

      <Progress 
        percent={task.progress || 0} 
        size="small"
        status={task.status === 'completed' ? 'success' : task.status === 'cancelled' ? 'exception' : 'active'}
      />

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 12 }}>
        <Space size={8}>
          {task.assignee_names && task.assignee_names.length > 0 && (
            <Avatar.Group maxCount={3}>
              {task.assignee_names.map((name, index) => (
                <Tooltip key={index} title={name}>
                  <Avatar size="small" icon={<UserOutlined />}>
                    {name.charAt(0).toUpperCase()}
                  </Avatar>
                </Tooltip>
              ))}
            </Avatar.Group>
          )}
          
          {task.sub_task_count > 0 && (
            <Tag size="small" color="purple">
              {task.sub_task_count} 个子任务
            </Tag>
          )}
        </Space>

        {task.due_date && (
          <Tooltip title={dayjs(task.due_date).format('YYYY-MM-DD')}>
            <Tag 
              icon={<ClockCircleOutlined />}
              color={isOverdue ? 'error' : isDueSoon ? 'warning' : 'default'}
              size="small"
            >
              {dayjs(task.due_date).format('MM-DD')}
            </Tag>
          </Tooltip>
        )}
      </div>
    </Card>
  );
};

export default TaskCard;
