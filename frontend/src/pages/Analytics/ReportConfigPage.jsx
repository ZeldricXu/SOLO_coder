import React, { useState, useEffect } from 'react';
import {
  Card,
  Row,
  Col,
  Select,
  Button,
  Form,
  Input,
  Switch,
  Table,
  Tag,
  Space,
  message,
  Spin,
  Modal,
  Popconfirm,
  Tabs,
  List,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  SaveOutlined,
  ReloadOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
  LineChart,
  Line,
  Area,
  AreaChart,
} from 'recharts';
import { eventApi, reportConfigApi, analyticsApi } from '../../services/api';
import dayjs from 'dayjs';

const { Option } = Select;
const { TabPane } = Tabs;

const COLORS = ['#0088FE', '#00C49F', '#FFBB28', '#FF8042', '#8884d8', '#82ca9d'];

const AVAILABLE_DIMENSIONS = [
  { key: 'date', label: '日期', type: 'time', supportIntervals: ['hour', 'day', 'week', 'month'] },
  { key: 'status', label: '报名状态', type: 'category' },
  { key: 'ticket_name', label: '票务类型', type: 'category' },
  { key: 'check_in_status', label: '签到状态', type: 'category' },
];

const AVAILABLE_METRICS = [
  { key: 'count', label: '报名人数', type: 'count', aggregation: 'count' },
  { key: 'registrations', label: '报名数', type: 'count', aggregation: 'count' },
  { key: 'approved', label: '通过数', type: 'count', aggregation: 'sum' },
  { key: 'revenue', label: '收入', type: 'sum', aggregation: 'sum' },
  { key: 'checked_in', label: '签到数', type: 'count', aggregation: 'sum' },
];

const CHART_TYPES = [
  { key: 'bar', label: '柱状图', icon: '📊' },
  { key: 'line', label: '折线图', icon: '📈' },
  { key: 'area', label: '面积图', icon: '📈' },
  { key: 'pie', label: '饼图', icon: '🥧' },
  { key: 'table', label: '数据表格', icon: '📋' },
];

const ReportConfigPage = () => {
  const [loading, setLoading] = useState(false);
  const [events, setEvents] = useState([]);
  const [selectedEvent, setSelectedEvent] = useState(null);
  const [configs, setConfigs] = useState([]);
  const [templates, setTemplates] = useState([]);
  const [activeTab, setActiveTab] = useState('templates');
  
  const [previewData, setPreviewData] = useState(null);
  const [previewChartType, setPreviewChartType] = useState('bar');
  
  const [configModalVisible, setConfigModalVisible] = useState(false);
  const [editingConfig, setEditingConfig] = useState(null);
  const [form] = Form.useForm();
  
  const [selectedDimensions, setSelectedDimensions] = useState([]);
  const [selectedMetrics, setSelectedMetrics] = useState([]);
  const [selectedChartType, setSelectedChartType] = useState('bar');

  useEffect(() => {
    fetchEvents();
    fetchTemplates();
  }, []);

  useEffect(() => {
    if (selectedEvent) {
      fetchConfigs();
    }
  }, [selectedEvent]);

  const fetchEvents = async () => {
    try {
      const res = await eventApi.getEvents();
      setEvents(res.data || []);
      if (res.data && res.data.length > 0) {
        setSelectedEvent(res.data[0].event_id);
      }
    } catch (error) {
      console.error('获取活动列表失败', error);
    }
  };

  const fetchTemplates = async () => {
    try {
      const res = await reportConfigApi.getTemplates();
      setTemplates(res.data || []);
    } catch (error) {
      console.error('获取模板失败', error);
    }
  };

  const fetchConfigs = async () => {
    if (!selectedEvent) return;
    try {
      const res = await reportConfigApi.getConfigs({ eventId: selectedEvent });
      setConfigs(res.data || []);
    } catch (error) {
      console.error('获取配置失败', error);
    }
  };

  const handleCreateConfig = () => {
    setEditingConfig(null);
    setSelectedDimensions([]);
    setSelectedMetrics([]);
    setSelectedChartType('bar');
    form.resetFields();
    setConfigModalVisible(true);
  };

  const handleEditConfig = (config) => {
    setEditingConfig(config);
    setSelectedDimensions(config.dimensions || []);
    setSelectedMetrics(config.metrics || []);
    setSelectedChartType(config.chart_type || 'bar');
    form.setFieldsValue({
      config_name: config.config_name,
      config_type: config.config_type,
      chart_type: config.chart_type,
      is_default: config.is_default,
      is_public: config.is_public,
    });
    setConfigModalVisible(true);
  };

  const handleDeleteConfig = async (configId) => {
    try {
      await reportConfigApi.deleteConfig(configId);
      message.success('删除成功');
      fetchConfigs();
    } catch (error) {
      message.error('删除失败');
    }
  };

  const handleSaveConfig = async () => {
    if (selectedDimensions.length === 0) {
      message.error('请至少选择一个统计维度');
      return;
    }
    if (selectedMetrics.length === 0) {
      message.error('请至少选择一个统计指标');
      return;
    }

    try {
      const values = await form.validateFields();
      const configData = {
        ...values,
        event_id: selectedEvent,
        dimensions: selectedDimensions,
        metrics: selectedMetrics,
        chart_type: selectedChartType,
      };

      if (editingConfig) {
        await reportConfigApi.updateConfig(editingConfig.config_id, configData);
        message.success('更新成功');
      } else {
        await reportConfigApi.createConfig(configData);
        message.success('创建成功');
      }

      setConfigModalVisible(false);
      fetchConfigs();
    } catch (error) {
      console.error('保存配置失败', error);
      message.error('保存失败');
    }
  };

  const handlePreviewReport = async () => {
    if (!selectedEvent) {
      message.error('请先选择活动');
      return;
    }
    if (selectedDimensions.length === 0) {
      message.error('请至少选择一个统计维度');
      return;
    }
    if (selectedMetrics.length === 0) {
      message.error('请至少选择一个统计指标');
      return;
    }

    try {
      setLoading(true);
      const res = await reportConfigApi.generateCustomReport({
        eventId: selectedEvent,
        chartType: selectedChartType,
        dimensions: selectedDimensions,
        metrics: selectedMetrics,
      });
      setPreviewData(res.data);
      setPreviewChartType(selectedChartType);
    } catch (error) {
      message.error('生成报表失败');
    } finally {
      setLoading(false);
    }
  };

  const handleUseTemplate = async (template) => {
    setEditingConfig(null);
    setSelectedDimensions(template.dimensions || []);
    setSelectedMetrics(template.metrics || []);
    setSelectedChartType(template.chart_type || 'bar');
    form.setFieldsValue({
      config_name: template.template_name,
      config_type: template.template_category,
      chart_type: template.chart_type,
      is_default: false,
      is_public: true,
    });
    setConfigModalVisible(true);
  };

  const renderChart = () => {
    if (!previewData || !previewData.data || previewData.data.length === 0) {
      return (
        <div style={{ textAlign: 'center', padding: '60px', color: '#999' }}>
          暂无数据，请点击"预览报表"生成
        </div>
      );
    }

    const data = previewData.data;

    switch (previewChartType) {
      case 'pie':
        const pieData = data.map((item, index) => ({
          name: item[selectedDimensions[0]?.key] || `数据${index + 1}`,
          value: item[selectedMetrics[0]?.key] || 0,
        }));
        return (
          <ResponsiveContainer width="100%" height={400}>
            <PieChart>
              <Pie
                data={pieData}
                cx="50%"
                cy="50%"
                labelLine={false}
                label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
                outerRadius={120}
                fill="#8884d8"
                dataKey="value"
              >
                {pieData.map((entry, index) => (
                  <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                ))}
              </Pie>
              <Tooltip />
              <Legend />
            </PieChart>
          </ResponsiveContainer>
        );

      case 'line':
        return (
          <ResponsiveContainer width="100%" height={400}>
            <LineChart data={data}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey={selectedDimensions[0]?.key || 'x'} />
              <YAxis />
              <Tooltip />
              <Legend />
              {selectedMetrics.map((metric, index) => (
                <Line
                  key={metric.key}
                  type="monotone"
                  dataKey={metric.key}
                  name={metric.label}
                  stroke={COLORS[index % COLORS.length]}
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        );

      case 'area':
        return (
          <ResponsiveContainer width="100%" height={400}>
            <AreaChart data={data}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey={selectedDimensions[0]?.key || 'x'} />
              <YAxis />
              <Tooltip />
              <Legend />
              {selectedMetrics.map((metric, index) => (
                <Area
                  key={metric.key}
                  type="monotone"
                  dataKey={metric.key}
                  name={metric.label}
                  stroke={COLORS[index % COLORS.length]}
                  fill={COLORS[index % COLORS.length]}
                  fillOpacity={0.3}
                />
              ))}
            </AreaChart>
          </ResponsiveContainer>
        );

      case 'table':
        const columns = [
          ...selectedDimensions.map(dim => ({
            title: dim.label,
            dataIndex: dim.key,
            key: dim.key,
          })),
          ...selectedMetrics.map(metric => ({
            title: metric.label,
            dataIndex: metric.key,
            key: metric.key,
          })),
        ];
        return (
          <Table
            columns={columns}
            dataSource={data}
            rowKey={(r, i) => i}
            pagination={false}
          />
        );

      case 'bar':
      default:
        return (
          <ResponsiveContainer width="100%" height={400}>
            <BarChart data={data}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey={selectedDimensions[0]?.key || 'x'} />
              <YAxis />
              <Tooltip />
              <Legend />
              {selectedMetrics.map((metric, index) => (
                <Bar
                  key={metric.key}
                  dataKey={metric.key}
                  name={metric.label}
                  fill={COLORS[index % COLORS.length]}
                />
              ))}
            </BarChart>
          </ResponsiveContainer>
        );
    }
  };

  const configColumns = [
    {
      title: '配置名称',
      dataIndex: 'config_name',
      key: 'config_name',
    },
    {
      title: '配置类型',
      dataIndex: 'config_type',
      key: 'config_type',
      render: (type) => (
        <Tag color="blue">{type}</Tag>
      ),
    },
    {
      title: '图表类型',
      dataIndex: 'chart_type',
      key: 'chart_type',
      render: (type) => {
        const chartType = CHART_TYPES.find(c => c.key === type);
        return <Tag>{chartType?.label || type}</Tag>;
      },
    },
    {
      title: '默认配置',
      dataIndex: 'is_default',
      key: 'is_default',
      render: (isDefault) => (
        isDefault ? <Tag color="green">是</Tag> : <Tag>否</Tag>
      ),
    },
    {
      title: '操作',
      key: 'action',
      render: (_, config) => (
        <Space>
          <Button
            type="link"
            icon={<EditOutlined />}
            onClick={() => handleEditConfig(config)}
          >
            编辑
          </Button>
          <Popconfirm
            title="确定删除此配置吗？"
            onConfirm={() => handleDeleteConfig(config.config_id)}
          >
            <Button type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className="report-config-page">
      <div className="page-header" style={{ marginBottom: 16 }}>
        <Row gutter={16} align="middle">
          <Col>
            <h2>报表配置</h2>
          </Col>
          <Col>
            <Select
              style={{ width: 300 }}
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
          </Col>
        </Row>
      </div>

      <Row gutter={16}>
        <Col xs={24} lg={14}>
          <Tabs activeKey={activeTab} onChange={setActiveTab}>
            <TabPane tab="预设模板" key="templates">
              <List
                grid={{ gutter: 16, column: 2 }}
                dataSource={templates}
                renderItem={(template) => (
                  <List.Item>
                    <Card
                      hoverable
                      actions={[
                        <Button
                          type="link"
                          icon={<EyeOutlined />}
                          onClick={() => handleUseTemplate(template)}
                        >
                          使用模板
                        </Button>,
                      ]}
                    >
                      <Card.Meta
                        title={template.template_name}
                        description={
                          <Space direction="vertical" size="small" style={{ width: '100%' }}>
                            <span>{template.description || '暂无描述'}</span>
                            <Space>
                              <Tag color="blue">{template.template_category}</Tag>
                              <Tag>{CHART_TYPES.find(c => c.key === template.chart_type)?.label}</Tag>
                            </Space>
                          </Space>
                        }
                      />
                    </Card>
                  </List.Item>
                )}
              />
            </TabPane>

            <TabPane tab="我的配置" key="configs">
              <Card
                extra={
                  <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={handleCreateConfig}
                  >
                    新建配置
                  </Button>
                }
              >
                <Table
                  columns={configColumns}
                  dataSource={configs}
                  rowKey="config_id"
                  pagination={false}
                />
              </Card>
            </TabPane>
          </Tabs>
        </Col>

        <Col xs={24} lg={10}>
          <Card title="报表预览">
            <div style={{ marginBottom: 16 }}>
              <Row gutter={16}>
                <Col span={12}>
                  <div style={{ marginBottom: 8 }}>
                    <strong>统计维度：</strong>
                  </div>
                  <Select
                    mode="multiple"
                    style={{ width: '100%' }}
                    placeholder="选择统计维度"
                    value={selectedDimensions.map(d => d.key)}
                    onChange={(values) => {
                      setSelectedDimensions(
                        values.map(v => AVAILABLE_DIMENSIONS.find(d => d.key === v))
                          .filter(Boolean)
                      );
                    }}
                  >
                    {AVAILABLE_DIMENSIONS.map(dim => (
                      <Option key={dim.key} value={dim.key}>
                        {dim.label}
                      </Option>
                    ))}
                  </Select>
                </Col>
                <Col span={12}>
                  <div style={{ marginBottom: 8 }}>
                    <strong>统计指标：</strong>
                  </div>
                  <Select
                    mode="multiple"
                    style={{ width: '100%' }}
                    placeholder="选择统计指标"
                    value={selectedMetrics.map(m => m.key)}
                    onChange={(values) => {
                      setSelectedMetrics(
                        values.map(v => AVAILABLE_METRICS.find(m => m.key === v))
                          .filter(Boolean)
                      );
                    }}
                  >
                    {AVAILABLE_METRICS.map(metric => (
                      <Option key={metric.key} value={metric.key}>
                        {metric.label}
                      </Option>
                    ))}
                  </Select>
                </Col>
              </Row>
            </div>

            <div style={{ marginBottom: 16 }}>
              <Row gutter={16}>
                <Col span={12}>
                  <div style={{ marginBottom: 8 }}>
                    <strong>图表类型：</strong>
                  </div>
                  <Select
                    style={{ width: '100%' }}
                    value={selectedChartType}
                    onChange={setSelectedChartType}
                  >
                    {CHART_TYPES.map(chart => (
                      <Option key={chart.key} value={chart.key}>
                        {chart.icon} {chart.label}
                      </Option>
                    ))}
                  </Select>
                </Col>
                <Col span={12}>
                  <div style={{ marginBottom: 8 }}>
                    <strong>操作：</strong>
                  </div>
                  <Space>
                    <Button
                      type="primary"
                      icon={<ReloadOutlined />}
                      onClick={handlePreviewReport}
                      loading={loading}
                    >
                      预览报表
                    </Button>
                    <Button
                      icon={<SaveOutlined />}
                      onClick={handleCreateConfig}
                    >
                      保存为配置
                    </Button>
                  </Space>
                </Col>
              </Row>
            </div>

            <Divider />

            <Spin spinning={loading}>
              {renderChart()}
            </Spin>
          </Card>
        </Col>
      </Row>

      <Modal
        title={editingConfig ? '编辑报表配置' : '新建报表配置'}
        open={configModalVisible}
        onCancel={() => setConfigModalVisible(false)}
        onOk={handleSaveConfig}
        width={800}
      >
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="config_name"
                label="配置名称"
                rules={[{ required: true, message: '请输入配置名称' }]}
              >
                <Input placeholder="请输入配置名称" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="config_type"
                label="配置类型"
                initialValue="custom"
              >
                <Select>
                  <Option value="overview">总览</Option>
                  <Option value="registration_trend">报名趋势</Option>
                  <Option value="ticket_sales">票务销售</Option>
                  <Option value="checkin_stats">签到统计</Option>
                  <Option value="custom">自定义</Option>
                </Select>
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="is_default"
                label="设为默认配置"
                valuePropName="checked"
                initialValue={false}
              >
                <Switch />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="is_public"
                label="公开配置"
                valuePropName="checked"
                initialValue={true}
              >
                <Switch />
              </Form.Item>
            </Col>
          </Row>

          <Divider />

          <div style={{ marginBottom: 16 }}>
            <strong>已选择的统计维度：</strong>
            <Space wrap style={{ marginLeft: 8 }}>
              {selectedDimensions.map(dim => (
                <Tag key={dim.key} closable onClose={() => {
                  setSelectedDimensions(selectedDimensions.filter(d => d.key !== dim.key));
                }}>
                  {dim.label}
                </Tag>
              ))}
            </Space>
          </div>

          <div style={{ marginBottom: 16 }}>
            <strong>已选择的统计指标：</strong>
            <Space wrap style={{ marginLeft: 8 }}>
              {selectedMetrics.map(metric => (
                <Tag key={metric.key} color="blue" closable onClose={() => {
                  setSelectedMetrics(selectedMetrics.filter(m => m.key !== metric.key));
                }}>
                  {metric.label}
                </Tag>
              ))}
            </Space>
          </div>

          <div>
            <strong>图表类型：</strong>
            <Space wrap style={{ marginLeft: 8 }}>
              {CHART_TYPES.map(chart => (
                <Tag
                  key={chart.key}
                  color={selectedChartType === chart.key ? 'blue' : 'default'}
                  style={{ cursor: 'pointer' }}
                  onClick={() => setSelectedChartType(chart.key)}
                >
                  {chart.icon} {chart.label}
                </Tag>
              ))}
            </Space>
          </div>
        </Form>
      </Modal>
    </div>
  );
};

export default ReportConfigPage;
