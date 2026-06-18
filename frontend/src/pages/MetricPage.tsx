import React, { useState, useEffect } from 'react';
import {
  Card,
  Button,
  Table,
  Space,
  Modal,
  Form,
  Input,
  Select,
  Tag,
  message,
  Popconfirm,
  Tabs,
  Switch,
  Typography,
  Row,
  Col,
  Divider,
  Empty,
  Collapse,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  PlayCircleOutlined,
  FileTextOutlined,
  CodeOutlined,
  ReloadOutlined,
  CheckCircleOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { metricService } from '@/services/metric';
import { dataSourceService } from '@/services/data-source';
import { tenantService } from '@/services/tenant';
import type {
  Metric,
  MetricType,
  DataSource,
  BusinessLine,
  Aggregation,
  TimeWindow,
} from '@/types';

const { TextArea } = Input;
const { Option } = Select;
const { Tabs: AntTabs, TabPane } = Tabs;
const { Text, Title } = Typography;
const { Panel } = Collapse;

const METRIC_TYPE_CONFIG: Record<MetricType, { label: string; icon: React.ReactNode }> = {
  SQL: { label: 'SQL查询', icon: <CodeOutlined /> },
  TEMPLATE: { label: '模板', icon: <FileTextOutlined /> },
};

const AGGREGATION_OPTIONS: { value: Aggregation; label: string }[] = [
  { value: 'SUM', label: '求和 (SUM)' },
  { value: 'COUNT', label: '计数 (COUNT)' },
  { value: 'AVG', label: '平均值 (AVG)' },
  { value: 'MAX', label: '最大值 (MAX)' },
  { value: 'MIN', label: '最小值 (MIN)' },
  { value: 'NONE', label: '无聚合' },
];

const TIME_WINDOW_OPTIONS: { value: TimeWindow; label: string }[] = [
  { value: 'HOUR', label: '小时' },
  { value: 'DAY', label: '天' },
  { value: 'WEEK', label: '周' },
  { value: 'MONTH', label: '月' },
];

const SQL_TEMPLATES = [
  {
    name: '基础求和查询',
    sql: `SELECT
  date_trunc('{time_window}', created_at) as date,
  SUM({metric_field}) as value,
  {dimension_field} as category
FROM {table}
WHERE created_at BETWEEN '{{startTime}}' AND '{{endTime}}'
GROUP BY 1, 3
ORDER BY 1`,
  },
  {
    name: '条件计数',
    sql: `SELECT
  date_trunc('{time_window}', created_at) as date,
  COUNT(CASE WHEN {condition} THEN 1 END) as value,
  {dimension_field} as category
FROM {table}
WHERE created_at BETWEEN '{{startTime}}' AND '{{endTime}}'
GROUP BY 1, 3
ORDER BY 1`,
  },
  {
    name: '环比对比',
    sql: `SELECT
  date_trunc('{time_window}', created_at) as date,
  SUM({metric_field}) as value,
  LAG(SUM({metric_field}), 1) OVER (ORDER BY date_trunc('{time_window}', created_at)) as prev_value,
  {dimension_field} as category
FROM {table}
WHERE created_at BETWEEN '{{startTime}}' AND '{{endTime}}'
GROUP BY 1, 4
ORDER BY 1`,
  },
];

const MetricPage: React.FC = () => {
  const [metrics, setMetrics] = useState<Metric[]>([]);
  const [dataSources, setDataSources] = useState<DataSource[]>([]);
  const [businessLines, setBusinessLines] = useState<BusinessLine[]>([]);
  const [templates, setTemplates] = useState<Record<string, unknown>[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingMetric, setEditingMetric] = useState<Metric | null>(null);
  const [previewVisible, setPreviewVisible] = useState(false);
  const [previewData, setPreviewData] = useState<Record<string, unknown>[]>([]);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('list');
  const [form] = Form.useForm();

  const loadMetrics = async (businessLineId?: string) => {
    setLoading(true);
    try {
      const res = await metricService.list(businessLineId);
      setMetrics(res.data.data);
    } catch (err) {
      message.error('加载指标列表失败');
    } finally {
      setLoading(false);
    }
  };

  const loadDataSources = async () => {
    try {
      const res = await dataSourceService.list();
      setDataSources(res.data.data);
    } catch (err) {
      console.error('加载数据源失败', err);
    }
  };

  const loadBusinessLines = async () => {
    try {
      const res = await tenantService.listBusinessLines('default');
      setBusinessLines(res.data.data);
    } catch (err) {
      console.error('加载业务线失败', err);
    }
  };

  const loadTemplates = async () => {
    try {
      const res = await metricService.templates();
      setTemplates(res.data.data);
    } catch (err) {
      console.error('加载模板失败', err);
    }
  };

  useEffect(() => {
    loadMetrics();
    loadDataSources();
    loadBusinessLines();
    loadTemplates();
  }, []);

  const handleCreate = () => {
    setEditingMetric(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleEdit = (record: Metric) => {
    setEditingMetric(record);
    form.setFieldsValue({
      ...record,
      dimensions: JSON.stringify(record.dimensions, null, 2),
    });
    setModalVisible(true);
  };

  const handleDelete = async (id: string) => {
    try {
      await metricService.delete(id);
      message.success('删除成功');
      loadMetrics();
    } catch (err) {
      message.error('删除失败');
    }
  };

  const handlePreview = async (record: Metric) => {
    setPreviewVisible(true);
    setPreviewLoading(true);
    try {
      const res = await metricService.execute(record.id, {
        startTime: dayjs().subtract(7, 'day').toISOString(),
        endTime: dayjs().toISOString(),
      });
      setPreviewData(res.data.data);
    } catch (err) {
      message.error('预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

  const handlePreviewWithForm = async () => {
    const values = await form.validateFields();
    setPreviewLoading(true);
    try {
      if (editingMetric) {
        await metricService.update(editingMetric.id, values);
        const res = await metricService.execute(editingMetric.id, {
          startTime: dayjs().subtract(7, 'day').toISOString(),
          endTime: dayjs().toISOString(),
        });
        setPreviewData(res.data.data);
      }
    } catch (err) {
      message.error('预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const data = {
        ...values,
        dimensions: values.dimensions ? JSON.parse(values.dimensions) : {},
      };

      if (editingMetric) {
        await metricService.update(editingMetric.id, data);
        message.success('更新成功');
      } else {
        await metricService.create(data);
        message.success('创建成功');
      }
      setModalVisible(false);
      loadMetrics();
    } catch (err) {
      console.error(err);
    }
  };

  const applyTemplate = (templateSql: string) => {
    form.setFieldsValue({ sqlTemplate: templateSql });
  };

  const selectedType = Form.useWatch('type', form);
  const selectedDataSource = Form.useWatch('dataSourceId', form);

  const columns: ColumnsType<Metric> = [
    {
      title: '指标名称',
      dataIndex: 'name',
      key: 'name',
      render: (text, record) => (
        <Space>
          {METRIC_TYPE_CONFIG[record.type].icon}
          {text}
        </Space>
      ),
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      render: (type: MetricType) => {
        const config = METRIC_TYPE_CONFIG[type];
        return <Tag color="blue">{config.label}</Tag>;
      },
    },
    {
      title: '数据源',
      dataIndex: 'dataSourceId',
      key: 'dataSourceId',
      render: (id) => {
        const ds = dataSources.find((d) => d.id === id);
        return ds?.name || '-';
      },
    },
    {
      title: '聚合方式',
      dataIndex: 'aggregation',
      key: 'aggregation',
      render: (agg: Aggregation) => {
        const opt = AGGREGATION_OPTIONS.find((o) => o.value === agg);
        return opt?.label || agg;
      },
    },
    {
      title: '时间窗口',
      dataIndex: 'timeWindow',
      key: 'timeWindow',
      render: (tw: TimeWindow) => {
        const opt = TIME_WINDOW_OPTIONS.find((o) => o.value === tw);
        return opt?.label || tw;
      },
    },
    {
      title: '环比对比',
      dataIndex: 'isAutoCompare',
      key: 'isAutoCompare',
      render: (isAuto) =>
        isAuto ? (
          <Tag color="green">
            <CheckCircleOutlined /> 已开启
          </Tag>
        ) : (
          <Tag color="default">未开启</Tag>
        ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (date) => dayjs(date).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_, record) => (
        <Space size="middle">
          <Button
            type="link"
            icon={<PlayCircleOutlined />}
            onClick={() => handlePreview(record)}
          >
            预览
          </Button>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            编辑
          </Button>
          <Popconfirm title="确定删除?" onConfirm={() => handleDelete(record.id)}>
            <Button type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Card
        title="指标管理"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => loadMetrics()}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
              新建指标
            </Button>
          </Space>
        }
      >
        <AntTabs activeKey={activeTab} onChange={setActiveTab}>
          <TabPane tab="指标列表" key="list">
            <div style={{ marginBottom: 16 }}>
              <Space>
                <Select
                  placeholder="选择业务线"
                  style={{ width: 200 }}
                  allowClear
                  onChange={(value) => loadMetrics(value)}
                >
                  {businessLines.map((bl) => (
                    <Option key={bl.id} value={bl.id}>
                      {bl.name}
                    </Option>
                  ))}
                </Select>
                <Select placeholder="指标类型" style={{ width: 150 }} allowClear>
                  {Object.entries(METRIC_TYPE_CONFIG).map(([type, config]) => (
                    <Option key={type} value={type}>
                      {config.label}
                    </Option>
                  ))}
                </Select>
              </Space>
            </div>
            <Table columns={columns} dataSource={metrics} rowKey="id" loading={loading} />
          </TabPane>

          <TabPane tab="模板库" key="templates">
            <Row gutter={[16, 16]}>
              {SQL_TEMPLATES.map((template, idx) => (
                <Col span={12} key={idx}>
                  <Card
                    size="small"
                    title={template.name}
                    extra={
                      <Button
                        type="primary"
                        size="small"
                        onClick={() => {
                          form.setFieldsValue({
                            type: 'SQL',
                            sqlTemplate: template.sql,
                          });
                          setModalVisible(true);
                        }}
                      >
                        使用模板
                      </Button>
                    }
                  >
                    <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 4, fontSize: 12, overflow: 'auto', maxHeight: 200 }}>
                      {template.sql}
                    </pre>
                  </Card>
                </Col>
              ))}
            </Row>
          </TabPane>
        </AntTabs>
      </Card>

      <Modal
        title={editingMetric ? '编辑指标' : '新建指标'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        destroyOnClose
        width={900}
        footer={
          <Space>
            <Button onClick={handlePreviewWithForm} icon={<PlayCircleOutlined />}>
              预览执行
            </Button>
            <Button onClick={() => setModalVisible(false)}>取消</Button>
            <Button type="primary" onClick={handleSubmit}>
              确定
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="name"
                label="指标名称"
                rules={[{ required: true, message: '请输入指标名称' }]}
              >
                <Input placeholder="请输入指标名称" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="type"
                label="指标类型"
                rules={[{ required: true, message: '请选择指标类型' }]}
              >
                <Select placeholder="请选择指标类型">
                  {Object.entries(METRIC_TYPE_CONFIG).map(([type, config]) => (
                    <Option key={type} value={type}>
                      <Space>
                        {config.icon}
                        {config.label}
                      </Space>
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
          </Row>

          <Form.Item name="description" label="描述">
            <TextArea rows={2} placeholder="请输入指标描述" />
          </Form.Item>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="dataSourceId"
                label="数据源"
                rules={[{ required: true, message: '请选择数据源' }]}
              >
                <Select placeholder="请选择数据源" showSearch optionFilterProp="children">
                  {dataSources.map((ds) => (
                    <Option key={ds.id} value={ds.id}>
                      {ds.name} ({ds.type})
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="businessLineId"
                label="业务线"
                rules={[{ required: true, message: '请选择业务线' }]}
              >
                <Select placeholder="请选择业务线">
                  {businessLines.map((bl) => (
                    <Option key={bl.id} value={bl.id}>
                      {bl.name}
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
          </Row>

          {selectedType === 'SQL' ? (
            <>
              {templates.length > 0 && (
                <Collapse ghost>
                  <Panel header="快速选择模板" key="1">
                    <Space wrap>
                      {templates.map((tpl: Record<string, unknown>, idx) => (
                        <Tag
                          key={idx}
                          color="blue"
                          style={{ cursor: 'pointer', padding: '4px 12px' }}
                          onClick={() => applyTemplate((tpl.sql as string) || '')}
                        >
                          {tpl.name as string}
                        </Tag>
                      ))}
                    </Space>
                  </Panel>
                </Collapse>
              )}
              <Form.Item
                name="sqlTemplate"
                label="SQL模板"
                rules={[{ required: true, message: '请输入SQL模板' }]}
              >
                <TextArea
                  rows={10}
                  placeholder="SELECT ... FROM ... WHERE created_at BETWEEN '{{startTime}}' AND '{{endTime}}'"
                  style={{ fontFamily: 'monospace', fontSize: 13 }}
                />
              </Form.Item>
              <Text type="secondary">
                支持变量: {'{startTime}'}, {'{endTime}'}, {'{time_window}'}, {'{business_line_id}'}
              </Text>
            </>
          ) : selectedType === 'TEMPLATE' ? (
            <Form.Item
              name="templateId"
              label="选择模板"
              rules={[{ required: true, message: '请选择模板' }]}
            >
              <Select placeholder="请选择模板">
                {templates.map((tpl: Record<string, unknown>) => (
                  <Option key={tpl.id as string} value={tpl.id as string}>
                    {tpl.name as string}
                  </Option>
                ))}
              </Select>
            </Form.Item>
          ) : null}

          <Divider orientation="left">高级配置</Divider>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="aggregation"
                label="聚合方式"
                rules={[{ required: true, message: '请选择聚合方式' }]}
              >
                <Select placeholder="请选择聚合方式">
                  {AGGREGATION_OPTIONS.map((opt) => (
                    <Option key={opt.value} value={opt.value}>
                      {opt.label}
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="timeWindow"
                label="时间窗口"
                rules={[{ required: true, message: '请选择时间窗口' }]}
              >
                <Select placeholder="请选择时间窗口">
                  {TIME_WINDOW_OPTIONS.map((opt) => (
                    <Option key={opt.value} value={opt.value}>
                      {opt.label}
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
          </Row>

          <Form.Item
            name="isAutoCompare"
            label="自动环比对比"
            valuePropName="checked"
            initialValue={false}
          >
            <Switch />
          </Form.Item>

          <Form.Item name="dimensions" label="维度配置 (JSON)">
            <TextArea
              rows={4}
              placeholder='{"group_by": ["region", "product"], "filters": {"status": "active"}}'
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="预览执行结果"
        open={previewVisible}
        onCancel={() => setPreviewVisible(false)}
        footer={null}
        width={800}
      >
        {previewLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}>
            <div className="ant-spin ant-spin-spinning">
              <span className="ant-spin-dot ant-spin-dot-spin">
                <i></i>
                <i></i>
                <i></i>
                <i></i>
              </span>
            </div>
          </div>
        ) : previewData.length > 0 ? (
          <div style={{ maxHeight: 400, overflow: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {Object.keys(previewData[0]).map((key) => (
                    <th key={key} style={headerStyle}>
                      {key}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {previewData.map((row, idx) => (
                  <tr key={idx}>
                    {Object.values(row).map((value, vIdx) => (
                      <td key={vIdx} style={cellStyle}>
                        {String(value)}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty description="暂无数据" />
        )}
      </Modal>
    </div>
  );
};

const headerStyle: React.CSSProperties = {
  padding: '12px',
  textAlign: 'left',
  borderBottom: '1px solid #f0f0f0',
  backgroundColor: '#fafafa',
};

const cellStyle: React.CSSProperties = {
  padding: '12px',
  borderBottom: '1px solid #f0f0f0',
};

export default MetricPage;
