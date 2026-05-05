import React, { useState, useEffect } from 'react';
import {
  Card,
  Row,
  Col,
  Table,
  Tag,
  Progress,
  Tabs,
  Empty,
  Statistic,
  Descriptions,
  Divider,
  Space,
  Button,
  List,
  Collapse
} from 'antd';
import {
  BarChartOutlined,
  WarningOutlined,
  FileTextOutlined,
  CodeOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  CopyOutlined
} from '@ant-design/icons';
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  RadarChart,
  PolarGrid,
  PolarAngleAxis,
  PolarRadiusAxis,
  Radar,
  Legend
} from 'recharts';

const mockComplexityData = {
  analysis_id: 'analysis_001',
  commit_id: 'commit_abc123',
  overall_score: 72,
  analyzed_at: '2026-05-04T14:15:00Z',
  status: 'completed',
  files: [
    {
      file_path: 'src/main.py',
      language: 'python',
      total_functions: 3,
      avg_cyclomatic: 4.5,
      complexity_score: 65,
      status: 'acceptable',
      functions: [
        { name: 'register', cyclomatic: 5, lines: 30, params: 2, is_above_threshold: false },
        { name: 'login', cyclomatic: 6, lines: 35, params: 2, is_above_threshold: false },
        { name: 'validate_token', cyclomatic: 3, lines: 15, params: 1, is_above_threshold: false }
      ]
    },
    {
      file_path: 'src/utils.py',
      language: 'python',
      total_functions: 3,
      avg_cyclomatic: 1.5,
      complexity_score: 90,
      status: 'excellent',
      functions: [
        { name: 'validate_email', cyclomatic: 1, lines: 5, params: 1, is_above_threshold: false },
        { name: 'format_date', cyclomatic: 2, lines: 10, params: 1, is_above_threshold: false },
        { name: 'generate_random_string', cyclomatic: 1, lines: 4, params: 1, is_above_threshold: false }
      ]
    }
  ]
};

const mockLintData = {
  commit_id: 'commit_abc123',
  score: 85,
  statistics: {
    total: 8,
    errors: 1,
    warnings: 5,
    infos: 2
  },
  files: {
    'src/main.py': [
      {
        id: 1,
        rule_id: 'C0103',
        severity: 'warning',
        line: 8,
        column: 1,
        message: 'Variable name "users" doesn\'t conform to naming convention',
        source: 'naming'
      },
      {
        id: 2,
        rule_id: 'E0001',
        severity: 'error',
        line: 45,
        column: 20,
        message: 'Missing return statement',
        source: 'error'
      },
      {
        id: 3,
        rule_id: 'W0613',
        severity: 'warning',
        line: 25,
        column: 4,
        message: 'Unused argument "request"',
        source: 'warning'
      }
    ],
    'src/utils.py': [
      {
        id: 4,
        rule_id: 'C0114',
        severity: 'info',
        line: 1,
        column: 1,
        message: 'Missing module docstring',
        source: 'docstring'
      },
      {
        id: 5,
        rule_id: 'W0212',
        severity: 'warning',
        line: 15,
        column: 8,
        message: 'Access to a protected member',
        source: 'warning'
      }
    ]
  }
};

const mockDuplicateData = {
  commit_id: 'commit_abc123',
  score: 95,
  statistics: {
    total_duplicates: 1,
    avg_similarity: 75.5,
    total_duplicate_lines: 25,
    files_involved: 2
  },
  duplicates: [
    {
      id: 1,
      file_path1: 'src/main.py',
      function_name1: 'validate_input',
      file_path2: 'src/utils.py',
      function_name2: 'validate_email',
      similarity: 75.5,
      lines_count: 25,
      fragment1: 'def validate_input(data):\n    if not data:\n        return None\n    return data',
      fragment2: 'def validate_email(email):\n    if not email:\n        return None\n    return email'
    }
  ]
};

function AnalysisResult() {
  const [activeTab, setActiveTab] = useState('complexity');
  const [complexityData, setComplexityData] = useState(mockComplexityData);
  const [lintData, setLintData] = useState(mockLintData);
  const [duplicateData, setDuplicateData] = useState(mockDuplicateData);

  const getScoreColor = (score) => {
    if (score >= 80) return '#52c41a';
    if (score >= 60) return '#1890ff';
    if (score >= 40) return '#faad14';
    return '#f5222d';
  };

  const getStatusTag = (status) => {
    const statusMap = {
      'excellent': { color: 'green', text: '优秀' },
      'acceptable': { color: 'blue', text: '可接受' },
      'needs_attention': { color: 'orange', text: '需关注' },
      'critical': { color: 'red', text: '严重' }
    };
    const info = statusMap[status] || statusMap['acceptable'];
    return <Tag color={info.color}>{info.text}</Tag>;
  };

  const getSeverityIcon = (severity) => {
    const iconMap = {
      'error': <CloseCircleOutlined style={{ color: '#f5222d' }} />,
      'warning': <ExclamationCircleOutlined style={{ color: '#faad14' }} />,
      'info': <InfoCircleOutlined style={{ color: '#1890ff' }} />
    };
    return iconMap[severity] || <InfoCircleOutlined />;
  };

  const complexityBarData = complexityData.files.map(f => ({
    name: f.file_path.split('/').pop(),
    score: f.complexity_score,
    avg_cyclomatic: f.avg_cyclomatic,
    functions: f.total_functions
  }));

  const radarData = [
    { subject: '复杂度评分', A: complexityData.overall_score, fullMark: 100 },
    { subject: '规范检测', A: lintData.score, fullMark: 100 },
    { subject: '重复检测', A: duplicateData.score, fullMark: 100 },
    { subject: '代码行数', A: 85, fullMark: 100 },
    { subject: '文档完善', A: 70, fullMark: 100 }
  ];

  const functionTableColumns = [
    {
      title: '函数名',
      dataIndex: 'name',
      key: 'name',
      render: (text) => <code style={{ background: '#f5f5f5', padding: '2px 6px', borderRadius: 4 }}>{text}</code>
    },
    {
      title: '圈复杂度',
      dataIndex: 'cyclomatic',
      key: 'cyclomatic',
      render: (val) => (
        <Tag color={val > 10 ? 'red' : 'green'}>{val}</Tag>
      )
    },
    {
      title: '代码行数',
      dataIndex: 'lines',
      key: 'lines'
    },
    {
      title: '参数数量',
      dataIndex: 'params',
      key: 'params'
    },
    {
      title: '状态',
      dataIndex: 'is_above_threshold',
      key: 'is_above_threshold',
      render: (val) => val ? (
        <Tag color="red">超标</Tag>
      ) : (
        <Tag color="green">正常</Tag>
      )
    }
  ];

  const lintTableColumns = [
    {
      title: '级别',
      dataIndex: 'severity',
      key: 'severity',
      width: 80,
      render: (val) => (
        <Tag color={
          val === 'error' ? 'red' : 
          val === 'warning' ? 'orange' : 'blue'
        }>
          {val === 'error' ? '错误' : val === 'warning' ? '警告' : '信息'}
        </Tag>
      )
    },
    {
      title: '行号',
      dataIndex: 'line',
      key: 'line',
      width: 80
    },
    {
      title: '规则ID',
      dataIndex: 'rule_id',
      key: 'rule_id',
      render: (text) => <code>{text}</code>
    },
    {
      title: '消息',
      dataIndex: 'message',
      key: 'message',
      ellipsis: true
    }
  ];

  const tabItems = [
    {
      key: 'complexity',
      label: (
        <span>
          <BarChartOutlined /> 复杂度分析
        </span>
      )
    },
    {
      key: 'lint',
      label: (
        <span>
          <WarningOutlined /> 规范检测
        </span>
      )
    },
    {
      key: 'duplicate',
      label: (
        <span>
          <CopyOutlined /> 重复检测
        </span>
      )
    },
    {
      key: 'overview',
      label: (
        <span>
          <FileTextOutlined /> 综合分析
        </span>
      )
    }
  ];

  const renderComplexityTab = () => (
    <div>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="总体复杂度评分"
              value={complexityData.overall_score}
              valueStyle={{ color: getScoreColor(complexityData.overall_score) }}
              prefix={<BarChartOutlined />}
              suffix="/ 100"
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="分析文件数"
              value={complexityData.files.length}
              valueStyle={{ color: '#1890ff' }}
              prefix={<FileTextOutlined />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic
              title="函数总数"
              value={complexityData.files.reduce((sum, f) => sum + f.total_functions, 0)}
              valueStyle={{ color: '#52c41a' }}
              prefix={<CodeOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} lg={12}>
          <Card title="文件复杂度分布">
            <div className="chart-container" style={{ height: 250 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={complexityBarData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" />
                  <YAxis domain={[0, 100]} />
                  <Tooltip />
                  <Bar dataKey="score" fill="#1890ff" name="复杂度评分" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card title="平均圈复杂度">
            <div className="chart-container" style={{ height: 250 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={complexityBarData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" />
                  <YAxis />
                  <Tooltip />
                  <Bar dataKey="avg_cyclomatic" fill="#faad14" name="平均圈复杂度" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Card>
        </Col>
      </Row>

      <Card title="函数复杂度详情" style={{ marginTop: 16 }}>
        <Collapse defaultActiveKey={['0']}>
          {complexityData.files.map((file, index) => (
            <Collapse.Panel 
              key={index}
              header={
                <div style={{ display: 'flex', justifyContent: 'space-between', width: '100%', alignItems: 'center' }}>
                  <Space>
                    <FileTextOutlined />
                    <span>{file.file_path}</span>
                    {getStatusTag(file.status)}
                  </Space>
                  <Space>
                    <Tag color="blue">{file.total_functions} 个函数</Tag>
                    <Tag color={getScoreColor(file.complexity_score)}>
                      评分: {file.complexity_score}
                    </Tag>
                  </Space>
                </div>
              }
            >
              <Table
                columns={functionTableColumns}
                dataSource={file.functions}
                rowKey="name"
                size="small"
                pagination={false}
              />
            </Collapse.Panel>
          ))}
        </Collapse>
      </Card>
    </div>
  );

  const renderLintTab = () => (
    <div>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={6}>
          <Card>
            <Statistic
              title="规范检测评分"
              value={lintData.score}
              valueStyle={{ color: getScoreColor(lintData.score) }}
              prefix={<CheckCircleOutlined />}
              suffix="/ 100"
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card>
            <Statistic
              title="总问题数"
              value={lintData.statistics.total}
              valueStyle={{ color: '#1890ff' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card>
            <Statistic
              title="错误"
              value={lintData.statistics.errors}
              valueStyle={{ color: '#f5222d' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card>
            <Statistic
              title="警告"
              value={lintData.statistics.warnings}
              valueStyle={{ color: '#faad14' }}
            />
          </Card>
        </Col>
      </Row>

      <Card title="规范检测详情" style={{ marginTop: 16 }}>
        <Collapse defaultActiveKey={['0']}>
          {Object.entries(lintData.files).map(([filePath, issues], index) => (
            <Collapse.Panel
              key={index}
              header={
                <div style={{ display: 'flex', justifyContent: 'space-between', width: '100%' }}>
                  <Space>
                    <FileTextOutlined />
                    <span>{filePath}</span>
                  </Space>
                  <Tag color={issues.some(i => i.severity === 'error') ? 'red' : 'orange'}>
                    {issues.length} 个问题
                  </Tag>
                </div>
              }
            >
              <Table
                columns={lintTableColumns}
                dataSource={issues}
                rowKey="id"
                size="small"
                pagination={false}
              />
            </Collapse.Panel>
          ))}
        </Collapse>
      </Card>
    </div>
  );

  const renderDuplicateTab = () => (
    <div>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={6}>
          <Card>
            <Statistic
              title="重复检测评分"
              value={duplicateData.score}
              valueStyle={{ color: getScoreColor(duplicateData.score) }}
              prefix={<CheckCircleOutlined />}
              suffix="/ 100"
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card>
            <Statistic
              title="重复片段数"
              value={duplicateData.statistics.total_duplicates}
              valueStyle={{ color: '#1890ff' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card>
            <Statistic
              title="平均相似度"
              value={duplicateData.statistics.avg_similarity}
              suffix="%"
              valueStyle={{ color: '#faad14' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={6}>
          <Card>
            <Statistic
              title="涉及文件数"
              value={duplicateData.statistics.files_involved}
              valueStyle={{ color: '#722ed1' }}
            />
          </Card>
        </Col>
      </Row>

      <Card title="重复代码详情" style={{ marginTop: 16 }}>
        {duplicateData.duplicates.length > 0 ? (
          <List
            dataSource={duplicateData.duplicates}
            renderItem={(item) => (
              <List.Item>
                <Card size="small">
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <Space>
                        <CopyOutlined style={{ color: '#faad14' }} />
                        <Text strong>{item.file_path1}</Text>
                        {item.function_name1 && <Tag color="blue">{item.function_name1}</Tag>}
                        <Text type="secondary">vs</Text>
                        <Text strong>{item.file_path2}</Text>
                        {item.function_name2 && <Tag color="blue">{item.function_name2}</Tag>}
                      </Space>
                      <Tag color={item.similarity > 80 ? 'red' : 'orange'}>
                        相似度: {item.similarity}%
                      </Tag>
                    </div>
                    <Row gutter={16}>
                      <Col span={12}>
                        <Card size="small" title="片段1" style={{ background: '#fff7e6' }}>
                          <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontSize: 12 }}>
                            {item.fragment1}
                          </pre>
                        </Card>
                      </Col>
                      <Col span={12}>
                        <Card size="small" title="片段2" style={{ background: '#fff7e6' }}>
                          <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontSize: 12 }}>
                            {item.fragment2}
                          </pre>
                        </Card>
                      </Col>
                    </Row>
                  </Space>
                </Card>
              </List.Item>
            )}
          />
        ) : (
          <Empty description="未检测到重复代码" />
        )}
      </Card>
    </div>
  );

  const renderOverviewTab = () => (
    <div>
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={10}>
          <Card title="综合质量雷达图">
            <div className="chart-container" style={{ height: 350 }}>
              <ResponsiveContainer width="100%" height="100%">
                <RadarChart data={radarData}>
                  <PolarGrid />
                  <PolarAngleAxis dataKey="subject" />
                  <PolarRadiusAxis angle={30} domain={[0, 100]} />
                  <Radar
                    name="评分"
                    dataKey="A"
                    stroke="#1890ff"
                    fill="#1890ff"
                    fillOpacity={0.6}
                  />
                  <Legend />
                  <Tooltip />
                </RadarChart>
              </ResponsiveContainer>
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={14}>
          <Card title="分析摘要">
            <Descriptions bordered column={2}>
              <Descriptions.Item label="复杂度评分" span={1}>
                <Progress 
                  percent={complexityData.overall_score} 
                  strokeColor={getScoreColor(complexityData.overall_score)}
                  format={(p) => <span style={{ color: getScoreColor(complexityData.overall_score) }}>{p}分</span>}
                />
              </Descriptions.Item>
              <Descriptions.Item label="规范检测评分" span={1}>
                <Progress 
                  percent={lintData.score} 
                  strokeColor={getScoreColor(lintData.score)}
                  format={(p) => <span style={{ color: getScoreColor(lintData.score) }}>{p}分</span>}
                />
              </Descriptions.Item>
              <Descriptions.Item label="重复检测评分" span={1}>
                <Progress 
                  percent={duplicateData.score} 
                  strokeColor={getScoreColor(duplicateData.score)}
                  format={(p) => <span style={{ color: getScoreColor(duplicateData.score) }}>{p}分</span>}
                />
              </Descriptions.Item>
              <Descriptions.Item label="总体评分" span={1}>
                <Progress 
                  percent={77} 
                  strokeColor={getScoreColor(77)}
                  format={(p) => <span style={{ color: getScoreColor(77), fontSize: 16, fontWeight: 'bold' }}>{p}分</span>}
                />
              </Descriptions.Item>
              <Descriptions.Item label="问题汇总" span={2}>
                <Space>
                  <Tag color="red">{lintData.statistics.errors} 个错误</Tag>
                  <Tag color="orange">{lintData.statistics.warnings} 个警告</Tag>
                  <Tag color="blue">{duplicateData.statistics.total_duplicates} 处重复</Tag>
                  <Tag color="purple">{complexityData.files.reduce((sum, f) => sum + f.functions.filter(fu => fu.is_above_threshold).length, 0)} 个超标函数</Tag>
                </Space>
              </Descriptions.Item>
            </Descriptions>
          </Card>

          <Card title="建议" style={{ marginTop: 16 }}>
            <List
              dataSource={[
                { type: 'error', content: '修复 main.py 第45行的缺少返回语句问题' },
                { type: 'warning', content: '检查 naming 规范警告，建议使用蛇形命名法' },
                { type: 'info', content: '考虑抽取 validate_input 和 validate_email 的公共逻辑' },
                { type: 'info', content: '建议为 utils.py 添加模块文档字符串' }
              ]}
              renderItem={(item) => (
                <List.Item>
                  <Space>
                    {item.type === 'error' && <CloseCircleOutlined style={{ color: '#f5222d' }} />}
                    {item.type === 'warning' && <ExclamationCircleOutlined style={{ color: '#faad14' }} />}
                    {item.type === 'info' && <InfoCircleOutlined style={{ color: '#1890ff' }} />}
                    <span>{item.content}</span>
                  </Space>
                </List.Item>
              )}
            />
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
        {activeTab === 'complexity' && renderComplexityTab()}
        {activeTab === 'lint' && renderLintTab()}
        {activeTab === 'duplicate' && renderDuplicateTab()}
        {activeTab === 'overview' && renderOverviewTab()}
      </Card>
    </div>
  );
}

export default AnalysisResult;
