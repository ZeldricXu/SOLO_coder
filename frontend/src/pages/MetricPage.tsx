import React, { useState, useEffect, useCallback } from 'react';
import {
  Table,
  Button,
  Space,
  Typography,
  Modal,
  Form,
  Input,
  Select,
  Switch,
  message,
  Popconfirm,
  Tag,
  Drawer,
  Tabs,
  Card,
  Row,
  Col,
  Statistic,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  PlayCircleOutlined,
  BarChartOutlined,
} from '@ant-design/icons';
import type { Metric, MetricType, Aggregation, TimeWindow } from '@/types';
import { MetricType as MetricTypeEnum, Aggregation as AggregationEnum, TimeWindow as TimeWindowEnum } from '@/types';
import { metricService } from '@/services/metric';
import { formatDate, formatNumber, formatChangeRate } from '@/utils/format';

const { Title } = Typography;
const { Option } = Select;
const { TextArea } = Input;
const { TabPane } = Tabs;

const MetricPage: React.FC = () => {
  const [metrics, setMetrics] = useState<Metric[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingMetric, setEditingMetric] = useState<Metric | null>(null);
  const [previewDrawerVisible, setPreviewDrawerVisible] = useState(false);
  const [previewData, setPreviewData] = useState<Record<string, unknown>[]>([]);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [comparisonData, setComparisonData] = useState<Record<string, unknown> | null>(null);
  const [comparisonLoading, setComparisonLoading] = useState(false);
  const [selectedMetricId, setSelectedMetricId] = useState<string | null>(null);
  const [form] = Form.useForm();

  const loadMetrics = useCallback(async () => {
    setLoading(true);
    try {
      const res = await metricService.list();
      setMetrics(res.data.data);
    } catch (err) {
      message.error('加载指标列表失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadMetrics();
  }, [loadMetrics]);

  const handleCreate = () => {
    setEditingMetric(null);
    form.resetFields();
    form.setFieldsValue({
      type: MetricTypeEnum.SQL,
      aggregation: AggregationEnum.SUM,
      timeWindow: TimeWindowEnum.DAY,
      isAutoCompare: false,
    });
    setModalVisible(true);
  };

  const handleEdit = (metric: Metric) => {
    setEditingMetric(metric);
    form.setFieldsValue({
      name: metric.name,
      description: metric.description,
      type: metric.type,
      sqlTemplate: metric.sqlTemplate,
      templateId: metric.templateId,
      aggregation: metric.aggregation,
      timeWindow: metric.timeWindow,
      dataSourceId: metric.dataSourceId,
      isAutoCompare: metric.isAutoCompare,
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

  const handlePreview = async (id: string) => {
    setSelectedMetricId(id);
    setPreviewDrawerVisible(true);
    setPreviewLoading(true);
    setComparisonLoading(true);
    try {
      const [previewRes, comparisonRes] = await Promise.all([
        metricService.execute(id),
        metricService.comparison(id, { compareType: 'yoy' }),
      ]);
      setPreviewData(previewRes.data.data);
      setComparisonData(comparisonRes.data.data);
    } catch (err) {
      message.error('加载数据失败');
    } finally {
      setPreviewLoading(false);
      setComparisonLoading(false);
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const data = {
        ...values,
        dimensions: {},
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
      // validation error
    }
  };

  const columns = [
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      render: (type: MetricType) => (
        <Tag color={type === MetricTypeEnum.SQL ? 'blue' : 'green'}>{type}</Tag>
      ),
    },
    {
      title: '聚合方式',
      dataIndex: 'aggregation',
      key: 'aggregation',
      render: (agg: Aggregation) => <Tag>{agg}</Tag>,
    },
    {
      title: '时间窗口',
      dataIndex: 'timeWindow',
      key: 'timeWindow',
      render: (tw: TimeWindow) => <Tag color="orange">{tw}</Tag>,
    },
    {
      title: '同环比',
      dataIndex: 'isAutoCompare',
      key: 'isAutoCompare',
      render: (isAutoCompare: boolean) => (
        <Tag color={isAutoCompare ? 'green' : 'default'}>
          {isAutoCompare ? '开启' : '关闭'}
        </Tag>
      ),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      render: (time: string) => formatDate(time),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_: unknown, record: Metric) => (
        <Space size="small">
          <Button
            type="text"
            size="small"
            icon={<PlayCircleOutlined />}
            onClick={() => handlePreview(record.id)}
          >
            预览
          </Button>
          <Button
            type="text"
            size="small"
            icon={<BarChartOutlined />}
            onClick={() => handlePreview(record.id)}
          >
            同环比
          </Button>
          <Button
            type="text"
            size="small"
            icon={<EditOutlined />}
            onClick={() => handleEdit(record)}
          >
            编辑
          </Button>
          <Popconfirm title="确定删除这个指标吗？" onConfirm={() => handleDelete(record.id)}>
            <Button type="text" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const typeLabelMap: Record<MetricType, string> = {
    [MetricTypeEnum.SQL]: 'SQL查询',
    [MetricTypeEnum.TEMPLATE]: '模板',
  };

  return (
    <div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 24,
        }}
      >
        <Title level={3} style={{ margin: 0 }}>
          指标管理
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
          新建指标
        </Button>
      </div>

      <Table rowKey="id" columns={columns} dataSource={metrics} loading={loading} />

      <Modal
        title={editingMetric ? '编辑指标' : '新建指标'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        okText="确定"
        cancelText="取消"
        width={600}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="指标名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="请输入指标名称" />
          </Form.Item>

          <Form.Item name="description" label="描述">
            <TextArea rows={2} placeholder="请输入描述" />
          </Form.Item>

          <Form.Item name="type" label="指标类型" rules={[{ required: true, message: '请选择类型' }]}>
            <Select>
              {Object.entries(typeLabelMap).map(([value, label]) => (
                <Option key={value} value={value}>
                  {label}
                </Option>
              ))}
            </Select>
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(prev, curr) => prev.type !== curr.type}>
            {({ getFieldValue }) => {
              const type = getFieldValue('type');
              if (type === MetricTypeEnum.SQL) {
                return (
                  <Form.Item
                    name="sqlTemplate"
                    label="SQL模板"
                    rules={[{ required: true, message: '请输入SQL模板' }]}
                  >
                    <TextArea rows={6} placeholder="SELECT ... FROM ... WHERE date = '{{date}}'" />
                  </Form.Item>
                );
              }
              return (
                <Form.Item
                  name="templateId"
                  label="模板ID"
                  rules={[{ required: true, message: '请选择模板' }]}
                >
                  <Select placeholder="请选择模板">
                    <Option value="tpl_1">每日活跃用户</Option>
                    <Option value="tpl_2">订单金额统计</Option>
                  </Select>
                </Form.Item>
              );
            }}
          </Form.Item>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="aggregation" label="聚合方式" rules={[{ required: true }]}>
                <Select>
                  {Object.values(AggregationEnum).map((agg) => (
                    <Option key={agg} value={agg}>
                      {agg}
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="timeWindow" label="时间窗口" rules={[{ required: true }]}>
                <Select>
                  {Object.values(TimeWindowEnum).map((tw) => (
                    <Option key={tw} value={tw}>
                      {tw}
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
          </Row>

          <Form.Item name="dataSourceId" label="数据源" rules={[{ required: true, message: '请选择数据源' }]}>
            <Select placeholder="请选择数据源">
              <Option value="ds_1">MySQL - 业务库</Option>
              <Option value="ds_2">ClickHouse - 分析库</Option>
            </Select>
          </Form.Item>

          <Form.Item name="isAutoCompare" label="自动同环比" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title="指标预览"
        placement="right"
        width={720}
        onClose={() => setPreviewDrawerVisible(false)}
        open={previewDrawerVisible}
      >
        <Tabs defaultActiveKey="preview">
          <TabPane tab="数据预览" key="preview">
            <Card loading={previewLoading}>
              {previewData.length > 0 ? (
                <Table
                  rowKey={(record, index) => String(index)}
                  dataSource={previewData}
                  pagination={false}
                  size="small"
                  columns={
                    previewData[0]
                      ? Object.keys(previewData[0]).map((key) => ({
                          title: key,
                          dataIndex: key,
                          key,
                        }))
                      : []
                  }
                />
              ) : (
                <div style={{ textAlign: 'center', padding: '40px 0', color: '#8c8c8c' }}>
                  暂无数据
                </div>
              )}
            </Card>
          </TabPane>
          <TabPane tab="同环比" key="comparison">
            <Card loading={comparisonLoading}>
              {comparisonData ? (
                <Row gutter={16}>
                  <Col span={12}>
                    <Statistic
                      title="当前值"
                      value={comparisonData.currentValue as number}
                      formatter={(value) => formatNumber(Number(value))}
                    />
                  </Col>
                  <Col span={12}>
                    <Statistic
                      title="同比"
                      value={comparisonData.yoyValue as number}
                      formatter={(value) => {
                        const rate = formatChangeRate(Number(value));
                        return <span style={{ color: rate.color }}>{rate.text}</span>;
                      }}
                    />
                  </Col>
                </Row>
              ) : (
                <div style={{ textAlign: 'center', padding: '40px 0', color: '#8c8c8c' }}>
                  暂无数据
                </div>
              )}
            </Card>
          </TabPane>
        </Tabs>
      </Drawer>
    </div>
  );
};

export default MetricPage;
