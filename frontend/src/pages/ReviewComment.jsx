import React, { useState, useEffect } from 'react';
import {
  Card,
  Row,
  Col,
  Table,
  Tag,
  Button,
  Space,
  Modal,
  Form,
  Input,
  Select,
  message,
  Tabs,
  Statistic,
  Empty,
  Descriptions,
  Divider,
  Timeline,
  List,
  Avatar
} from 'antd';
import {
  MessageOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  MinusCircleOutlined,
  EditOutlined,
  DeleteOutlined,
  ReplyOutlined,
  FileTextOutlined,
  UserOutlined,
  ClockCircleOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';

const mockComments = [
  {
    comment_id: 'comment_001',
    commit_id: 'commit_abc123',
    file_path: 'src/main.py',
    line_start: 25,
    line_end: 35,
    comment_type: 'suggestion',
    content: '建议将认证逻辑拆分为独立模块，便于后续维护和测试。当前 login 和 register 函数有大量重复代码。',
    author: 'reviewer_01',
    status: 'open',
    created_at: '2026-05-04T14:20:00Z',
    updated_at: '2026-05-04T14:20:00Z',
    parent_comment_id: null,
    replies: [
      {
        comment_id: 'comment_002',
        commit_id: 'commit_abc123',
        file_path: 'src/main.py',
        line_start: 25,
        line_end: 35,
        comment_type: 'comment',
        content: '同意，我会在下一个提交中重构这部分代码。计划创建一个 auth.py 模块来处理认证逻辑。',
        author: 'developer_01',
        status: 'open',
        created_at: '2026-05-04T14:30:00Z',
        updated_at: '2026-05-04T14:30:00Z',
        parent_comment_id: 'comment_001'
      },
      {
        comment_id: 'comment_003',
        commit_id: 'commit_abc123',
        file_path: 'src/main.py',
        line_start: 25,
        line_end: 35,
        comment_type: 'comment',
        content: '好的，请在重构后添加相应的单元测试。',
        author: 'reviewer_01',
        status: 'open',
        created_at: '2026-05-04T14:35:00Z',
        updated_at: '2026-05-04T14:35:00Z',
        parent_comment_id: 'comment_001'
      }
    ]
  },
  {
    comment_id: 'comment_004',
    commit_id: 'commit_abc123',
    file_path: 'src/main.py',
    line_start: 10,
    line_end: 10,
    comment_type: 'issue',
    content: 'SECRET_KEY 不应该硬编码在代码中，建议使用环境变量。生产环境中这会存在安全隐患。',
    author: 'reviewer_01',
    status: 'open',
    created_at: '2026-05-04T14:15:00Z',
    updated_at: '2026-05-04T14:15:00Z',
    parent_comment_id: null,
    replies: []
  },
  {
    comment_id: 'comment_005',
    commit_id: 'commit_abc123',
    file_path: 'src/utils.py',
    line_start: 15,
    line_end: 20,
    comment_type: 'suggestion',
    content: '建议使用 try-except 来处理日期转换可能的异常情况。当前实现如果日期格式不正确会抛出异常。',
    author: 'reviewer_02',
    status: 'resolved',
    created_at: '2026-05-04T13:45:00Z',
    updated_at: '2026-05-04T15:00:00Z',
    parent_comment_id: null,
    replies: [
      {
        comment_id: 'comment_006',
        commit_id: 'commit_abc123',
        file_path: 'src/utils.py',
        line_start: 15,
        line_end: 20,
        comment_type: 'comment',
        content: '已修复，添加了异常处理逻辑。',
        author: 'developer_01',
        status: 'resolved',
        created_at: '2026-05-04T14:55:00Z',
        updated_at: '2026-05-04T15:00:00Z',
        parent_comment_id: 'comment_005'
      }
    ]
  },
  {
    comment_id: 'comment_007',
    commit_id: 'commit_abc123',
    file_path: 'src/main.py',
    line_start: 45,
    line_end: 50,
    comment_type: 'issue',
    content: '这里缺少返回语句，可能导致逻辑错误。当用户不存在时应该返回 401 错误。',
    author: 'reviewer_01',
    status: 'dismissed',
    created_at: '2026-05-04T12:30:00Z',
    updated_at: '2026-05-04T13:00:00Z',
    parent_comment_id: null,
    replies: [
      {
        comment_id: 'comment_008',
        commit_id: 'commit_abc123',
        file_path: 'src/main.py',
        line_start: 45,
        line_end: 50,
        comment_type: 'comment',
        content: '经检查，这里实际上有返回语句，可能是静态分析工具的误报。',
        author: 'developer_01',
        status: 'dismissed',
        created_at: '2026-05-04T12:45:00Z',
        updated_at: '2026-05-04T13:00:00Z',
        parent_comment_id: 'comment_007'
      }
    ]
  }
];

const mockTasks = [
  {
    task_id: 'task_001',
    commit_id: 'commit_abc123',
    assignee: 'reviewer_01',
    title: '审查任务 - 用户认证功能',
    description: '检测到以下问题需要审查:\n- 2个文件复杂度较高\n- 1个规范错误\n- 5个规范警告',
    status: 'in_progress',
    priority: 'high',
    created_at: '2026-05-04T14:15:00Z',
    completed_at: null
  },
  {
    task_id: 'task_002',
    commit_id: 'commit_def456',
    assignee: 'reviewer_02',
    title: '审查任务 - 数据库优化',
    description: '代码分析完成，需要人工审查确认。',
    status: 'pending',
    priority: 'medium',
    created_at: '2026-05-04T12:00:00Z',
    completed_at: null
  },
  {
    task_id: 'task_003',
    commit_id: 'commit_ghi789',
    assignee: 'reviewer_01',
    title: '审查任务 - API重构',
    description: '代码质量良好，无严重问题。',
    status: 'completed',
    priority: 'low',
    created_at: '2026-05-03T16:30:00Z',
    completed_at: '2026-05-04T10:00:00Z'
  }
];

function ReviewComment() {
  const [activeTab, setActiveTab] = useState('comments');
  const [comments, setComments] = useState(mockComments);
  const [tasks, setTasks] = useState(mockTasks);
  const [selectedComment, setSelectedComment] = useState(null);
  const [replyModalVisible, setReplyModalVisible] = useState(false);
  const [replyForm] = Form.useForm();

  const commentStats = {
    total: comments.length,
    open: comments.filter(c => c.status === 'open').length,
    resolved: comments.filter(c => c.status === 'resolved').length,
    dismissed: comments.filter(c => c.status === 'dismissed').length,
    byType: {
      comment: comments.filter(c => c.comment_type === 'comment').length,
      suggestion: comments.filter(c => c.comment_type === 'suggestion').length,
      issue: comments.filter(c => c.comment_type === 'issue').length
    }
  };

  const taskStats = {
    total: tasks.length,
    pending: tasks.filter(t => t.status === 'pending').length,
    in_progress: tasks.filter(t => t.status === 'in_progress').length,
    completed: tasks.filter(t => t.status === 'completed').length,
    rejected: tasks.filter(t => t.status === 'rejected').length
  };

  const getStatusTag = (status) => {
    const statusMap = {
      'open': { color: 'orange', text: '待处理', icon: <ClockCircleOutlined /> },
      'resolved': { color: 'green', text: '已解决', icon: <CheckCircleOutlined /> },
      'dismissed': { color: 'default', text: '已忽略', icon: <MinusCircleOutlined /> },
      'pending': { color: 'orange', text: '待处理' },
      'in_progress': { color: 'blue', text: '进行中' },
      'completed': { color: 'green', text: '已完成' },
      'rejected': { color: 'red', text: '已拒绝' }
    };
    const info = statusMap[status] || statusMap['open'];
    return (
      <Tag color={info.color} icon={info.icon}>
        {info.text}
      </Tag>
    );
  };

  const getTypeTag = (type) => {
    const typeMap = {
      'comment': { color: 'blue', text: '评论' },
      'suggestion': { color: 'orange', text: '建议' },
      'issue': { color: 'red', text: '问题' }
    };
    const info = typeMap[type] || typeMap['comment'];
    return <Tag color={info.color}>{info.text}</Tag>;
  };

  const getPriorityTag = (priority) => {
    const priorityMap = {
      'high': { color: 'red', text: '高' },
      'medium': { color: 'orange', text: '中' },
      'low': { color: 'green', text: '低' }
    };
    const info = priorityMap[priority] || priorityMap['medium'];
    return <Tag color={info.color}>{info.text}</Tag>;
  };

  const handleResolveComment = async (comment_id) => {
    try {
      setComments(comments.map(c => 
        c.comment_id === comment_id ? { ...c, status: 'resolved' } : c
      ));
      message.success('评论已标记为已解决');
    } catch (error) {
      message.error('操作失败');
    }
  };

  const handleDismissComment = async (comment_id) => {
    try {
      setComments(comments.map(c => 
        c.comment_id === comment_id ? { ...c, status: 'dismissed' } : c
      ));
      message.success('评论已标记为已忽略');
    } catch (error) {
      message.error('操作失败');
    }
  };

  const handleReopenComment = async (comment_id) => {
    try {
      setComments(comments.map(c => 
        c.comment_id === comment_id ? { ...c, status: 'open' } : c
      ));
      message.success('评论已重新打开');
    } catch (error) {
      message.error('操作失败');
    }
  };

  const handleReplySubmit = async (values) => {
    try {
      const newReply = {
        comment_id: `comment_${Date.now()}`,
        commit_id: selectedComment.commit_id,
        file_path: selectedComment.file_path,
        line_start: selectedComment.line_start,
        line_end: selectedComment.line_end,
        comment_type: 'comment',
        content: values.content,
        author: 'current_user',
        status: selectedComment.status,
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
        parent_comment_id: selectedComment.comment_id
      };

      setComments(comments.map(c => {
        if (c.comment_id === selectedComment.comment_id) {
          return {
            ...c,
            replies: [...(c.replies || []), newReply]
          };
        }
        return c;
      }));

      message.success('回复成功');
      setReplyModalVisible(false);
      replyForm.resetFields();
    } catch (error) {
      message.error('回复失败');
    }
  };

  const handleUpdateTaskStatus = async (task_id, status) => {
    try {
      setTasks(tasks.map(t => 
        t.task_id === task_id ? { 
          ...t, 
          status,
          completed_at: status === 'completed' ? new Date().toISOString() : null
        } : t
      ));
      message.success('任务状态已更新');
    } catch (error) {
      message.error('操作失败');
    }
  };

  const commentColumns = [
    {
      title: '文件',
      dataIndex: 'file_path',
      key: 'file_path',
      render: (text) => <code>{text}</code>
    },
    {
      title: '行号',
      dataIndex: 'line_start',
      key: 'line_start',
      render: (_, record) => (
        <span>
          {record.line_start}{record.line_end !== record.line_start ? `-${record.line_end}` : ''}
        </span>
      )
    },
    {
      title: '类型',
      dataIndex: 'comment_type',
      key: 'comment_type',
      width: 80,
      render: (type) => getTypeTag(type)
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status) => getStatusTag(status)
    },
    {
      title: '作者',
      dataIndex: 'author',
      key: 'author',
      width: 100
    },
    {
      title: '回复数',
      dataIndex: 'replies',
      key: 'replies',
      width: 80,
      render: (replies) => replies?.length || 0
    },
    {
      title: '创建时间',
      dataIndex: 'created_at',
      key: 'created_at',
      render: (time) => dayjs(time).format('YYYY-MM-DD HH:mm')
    },
    {
      title: '操作',
      key: 'action',
      width: 200,
      render: (_, record) => (
        <Space>
          <Button 
            type="link" 
            size="small"
            icon={<ReplyOutlined />}
            onClick={() => {
              setSelectedComment(record);
              setReplyModalVisible(true);
            }}
          >
            回复
          </Button>
          {record.status === 'open' && (
            <>
              <Button 
                type="link" 
                size="small"
                icon={<CheckCircleOutlined />}
                onClick={() => handleResolveComment(record.comment_id)}
              >
                解决
              </Button>
              <Button 
                type="link" 
                size="small"
                icon={<MinusCircleOutlined />}
                onClick={() => handleDismissComment(record.comment_id)}
              >
                忽略
              </Button>
            </>
          )}
          {record.status !== 'open' && (
            <Button 
              type="link" 
              size="small"
              onClick={() => handleReopenComment(record.comment_id)}
            >
              重新打开
            </Button>
          )}
        </Space>
      )
    }
  ];

  const taskColumns = [
    {
      title: '任务标题',
      dataIndex: 'title',
      key: 'title'
    },
    {
      title: '提交ID',
      dataIndex: 'commit_id',
      key: 'commit_id',
      render: (text) => <Tag>{text}</Tag>
    },
    {
      title: '分配给',
      dataIndex: 'assignee',
      key: 'assignee'
    },
    {
      title: '优先级',
      dataIndex: 'priority',
      key: 'priority',
      width: 80,
      render: (priority) => getPriorityTag(priority)
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status) => getStatusTag(status)
    },
    {
      title: '创建时间',
      dataIndex: 'created_at',
      key: 'created_at',
      render: (time) => dayjs(time).format('YYYY-MM-DD HH:mm')
    },
    {
      title: '操作',
      key: 'action',
      width: 250,
      render: (_, record) => (
        <Space>
          {record.status === 'pending' && (
            <Button 
              type="link" 
              size="small"
              onClick={() => handleUpdateTaskStatus(record.task_id, 'in_progress')}
            >
              开始处理
            </Button>
          )}
          {record.status === 'in_progress' && (
            <>
              <Button 
                type="link" 
                size="small"
                onClick={() => handleUpdateTaskStatus(record.task_id, 'completed')}
              >
                完成
              </Button>
              <Button 
                type="link" 
                size="small"
                danger
                onClick={() => handleUpdateTaskStatus(record.task_id, 'rejected')}
              >
                拒绝
              </Button>
            </>
          )}
        </Space>
      )
    }
  ];

  const tabItems = [
    {
      key: 'comments',
      label: (
        <span>
          <MessageOutlined /> 审查意见 ({commentStats.total})
        </span>
      )
    },
    {
      key: 'tasks',
      label: (
        <span>
          <FileTextOutlined /> 审查任务 ({taskStats.total})
        </span>
      )
    },
    {
      key: 'statistics',
      label: (
        <span>
          <CheckCircleOutlined /> 统计概览
        </span>
      )
    }
  ];

  const renderCommentsTab = () => (
    <div>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="待处理"
              value={commentStats.open}
              valueStyle={{ color: '#faad14' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="已解决"
              value={commentStats.resolved}
              valueStyle={{ color: '#52c41a' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="已忽略"
              value={commentStats.dismissed}
              valueStyle={{ color: '#8c8c8c' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small">
            <Statistic
              title="问题数"
              value={commentStats.byType.issue}
              valueStyle={{ color: '#f5222d' }}
            />
          </Card>
        </Col>
      </Row>

      <Card>
        <Tabs
          defaultActiveKey="all"
          items={[
            { key: 'all', label: `全部 (${commentStats.total})` },
            { key: 'open', label: `待处理 (${commentStats.open})` },
            { key: 'resolved', label: `已解决 (${commentStats.resolved})` },
            { key: 'dismissed', label: `已忽略 (${commentStats.dismissed})` }
          ]}
        />
        
        <Table
          columns={commentColumns}
          dataSource={comments}
          rowKey="comment_id"
          pagination={{ pageSize: 10 }}
          expandable={{
            expandedRowRender: (record) => (
              <div style={{ padding: '0 48px' }}>
                <div style={{ marginBottom: 16 }}>
                  <Text strong>意见内容:</Text>
                  <div style={{ marginTop: 8, padding: 12, background: '#fafafa', borderRadius: 4 }}>
                    {record.content}
                  </div>
                </div>
                
                {record.replies && record.replies.length > 0 && (
                  <div>
                    <Text strong>回复 ({record.replies.length}):</Text>
                    <Timeline
                      style={{ marginTop: 12 }}
                      items={record.replies.map(reply => ({
                        color: 'blue',
                        dot: <Avatar size="small" icon={<UserOutlined />} />,
                        children: (
                          <div>
                            <div style={{ marginBottom: 4 }}>
                              <Space>
                                <Text strong>{reply.author}</Text>
                                <Text type="secondary" style={{ fontSize: 12 }}>
                                  {dayjs(reply.created_at).format('YYYY-MM-DD HH:mm')}
                                </Text>
                              </Space>
                            </div>
                            <div style={{ padding: 8, background: '#f5f5f5', borderRadius: 4 }}>
                              {reply.content}
                            </div>
                          </div>
                        )
                      }))}
                    />
                  </div>
                )}
              </div>
            )
          }}
        />
      </Card>
    </div>
  );

  const renderTasksTab = () => (
    <div>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={4}>
          <Card size="small">
            <Statistic
              title="待处理"
              value={taskStats.pending}
              valueStyle={{ color: '#faad14' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={4}>
          <Card size="small">
            <Statistic
              title="进行中"
              value={taskStats.in_progress}
              valueStyle={{ color: '#1890ff' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={4}>
          <Card size="small">
            <Statistic
              title="已完成"
              value={taskStats.completed}
              valueStyle={{ color: '#52c41a' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={4}>
          <Card size="small">
            <Statistic
              title="已拒绝"
              value={taskStats.rejected}
              valueStyle={{ color: '#f5222d' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={4}>
          <Card size="small">
            <Statistic
              title="总数"
              value={taskStats.total}
              valueStyle={{ color: '#722ed1' }}
            />
          </Card>
        </Col>
      </Row>

      <Card>
        <Table
          columns={taskColumns}
          dataSource={tasks}
          rowKey="task_id"
          pagination={{ pageSize: 10 }}
          expandable={{
            expandedRowRender: (record) => (
              <div style={{ padding: '0 48px' }}>
                <Descriptions column={1}>
                  <Descriptions.Item label="任务描述">
                    <pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>
                      {record.description}
                    </pre>
                  </Descriptions.Item>
                  <Descriptions.Item label="创建时间">
                    {dayjs(record.created_at).format('YYYY-MM-DD HH:mm:ss')}
                  </Descriptions.Item>
                  {record.completed_at && (
                    <Descriptions.Item label="完成时间">
                      {dayjs(record.completed_at).format('YYYY-MM-DD HH:mm:ss')}
                    </Descriptions.Item>
                  )}
                </Descriptions>
              </div>
            )
          }}
        />
      </Card>
    </div>
  );

  const renderStatisticsTab = () => (
    <div>
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card title="意见统计">
            <List
              dataSource={[
                { label: '总意见数', value: commentStats.total, color: '#1890ff' },
                { label: '待处理', value: commentStats.open, color: '#faad14' },
                { label: '已解决', value: commentStats.resolved, color: '#52c41a' },
                { label: '已忽略', value: commentStats.dismissed, color: '#8c8c8c' },
                { label: '问题类型', value: commentStats.byType.issue, color: '#f5222d' },
                { label: '建议类型', value: commentStats.byType.suggestion, color: '#faad14' },
                { label: '评论类型', value: commentStats.byType.comment, color: '#1890ff' }
              ]}
              renderItem={(item) => (
                <List.Item>
                  <div style={{ display: 'flex', justifyContent: 'space-between', width: '100%' }}>
                    <span>{item.label}</span>
                    <Tag color={item.color}>{item.value}</Tag>
                  </div>
                </List.Item>
              )}
            />
            
            <Divider />
            
            <div>
              <div style={{ marginBottom: 8 }}>
                <Text strong>解决率:</Text>
              </div>
              <Progress 
                percent={
                  commentStats.total > 0 
                    ? Math.round((commentStats.resolved / commentStats.total) * 100) 
                    : 0
                }
                strokeColor="#52c41a"
                format={(p) => `${p}% (${commentStats.resolved}/${commentStats.total})`}
              />
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card title="任务统计">
            <List
              dataSource={[
                { label: '总任务数', value: taskStats.total, color: '#1890ff' },
                { label: '待处理', value: taskStats.pending, color: '#faad14' },
                { label: '进行中', value: taskStats.in_progress, color: '#1890ff' },
                { label: '已完成', value: taskStats.completed, color: '#52c41a' },
                { label: '已拒绝', value: taskStats.rejected, color: '#f5222d' }
              ]}
              renderItem={(item) => (
                <List.Item>
                  <div style={{ display: 'flex', justifyContent: 'space-between', width: '100%' }}>
                    <span>{item.label}</span>
                    <Tag color={item.color}>{item.value}</Tag>
                  </div>
                </List.Item>
              )}
            />
            
            <Divider />
            
            <div>
              <div style={{ marginBottom: 8 }}>
                <Text strong>完成率:</Text>
              </div>
              <Progress 
                percent={
                  taskStats.total > 0 
                    ? Math.round((taskStats.completed / taskStats.total) * 100) 
                    : 0
                }
                strokeColor="#52c41a"
                format={(p) => `${p}% (${taskStats.completed}/${taskStats.total})`}
              />
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  );

  return (
    <div>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={tabItems}
      />

      <Card style={{ marginTop: 16 }}>
        {activeTab === 'comments' && renderCommentsTab()}
        {activeTab === 'tasks' && renderTasksTab()}
        {activeTab === 'statistics' && renderStatisticsTab()}
      </Card>

      <Modal
        title="回复意见"
        open={replyModalVisible}
        onCancel={() => setReplyModalVisible(false)}
        onOk={() => replyForm.submit()}
        okText="提交"
        cancelText="取消"
      >
        <Form
          form={replyForm}
          layout="vertical"
          onFinish={handleReplySubmit}
        >
          <div style={{ marginBottom: 16, padding: 12, background: '#fafafa', borderRadius: 4 }}>
            <Text strong>回复:</Text>
            <div style={{ marginTop: 4 }}>
              <Text type="secondary">文件:</Text> {selectedComment?.file_path}
              <Text type="secondary" style={{ marginLeft: 16 }}>
                行号: {selectedComment?.line_start}
              </Text>
            </div>
          </div>
          
          <Form.Item
            name="content"
            label="回复内容"
            rules={[{ required: true, message: '请输入回复内容' }]}
          >
            <Input.TextArea rows={4} placeholder="请输入您的回复..." />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default ReviewComment;
