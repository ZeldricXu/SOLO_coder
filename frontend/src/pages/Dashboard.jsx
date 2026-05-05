import React, { useState, useEffect } from 'react';
import {
  Card,
  Row,
  Col,
  Statistic,
  Progress,
  List,
  Avatar,
  Tag,
  Button,
  Space,
  Spin,
  message,
  Divider
} from 'antd';
import {
  CodeOutlined,
  CheckCircleOutlined,
  WarningOutlined,
  LineChartOutlined,
  FileTextOutlined,
  ArrowRightOutlined,
  SyncOutlined
} from '@ant-design/icons';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  AreaChart,
  Area
} from 'recharts';
import { reportApi, reviewApi } from '../services/api';
import useAppStore from '../store/appStore';

const mockTrendData = [
  { date: '05-01', score: 75, complexity: 70, lint: 80, duplicate: 90 },
  { date: '05-02', score: 78, complexity: 72, lint: 82, duplicate: 88 },
  { date: '05-03', score: 82, complexity: 80, lint: 85, duplicate: 85 },
  { date: '05-04', score: 79, complexity: 75, lint: 83, duplicate: 88 },
  { date: '05-05', score: 85, complexity: 82, lint: 88, duplicate: 92 },
];

const mockRecentTasks = [
  {
    id: '1',
    title: '审查任务 - 用户认证功能',
    commit_id: 'commit_abc123',
    assignee: 'reviewer_01',
    status: 'in_progress',
    priority: 'high'
  },
  {
    id: '2',
    title: '审查任务 - 数据库优化',
    commit_id: 'commit_def456',
    assignee: 'reviewer_02',
    status: 'pending',
    priority: 'medium'
  },
  {
    id: '3',
    title: '审查任务 - API重构',
    commit_id: 'commit_ghi789',
    assignee: 'reviewer_01',
    status: 'completed',
    priority: 'low'
  },
];

function Dashboard() {
  const [loading, setLoading] = useState(false);
  const [trendData, setTrendData] = useState(mockTrendData);
  const [statistics, setStatistics] = useState({
    tasks: { total: 15, pending: 5, in_progress: 3, completed: 7 },
    comments: { total: 42, open: 12, resolved: 28, dismissed: 2 }
  });
  
  const { getOverallScore } = useAppStore();

  const getStatusTag = (status) => {
    const statusMap = {
      'pending': { color: 'orange', text: '待处理' },
      'in_progress': { color: 'blue', text: '进行中' },
      'completed': { color: 'green', text: '已完成' },
      'rejected': { color: 'red', text: '已拒绝' }
    };
    const info = statusMap[status] || statusMap['pending'];
    return <Tag color={info.color}>{info.text}</Tag>;
  };

  const getPriorityTag = (priority) => {
    const priorityMap = {
      'high': { color: 'red', text: '高优先级' },
      'medium': { color: 'orange', text: '中优先级' },
      'low': { color: 'green', text: '低优先级' }
    };
    const info = priorityMap[priority] || priorityMap['medium'];
    return <Tag color={info.color}>{info.text}</Tag>;
  };

  const getScoreColor = (score) => {
    if (score >= 80) return '#52c41a';
    if (score >= 60) return '#1890ff';
    if (score >= 40) return '#faad14';
    return '#f5222d';
  };

  return (
    <div>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card hoverable>
            <Statistic
              title="总审查任务"
              value={statistics.tasks.total}
              prefix={<FileTextOutlined />}
              valueStyle={{ color: '#1890ff' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card hoverable>
            <Statistic
              title="待处理任务"
              value={statistics.tasks.pending}
              prefix={<WarningOutlined />}
              valueStyle={{ color: '#faad14' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card hoverable>
            <Statistic
              title="已解决意见"
              value={statistics.comments.resolved}
              prefix={<CheckCircleOutlined />}
              valueStyle={{ color: '#52c41a' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card hoverable>
            <Statistic
              title="解决率"
              value={Math.round(statistics.comments.resolved / statistics.comments.total * 100)}
              suffix="%"
              prefix={<LineChartOutlined />}
              valueStyle={{ color: '#722ed1' }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={16}>
          <Card 
            title="质量趋势" 
            extra={
              <Tag icon={<SyncOutlined spin />}>最近5天</Tag>
            }
          >
            <div className="chart-container">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={trendData}>
                  <defs>
                    <linearGradient id="colorScore" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#1890ff" stopOpacity={0.3}/>
                      <stop offset="95%" stopColor="#1890ff" stopOpacity={0}/>
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                  <XAxis dataKey="date" stroke="#999" />
                  <YAxis stroke="#999" domain={[0, 100]} />
                  <Tooltip 
                    contentStyle={{ 
                      backgroundColor: 'white', 
                      border: '1px solid #f0f0f0',
                      borderRadius: 8
                    }}
                  />
                  <Area 
                    type="monotone" 
                    dataKey="score" 
                    stroke="#1890ff" 
                    fillOpacity={1} 
                    fill="url(#colorScore)"
                    strokeWidth={2}
                    name="总体评分"
                  />
                  <Line 
                    type="monotone" 
                    dataKey="complexity" 
                    stroke="#faad14" 
                    strokeWidth={2}
                    dot={false}
                    name="复杂度评分"
                  />
                  <Line 
                    type="monotone" 
                    dataKey="lint" 
                    stroke="#52c41a" 
                    strokeWidth={2}
                    dot={false}
                    name="规范评分"
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card title="代码质量评分">
            <div style={{ textAlign: 'center', padding: 24 }}>
              <div 
                className="score-circle"
                style={{ 
                  background: `conic-gradient(${getScoreColor(85)} ${85 * 3.6}deg, #f0f0f0 0deg)`,
                  margin: '0 auto 16px'
                }}
              >
                <span style={{ 
                  background: 'white', 
                  borderRadius: '50%',
                  width: 64,
                  height: 64,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center'
                }}>
                  <span style={{ fontSize: 28, fontWeight: 'bold', color: getScoreColor(85) }}>
                    85
                  </span>
                </span>
              </div>
              <div style={{ color: '#666', marginBottom: 24 }}>
                总体质量评分
              </div>
            </div>
            
            <Divider />
            
            <div>
              <div style={{ marginBottom: 16 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <span>复杂度评分</span>
                  <span style={{ fontWeight: 'bold', color: '#faad14' }}>82</span>
                </div>
                <Progress percent={82} strokeColor="#faad14" showInfo={false} />
              </div>
              
              <div style={{ marginBottom: 16 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <span>规范检测评分</span>
                  <span style={{ fontWeight: 'bold', color: '#52c41a' }}>88</span>
                </div>
                <Progress percent={88} strokeColor="#52c41a" showInfo={false} />
              </div>
              
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <span>重复代码评分</span>
                  <span style={{ fontWeight: 'bold', color: '#722ed1' }}>92</span>
                </div>
                <Progress percent={92} strokeColor="#722ed1" showInfo={false} />
              </div>
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card 
            title="最近审查任务"
            extra={
              <Button type="link" size="small">
                查看全部 <ArrowRightOutlined />
              </Button>
            }
          >
            <List
              dataSource={mockRecentTasks}
              renderItem={(item) => (
                <List.Item
                  actions={[
                    getStatusTag(item.status),
                    getPriorityTag(item.priority)
                  ]}
                >
                  <List.Item.Meta
                    avatar={
                      <Avatar style={{ backgroundColor: '#1890ff' }}>
                        <CodeOutlined />
                      </Avatar>
                    }
                    title={item.title}
                    description={
                      <Space>
                        <span style={{ color: '#999' }}>
                          提交ID: {item.commit_id}
                        </span>
                        <span style={{ color: '#999' }}>
                          分配给: {item.assignee}
                        </span>
                      </Space>
                    }
                  />
                </List.Item>
              )}
            />
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card 
            title="快速操作"
          >
            <Space direction="vertical" style={{ width: '100%' }}>
              <Button 
                type="primary" 
                block 
                size="large"
                icon={<CodeOutlined />}
              >
                查看代码变更
              </Button>
              <Button 
                block 
                size="large"
                icon={<LineChartOutlined />}
              >
                查看分析结果
              </Button>
              <Button 
                block 
                size="large"
                icon={<FileTextOutlined />}
              >
                生成质量报告
              </Button>
              <Button 
                block 
                size="large"
                icon={<MessageOutlined />}
              >
                管理审查意见
              </Button>
            </Space>
          </Card>
        </Col>
      </Row>
    </div>
  );
}

export default Dashboard;
