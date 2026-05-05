import React, { useState, useEffect } from 'react';
import {
  Card,
  Row,
  Col,
  Statistic,
  Progress,
  Table,
  Tag,
  DatePicker,
  Space,
  Button,
  Tabs,
  Empty,
  Descriptions,
  List,
  Divider,
  Typography
} from 'antd';
import {
  LineChartOutlined,
  BarChartOutlined,
  FileTextOutlined,
  CheckCircleOutlined,
  WarningOutlined,
  ArrowUpOutlined,
  ArrowDownOutlined,
  DownloadOutlined,
  ReloadOutlined
} from '@ant-design/icons';
import {
  LineChart,
  Line,
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
  BarChart,
  Bar,
  PieChart,
  Pie,
  Cell
} from 'recharts';
import dayjs from 'dayjs';

const { Title, Text, Paragraph } = Typography;
const { RangePicker } = DatePicker;

const mockTrendData = [
  { date: '04-28', overall: 65, complexity: 60, lint: 70, duplicate: 80 },
  { date: '04-29', overall: 68, complexity: 62, lint: 72, duplicate: 78 },
  { date: '04-30', overall: 72, complexity: 68, lint: 75, duplicate: 82 },
  { date: '05-01', overall: 70, complexity: 65, lint: 73, duplicate: 80 },
  { date: '05-02', overall: 75, complexity: 72, lint: 78, duplicate: 85 },
  { date: '05-03', overall: 78, complexity: 75, lint: 80, duplicate: 88 },
  { date: '05-04', overall: 77, complexity: 74, lint: 79, duplicate: 86 },
  { date: '05-05', overall: 82, complexity: 80, lint: 85, duplicate: 92 },
];

const mockReportData = [
  {
    report_id: 'report_001',
    repo_id: 'repo_project_01',
    commit_id: 'commit_abc123',
    overall_score: 82,
    complexity_score: 80,
    lint_score: 85,
    duplicate_score: 92,
    total_issues: 8,
    resolved_issues: 3,
    generated_at: '2026-05-05T14:30:00Z',
    report_data: {
      commit: {
        commit_id: 'commit_abc123',
        message: '添加用户认证功能',
        author: 'developer_01'
      }
    }
  },
  {
    report_id: 'report_002',
    repo_id: 'repo_project_01',
    commit_id: 'commit_def456',
    overall_score: 77,
    complexity_score: 74,
    lint_score: 79,
    duplicate_score: 86,
    total_issues: 12,
    resolved_issues: 5,
    generated_at: '2026-05-04T16:00:00Z',
    report_data: {
      commit: {
        commit_id: 'commit_def456',
        message: '数据库优化',
        author: 'developer_02'
      }
    }
  },
  {
    report_id: 'report_003',
    repo_id: 'repo_project_01',
    commit_id: 'commit_ghi789',
    overall_score: 78,
    complexity_score: 75,
    lint_score: 80,
    duplicate_score: 88,
    total_issues: 10,
    resolved_issues: 7,
    generated_at: '2026-05-03T10:00:00Z',
    report_data: {
      commit: {
        commit_id: 'commit_ghi789',
        message: 'API重构',
        author: 'developer_01'
      }
    }
  }
];

const COLORS = ['#1890ff', '#52c41a', '#faad14', '#722ed1'];

function QualityReport() {
  const [activeTab, setActiveTab] = useState('trend');
  const [trendData, setTrendData] = useState(mockTrendData);
  const [reports, setReports] = useState(mockReportData);
  const [dateRange, setDateRange] = useState(null);
  const [selectedReport, setSelectedReport] = useState(null);

  const latestReport = reports[0];
  const previousReport = reports[1];

  const getScoreColor = (score) => {
    if (score >= 80) return '#52c41a';
    if (score >= 60) return '#1890ff';
    if (score >= 40) return '#faad14';
    return '#f5222d';
  };

  const getScoreLevel = (score) => {
    if (score >= 80) return '优秀';
    if (score >= 60) return '良好';
    if (score >= 40) return '一般';
    return '较差';
  };

  const getScoreDescription = (score) => {
    if (score >= 80) return '代码质量优秀，符合最佳实践规范';
    if (score >= 60) return '代码质量良好，仅有少量建议性问题';
    if (score >= 40) return '代码质量一般，存在一些需要关注的问题';
    return '代码质量较差，存在较多严重问题需要修复';
  };

  const compareScore = (current, previous) => {
    if (!previous) return { change: 0, trend: 'none' };
    const change = current - previous;
    return {
      change: Math.abs(change),
      trend: change > 0 ? 'up' : change < 0 ? 'down' : 'none'
    };
  };

  const pieData = [
    { name: '复杂度评分', value: latestReport?.complexity_score || 0 },
    { name: '规范检测', value: latestReport?.lint_score || 0 },
    { name: '重复检测', value: latestReport?.duplicate_score || 0 }
  ].filter(d => d.value > 0);

  const reportTableColumns = [
    {
      title: '报告ID',
      dataIndex: 'report_id',
      key: 'report_id',
      render: (text) => <Tag>{text}</Tag>
    },
    {
      title: '提交信息',
      dataIndex: 'report_data',
      key: 'commit_message',
      render: (data) => data?.commit?.message || '-'
    },
    {
      title: '作者',
      dataIndex: 'report_data',
      key: 'author',
      render: (data) => data?.commit?.author || '-'
    },
    {
      title: '总体评分',
      dataIndex: 'overall_score',
      key: 'overall_score',
      render: (score) => (
        <Tag color={getScoreColor(score)} style={{ fontSize: 14, fontWeight: 'bold' }}>
          {score}
        </Tag>
      )
    },
    {
      title: '问题数',
      key: 'issues',
      render: (_, record) => (
        <Space>
          <Tag color="orange">{record.total_issues} 个问题</Tag>
          <Tag color="green">{record.resolved_issues} 已解决</Tag>
        </Space>
      )
    },
    {
      title: '生成时间',
      dataIndex: 'generated_at',
      key: 'generated_at',
      render: (time) => dayjs(time).format('YYYY-MM-DD HH:mm')
    }
  ];

  const tabItems = [
    {
      key: 'trend',
      label: (
        <span>
          <LineChartOutlined /> 质量趋势
        </span>
      )
    },
    {
      key: 'latest',
      label: (
        <span>
          <FileTextOutlined /> 最新报告
        </span>
      )
    },
    {
      key: 'history',
      label: (
        <span>
          <BarChartOutlined /> 历史报告
        </span>
      )
    }
  ];

  const renderTrendTab = () => (
    <div>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24}>
          <Card>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
              <div>
                <Title level={5}>质量趋势分析</Title>
                <Text type="secondary">显示最近8天的质量评分变化趋势</Text>
              </div>
              <Space>
                <RangePicker 
                  onChange={(dates) => setDateRange(dates)}
                  style={{ width: 300 }}
                />
                <Button icon={<ReloadOutlined />}>刷新</Button>
                <Button type="primary" icon={<DownloadOutlined />}>导出</Button>
              </Space>
            </div>
            
            <div className="chart-container" style={{ height: 400 }}>
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={trendData}>
                  <defs>
                    <linearGradient id="colorOverall" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#1890ff" stopOpacity={0.3}/>
                      <stop offset="95%" stopColor="#1890ff" stopOpacity={0}/>
                    </linearGradient>
                    <linearGradient id="colorComplexity" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#faad14" stopOpacity={0.3}/>
                      <stop offset="95%" stopColor="#faad14" stopOpacity={0}/>
                    </linearGradient>
                    <linearGradient id="colorLint" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#52c41a" stopOpacity={0.3}/>
                      <stop offset="95%" stopColor="#52c41a" stopOpacity={0}/>
                    </linearGradient>
                    <linearGradient id="colorDuplicate" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#722ed1" stopOpacity={0.3}/>
                      <stop offset="95%" stopColor="#722ed1" stopOpacity={0}/>
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="date" />
                  <YAxis domain={[40, 100]} />
                  <Tooltip 
                    contentStyle={{ 
                      backgroundColor: 'white',
                      border: '1px solid #f0f0f0',
                      borderRadius: 8
                    }}
                  />
                  <Legend />
                  <Area 
                    type="monotone" 
                    dataKey="overall" 
                    stroke="#1890ff" 
                    fill="url(#colorOverall)"
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
                    name="规范检测"
                  />
                  <Line 
                    type="monotone" 
                    dataKey="duplicate" 
                    stroke="#722ed1" 
                    strokeWidth={2}
                    dot={false}
                    name="重复检测"
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card title="每日评分变化">
            <div className="chart-container" style={{ height: 300 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={trendData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="date" />
                  <YAxis domain={[40, 100]} />
                  <Tooltip />
                  <Legend />
                  <Bar dataKey="overall" fill="#1890ff" name="总体评分" />
                  <Bar dataKey="complexity" fill="#faad14" name="复杂度评分" />
                  <Bar dataKey="lint" fill="#52c41a" name="规范检测" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card title="各维度评分分布">
            <div className="chart-container" style={{ height: 300 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart 
                  data={[
                    { name: '最新', complexity: latestReport?.complexity_score || 0, lint: latestReport?.lint_score || 0, duplicate: latestReport?.duplicate_score || 0 },
                    { name: '上一次', complexity: previousReport?.complexity_score || 0, lint: previousReport?.lint_score || 0, duplicate: previousReport?.duplicate_score || 0 }
                  ]}
                  layout="vertical"
                >
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis type="number" domain={[0, 100]} />
                  <YAxis dataKey="name" type="category" />
                  <Tooltip />
                  <Legend />
                  <Bar dataKey="complexity" fill="#faad14" name="复杂度评分" />
                  <Bar dataKey="lint" fill="#52c41a" name="规范检测" />
                  <Bar dataKey="duplicate" fill="#722ed1" name="重复检测" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  );

  const renderLatestTab = () => {
    if (!latestReport) {
      return <Empty description="暂无报告数据" />;
    }

    const comparison = compareScore(latestReport.overall_score, previousReport?.overall_score);

    return (
      <div>
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={10}>
            <Card>
              <div style={{ textAlign: 'center', padding: 24 }}>
                <div 
                  className="score-circle"
                  style={{ 
                    background: `conic-gradient(${getScoreColor(latestReport.overall_score)} ${latestReport.overall_score * 3.6}deg, #f0f0f0 0deg)`,
                    margin: '0 auto 16px'
                  }}
                >
                  <span style={{ 
                    background: 'white', 
                    borderRadius: '50%',
                    width: 100,
                    height: 100,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    flexDirection: 'column'
                  }}>
                    <span style={{ fontSize: 36, fontWeight: 'bold', color: getScoreColor(latestReport.overall_score) }}>
                      {latestReport.overall_score}
                    </span>
                    <span style={{ fontSize: 12, color: '#999' }}>
                      {getScoreLevel(latestReport.overall_score)}
                    </span>
                  </span>
                </div>
                
                <div style={{ marginTop: 16 }}>
                  {comparison.trend !== 'none' && (
                    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 8 }}>
                      {comparison.trend === 'up' ? (
                        <Tag icon={<ArrowUpOutlined />} color="green">
                          较上次提升 {comparison.change} 分
                        </Tag>
                      ) : (
                        <Tag icon={<ArrowDownOutlined />} color="red">
                          较上次下降 {comparison.change} 分
                        </Tag>
                      )}
                    </div>
                  )}
                </div>
              </div>
              
              <Divider />
              
              <Descriptions column={1} size="small">
                <Descriptions.Item label="报告ID">
                  <Tag>{latestReport.report_id}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="提交ID">
                  <Tag>{latestReport.commit_id}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="提交信息">
                  {latestReport.report_data?.commit?.message || '-'}
                </Descriptions.Item>
                <Descriptions.Item label="作者">
                  {latestReport.report_data?.commit?.author || '-'}
                </Descriptions.Item>
                <Descriptions.Item label="生成时间">
                  {dayjs(latestReport.generated_at).format('YYYY-MM-DD HH:mm:ss')}
                </Descriptions.Item>
              </Descriptions>
            </Card>
          </Col>

          <Col xs={24} lg={14}>
            <Card title="评分详情">
              <Row gutter={[16, 16]}>
                <Col xs={12}>
                  <Card size="small">
                    <Statistic
                      title="复杂度评分"
                      value={latestReport.complexity_score}
                      valueStyle={{ color: '#faad14' }}
                      suffix="/ 100"
                    />
                    <Progress 
                      percent={latestReport.complexity_score} 
                      strokeColor="#faad14"
                      showInfo={false}
                      style={{ marginTop: 8 }}
                    />
                  </Card>
                </Col>
                <Col xs={12}>
                  <Card size="small">
                    <Statistic
                      title="规范检测评分"
                      value={latestReport.lint_score}
                      valueStyle={{ color: '#52c41a' }}
                      suffix="/ 100"
                    />
                    <Progress 
                      percent={latestReport.lint_score} 
                      strokeColor="#52c41a"
                      showInfo={false}
                      style={{ marginTop: 8 }}
                    />
                  </Card>
                </Col>
                <Col xs={12}>
                  <Card size="small">
                    <Statistic
                      title="重复检测评分"
                      value={latestReport.duplicate_score}
                      valueStyle={{ color: '#722ed1' }}
                      suffix="/ 100"
                    />
                    <Progress 
                      percent={latestReport.duplicate_score} 
                      strokeColor="#722ed1"
                      showInfo={false}
                      style={{ marginTop: 8 }}
                    />
                  </Card>
                </Col>
                <Col xs={12}>
                  <Card size="small">
                    <Statistic
                      title="问题解决率"
                      value={
                        latestReport.total_issues > 0 
                          ? Math.round((latestReport.resolved_issues / latestReport.total_issues) * 100)
                          : 100
                      }
                      valueStyle={{ color: '#1890ff' }}
                      suffix="%"
                    />
                    <div style={{ marginTop: 8 }}>
                      <Tag color="orange">{latestReport.total_issues} 个问题</Tag>
                      <Tag color="green">{latestReport.resolved_issues} 已解决</Tag>
                    </div>
                  </Card>
                </Col>
              </Row>
            </Card>

            <Card title="评分分布" style={{ marginTop: 16 }}>
              <div className="chart-container" style={{ height: 250 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie
                      data={pieData}
                      cx="50%"
                      cy="50%"
                      innerRadius={60}
                      outerRadius={100}
                      fill="#8884d8"
                      paddingAngle={5}
                      dataKey="value"
                      label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
                    >
                      {pieData.map((entry, index) => (
                        <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                      ))}
                    </Pie>
                    <Tooltip />
                  </PieChart>
                </ResponsiveContainer>
              </div>
            </Card>
          </Col>
        </Row>

        <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
          <Col xs={24}>
            <Card title="报告摘要">
              <div style={{ padding: 16 }}>
                <Paragraph>
                  <Text strong>总体评价:</Text> {getScoreDescription(latestReport.overall_score)}
                </Paragraph>
                
                <Divider />
                
                <div style={{ marginBottom: 16 }}>
                  <Title level={5}>优势:</Title>
                  <List
                    dataSource={[
                      '代码重复率低，复用性良好',
                      '代码规范整体符合要求',
                      '问题解决率较高'
                    ]}
                    renderItem={(item) => (
                      <List.Item>
                        <CheckCircleOutlined style={{ color: '#52c41a', marginRight: 8 }} />
                        {item}
                      </List.Item>
                    )}
                  />
                </div>
                
                <div>
                  <Title level={5}>改进建议:</Title>
                  <List
                    dataSource={[
                      '部分函数复杂度较高，建议拆分',
                      '存在少量命名规范警告',
                      '建议增加更多单元测试覆盖'
                    ]}
                    renderItem={(item) => (
                      <List.Item>
                        <WarningOutlined style={{ color: '#faad14', marginRight: 8 }} />
                        {item}
                      </List.Item>
                    )}
                  />
                </div>
              </div>
            </Card>
          </Col>
        </Row>
      </div>
    );
  };

  const renderHistoryTab = () => (
    <div>
      <Card title="历史报告列表">
        <Table
          columns={reportTableColumns}
          dataSource={reports}
          rowKey="report_id"
          pagination={{ pageSize: 10 }}
          expandable={{
            expandedRowRender: (record) => (
              <div style={{ padding: '0 48px' }}>
                <Descriptions column={3} size="small">
                  <Descriptions.Item label="复杂度评分">
                    <Progress 
                      percent={record.complexity_score} 
                      strokeColor="#faad14"
                      format={(p) => <span style={{ color: '#faad14' }}>{p}</span>}
                    />
                  </Descriptions.Item>
                  <Descriptions.Item label="规范检测评分">
                    <Progress 
                      percent={record.lint_score} 
                      strokeColor="#52c41a"
                      format={(p) => <span style={{ color: '#52c41a' }}>{p}</span>}
                    />
                  </Descriptions.Item>
                  <Descriptions.Item label="重复检测评分">
                    <Progress 
                      percent={record.duplicate_score} 
                      strokeColor="#722ed1"
                      format={(p) => <span style={{ color: '#722ed1' }}>{p}</span>}
                    />
                  </Descriptions.Item>
                </Descriptions>
              </div>
            )
          }}
        />
      </Card>
    </div>
  );

  return (
    <div>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} sm={6}>
          <Card size="small">
            <Statistic
              title="当前评分"
              value={latestReport?.overall_score || 0}
              valueStyle={{ color: getScoreColor(latestReport?.overall_score || 0) }}
              prefix={<CheckCircleOutlined />}
              suffix="/ 100"
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card size="small">
            <Statistic
              title="平均评分"
              value={Math.round(reports.reduce((sum, r) => sum + r.overall_score, 0) / reports.length)}
              valueStyle={{ color: '#1890ff' }}
              prefix={<BarChartOutlined />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card size="small">
            <Statistic
              title="总报告数"
              value={reports.length}
              valueStyle={{ color: '#52c41a' }}
              prefix={<FileTextOutlined />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card size="small">
            <Statistic
              title="总问题数"
              value={reports.reduce((sum, r) => sum + r.total_issues, 0)}
              valueStyle={{ color: '#faad14' }}
              prefix={<WarningOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={tabItems}
      />

      <Card style={{ marginTop: 16 }}>
        {activeTab === 'trend' && renderTrendTab()}
        {activeTab === 'latest' && renderLatestTab()}
        {activeTab === 'history' && renderHistoryTab()}
      </Card>
    </div>
  );
}

export default QualityReport;
