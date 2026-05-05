import React, { useEffect } from 'react';
import { Badge, Dropdown, List, Avatar, Tag, Button, Typography, Empty } from 'antd';
import { BellOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { useNotifications } from '../contexts/NotificationContext';
import dayjs from 'dayjs';

const { Text, Paragraph } = Typography;

const typeLabels = {
  'task_created': '任务创建',
  'task_assigned': '任务分配',
  'task_status_changed': '状态变更',
  'task_deadline_approaching': '截止提醒',
  'event_created': '日程创建',
  'system': '系统通知'
};

const typeColors = {
  'task_created': 'blue',
  'task_assigned': 'purple',
  'task_status_changed': 'orange',
  'task_deadline_approaching': 'red',
  'event_created': 'green',
  'system': 'default'
};

const NotificationCenter = () => {
  const { 
    notifications, 
    unreadCount, 
    fetchUnreadNotifications, 
    markAsRead,
    clearAll
  } = useNotifications();

  useEffect(() => {
    fetchUnreadNotifications();
  }, []);

  const handleMarkAsRead = (notificationId) => {
    markAsRead(notificationId);
  };

  const dropdownContent = (
    <div style={{ width: 350, maxHeight: 400, overflow: 'auto' }}>
      <div style={{ 
        padding: '12px 16px', 
        borderBottom: '1px solid #f0f0f0',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center'
      }}>
        <Text strong>通知 ({unreadCount})</Text>
        {unreadCount > 0 && (
          <Button type="link" size="small" onClick={clearAll}>
            全部清除
          </Button>
        )}
      </div>
      
      {notifications.length === 0 ? (
        <Empty 
          description="暂无通知" 
          style={{ margin: '40px 0' }}
        />
      ) : (
        <List
          dataSource={notifications}
          renderItem={(item) => (
            <List.Item
              style={{ padding: '12px 16px', cursor: 'pointer' }}
              actions={[
                <Button 
                  type="text" 
                  size="small" 
                  icon={<CheckCircleOutlined />}
                  onClick={() => handleMarkAsRead(item.notification_id)}
                >
                  已读
                </Button>
              ]}
            >
              <List.Item.Meta
                avatar={
                  <Avatar 
                    style={{ 
                      backgroundColor: '#1890ff' 
                    }}
                  >
                    <BellOutlined />
                  </Avatar>
                }
                title={
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontWeight: 500 }}>{item.title}</span>
                    <Tag color={typeColors[item.type] || 'default'}>
                      {typeLabels[item.type] || '通知'}
                    </Tag>
                  </div>
                }
                description={
                  <div>
                    <Paragraph 
                      style={{ margin: 0, fontSize: 13, color: '#666' }}
                      ellipsis={{ rows: 2 }}
                    >
                      {item.content}
                    </Paragraph>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {dayjs(item.created_at).format('MM-DD HH:mm')}
                    </Text>
                  </div>
                }
              />
            </List.Item>
          )}
        />
      )}
    </div>
  );

  return (
    <Dropdown
      overlay={dropdownContent}
      placement="bottomRight"
      trigger={['click']}
    >
      <Badge count={unreadCount} overflowCount={99}>
        <Button 
          type="text" 
          icon={<BellOutlined style={{ fontSize: 18 }} />}
          style={{ 
            width: 40, 
            height: 40,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center'
          }}
        />
      </Badge>
    </Dropdown>
  );
};

export default NotificationCenter;
