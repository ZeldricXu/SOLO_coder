import React, { useState, useEffect, useMemo } from 'react';
import {
  Steps,
  Card,
  Row,
  Col,
  Form,
  Select,
  Input,
  Button,
  Space,
  Table,
  Tag,
  Divider,
  Alert,
  InputNumber,
  DatePicker,
  Tooltip,
  Switch,
  Result,
  message,
} from 'antd';
import {
  DatabaseOutlined,
  TableOutlined,
  CalculatorOutlined,
  FieldTimeOutlined,
  FilterOutlined,
  CheckCircleOutlined,
  ReloadOutlined,
  EyeOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  metricBuilderApi,
  VisualMetricConfig,
  SchemaTable,
  PreviewResult,
} from '@/services/metric-builder';
import { dataSourceApi } from '@/services/data-source';
import dayjs, { Dayjs } from 'dayjs';

const { RangePicker } = DatePicker;
const { Step } = Steps;
const { Option } = Select;
const { TextArea } = Input;

interface Props {
  onCreated?: (metric: any) => void;
  onCancel?: () => void;
}

const AGG_OPTIONS: { label: string; value: VisualMetricConfig['aggregation']; desc: string }[] = [
  { label: 'SUM 求和', value: 'SUM', desc: '对数值字段求和，适合金额、数量等' },
  { label: 'COUNT 计数', value: 'COUNT', desc: '统计行数，适合订单量、用户数等' },
  { label: 'DISTINCT_COUNT 去重计数', value: 'DISTINCT_COUNT', desc: '统计去重后的行数，适合UV等' },
  { label: 'AVG 平均', value: 'AVG', desc: '计算平均值，适合客单价等' },
  { label: 'MAX 最大值', value: 'MAX', desc: '取最大值' },
  { label: 'MIN 最小值', value: 'MIN', desc: '取最小值' },
];

const GRAN_OPTIONS: { label: string; value: VisualMetricConfig['granularity'] }[] = [
  { label: '按小时', value: 'HOUR' },
  { label: '按天', value: 'DAY' },
  { label: '按周', value: 'WEEK' },
  { label: '按月', value: 'MONTH' },
];

const OPERATOR_OPTIONS = [
  { label: '等于 =', value: 'eq' },
  { label: '不等于 ≠', value: 'ne' },
  { label: '大于 >', value: 'gt' },
  { label: '大于等于 ≥', value: 'gte' },
  { label: '小于 <', value: 'lt' },
  { label: '小于等于 ≤', value: 'lte' },
  { label: '包含 IN', value: 'in' },
  { label: '模糊匹配 LIKE', value: 'like' },
  { label: '区间 BETWEEN', value: 'between' },
];

const TYPE_COLOR: Record<string, string> = {
  number: 'blue',
  string: 'green',
  boolean: 'orange',
  Date: 'purple',
  object: 'magenta',
  array: 'cyan',
  unknown: 'default',
};

const VisualMetricEditor: React.FC<Props> = ({ onCreated, onCancel }) => {
  const [current, setCurrent] = useState(0);
  const [form] = Form.useForm<VisualMetricConfig & { dataSourceId: string; name: string; description: string }>();

  const [dataSources, setDataSources] = useState<any[]>([]);
  const [tables, setTables] = useState<SchemaTable[]>([]);
  const [columns, setColumns] = useState<{ name: string; type: string; nullable: boolean }[]>([]);
  const [loadingTables, setLoadingTables] = useState(false);
  const [loadingColumns, setLoadingColumns] = useState(false);
  const [preview, setPreview] = useState<PreviewResult | null>(null);
  const [loadingPreview, setLoadingPreview] = useState(false);
  const [savedMetric, setSavedMetric] = useState<any>(null);

  const dataSourceId = Form.useWatch('dataSourceId', form);
  const tableName = Form.useWatch('table', form);
  const metricField = Form.useWatch('metricField', form);
  const aggregation = Form.useWatch('aggregation', form);
  const timeField = Form.useWatch('timeField', form);

  useEffect(() => {
    dataSourceApi.list().then(setDataSources).catch(() => {});
  }, []);

  useEffect(() => {
    if (!dataSourceId) {
      setTables([]);
      return;
    }
    setLoadingTables(true);
    metricBuilderApi
      .listTables(dataSourceId)
      .then(setTables)
      .catch((e) => message.error(`加载表失败: ${e.message}`))
      .finally(() => setLoadingTables(false));
  }, [dataSourceId]);

  useEffect(() => {
    if (!dataSourceId || !tableName) {
      setColumns([]);
      return;
    }
    setLoadingColumns(true);
    metricBuilderApi
      .listColumns(dataSourceId, tableName)
      .then(setColumns)
      .catch((e) => message.error(`加载列失败: ${e.message}`))
      .finally(() => setLoadingColumns(false));
  }, [dataSourceId, tableName]);

  const numericColumns = useMemo(
    () => columns.filter((c) => c.type === 'number' || c.type === 'Date'),
    [columns],
  );
  const dateColumns = useMemo(
    () => columns.filter((c) => c.type === 'Date' || c.type === 'string' || c.name.toLowerCase().includes('time') || c.name.toLowerCase().includes('date')),
    [columns],
  );

  const canPreview = !!(dataSourceId && tableName && metricField && aggregation);

  const handlePreview = async () => {
    if (!canPreview || !dataSourceId) return;
    setLoadingPreview(true);
    try {
      const values = await form.validateFields();
      const filters = values.filters?.map((f: any) => ({
        ...f,
        value:
          f.operator === 'in'
            ? String(f.value).split(',').map((s) => s.trim())
            : f.operator === 'between'
              ? String(f.value).split(',').map((s) => s.trim())
              : f.value,
      }));
      const res = await metricBuilderApi.preview(dataSourceId, {
        table: values.table,
        metricField: values.metricField,
        aggregation: values.aggregation,
        alias: values.alias,
        timeField: values.timeField,
        granularity: values.granularity,
        startDate: values.startDate
          ? values.startDate.toISOString()
          : undefined,
        endDate: values.endDate
          ? values.endDate.toISOString()
          : undefined,
        dimensions: values.dimensions,
        filters,
      });
      setPreview(res);
      message.success(`预览成功，共 ${res.rowCount} 条数据`);
    } catch (e: any) {
      message.error(`预览失败: ${e.message}`);
    } finally {
      setLoadingPreview(false);
    }
  };

  const handleSave = async () => {
    if (!dataSourceId) return;
    try {
      const values = await form.validateFields([
        'name', 'description', 'table', 'metricField', 'aggregation',
        'alias', 'timeField', 'granularity', 'dimensions',
      ]);
      const created = await metricBuilderApi.createMetric(dataSourceId, {
        ...values,
        startDate: undefined,
        endDate: undefined,
        businessLineId: dataSources.find((d) => d.id === dataSourceId)?.businessLineId,
      } as any);
      setSavedMetric(created);
      setCurrent(5);
      onCreated?.(created);
    } catch (e: any) {
      message.error(`保存失败: ${e.message}`);
    }
  };

  const previewColumns: ColumnsType<Record<string, any>> = useMemo(() => {
    if (!preview?.data?.length) return [];
    const keys = Object.keys(preview.data[0]);
    return keys.map((k) => ({
      title: k,
      dataIndex: k,
      key: k,
      width: 150,
    }));
  }, [preview]);

  const steps = [
    { title: '选择数据源', icon: <DatabaseOutlined /> },
    { title: '选择数据表', icon: <TableOutlined /> },
    { title: '指标与聚合', icon: <CalculatorOutlined /> },
    { title: '时间与维度', icon: <FieldTimeOutlined /> },
    { title: '过滤与保存', icon: <FilterOutlined /> },
    { title: '完成', icon: <CheckCircleOutlined /> },
  ];

  return (
    <div style={{ padding: 24, maxWidth: 1200, margin: '0 auto' }}>
      <Card style={{ marginBottom: 16 }}>
        <Steps current={current} items={steps} />
      </Card>

      {current < 5 && (
        <Card>
          <Form form={form} layout="vertical" initialValues={{ aggregation: 'SUM', dimensions: [] }}>
            {/* Step 0: 数据源 */}
            {current === 0 && (
              <div>
                <Form.Item
                  name="dataSourceId"
                  label="选择数据源"
                  rules={[{ required: true, message: '请选择数据源' }]}
                >
                  <Select
                    placeholder="选择要查询的数据源（支持 MySQL / ClickHouse / PostgreSQL）"
                    showSearch
                    optionFilterProp="label"
                    loading={!dataSources.length}
                  >
                    {dataSources.map((ds) => (
                      <Option key={ds.id} value={ds.id} label={ds.name}>
                        <Space>
                          <Tag color={ds.type === 'CLICKHOUSE' ? 'cyan' : ds.type === 'MYSQL' ? 'blue' : 'orange'}>
                            {ds.type}
                          </Tag>
                          <strong>{ds.name}</strong>
                        </Space>
                      </Option>
                    ))}
                  </Select>
                </Form.Item>
                {dataSourceId && (
                  <Alert
                    type="success"
                    showIcon
                    message={`已选数据源：${dataSources.find(d => d.id === dataSourceId)?.name}`}
                    description="下一步选择该数据源中的数据表"
                  />
                )}
              </div>
            )}

            {/* Step 1: 选表 */}
            {current === 1 && (
              <div>
                <Form.Item
                  name="table"
                  label="选择数据表"
                  rules={[{ required: true, message: '请选择表' }]}
                >
                  <Select
                    placeholder="选择要查询的表"
                    loading={loadingTables}
                    showSearch
                    optionFilterProp="label"
                  >
                    {tables.map((t) => (
                      <Option key={t.table} value={t.table} label={t.table}>
                        <Space>
                          <span>{t.table}</span>
                          <Tag color="purple">{t.columns.length} 列</Tag>
                        </Space>
                      </Option>
                    ))}
                  </Select>
                </Form.Item>

                {tableName && (
                  <>
                    <Divider orientation="left">表结构预览</Divider>
                    <Table
                      size="small"
                      dataSource={columns}
                      loading={loadingColumns}
                      pagination={false}
                      rowKey="name"
                      columns={[
                        { title: '字段名', dataIndex: 'name', key: 'name', width: 200 },
                        {
                          title: '类型',
                          dataIndex: 'type',
                          key: 'type',
                          width: 150,
                          render: (t) => <Tag color={TYPE_COLOR[t] ?? 'default'}>{t}</Tag>,
                        },
                        {
                          title: '可空',
                          dataIndex: 'nullable',
                          key: 'nullable',
                          width: 100,
                          render: (n) => (n ? <Tag color="orange">NULL</Tag> : <Tag color="green">NOT NULL</Tag>),
                        },
                      ]}
                    />
                  </>
                )}
              </div>
            )}

            {/* Step 2: 指标和聚合 */}
            {current === 2 && (
              <div>
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item
                      name="metricField"
                      label="指标字段"
                      rules={[{ required: true, message: '请选择指标字段' }]}
                      extra="COUNT 可以不选具体字段（统计行数），其他聚合请选择数值字段"
                    >
                      <Select
                        placeholder="选择要聚合的字段"
                        loading={loadingColumns}
                        showSearch
                        optionFilterProp="label"
                        allowClear
                      >
                        <Option value="*" label="* (计数用)">
                          <Space><Tag color="default">*</Tag> 所有字段（用于 COUNT）</Space>
                        </Option>
                        {columns.map((c) => (
                          <Option key={c.name} value={c.name} label={c.name}>
                            <Space>
                              <span>{c.name}</span>
                              <Tag color={TYPE_COLOR[c.type] ?? 'default'}>{c.type}</Tag>
                            </Space>
                          </Option>
                        ))}
                      </Select>
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item
                      name="aggregation"
                      label="聚合方式"
                      rules={[{ required: true, message: '请选择聚合方式' }]}
                    >
                      <Select>
                        {AGG_OPTIONS.map((a) => (
                          <Option key={a.value} value={a.value}>
                            <Tooltip title={a.desc}>{a.label}</Tooltip>
                          </Option>
                        ))}
                      </Select>
                    </Form.Item>
                  </Col>
                </Row>

                <Form.Item name="alias" label="字段别名（可选）" extra="如 gmv、order_count 等">
                  <Input placeholder="gmv" />
                </Form.Item>

                <Alert
                  type="info"
                  showIcon
                  message="实时预览"
                  description="配置完成后可以点击下方按钮预览生成的 SQL 和执行结果，预览结果最多显示 100 行"
                />
                <Divider />
                <Space>
                  <Button
                    type="primary"
                    icon={<EyeOutlined />}
                    onClick={handlePreview}
                    disabled={!canPreview}
                    loading={loadingPreview}
                  >
                    预览生成 SQL 和结果
                  </Button>
                  <Button
                    icon={<ReloadOutlined />}
                    onClick={() => setPreview(null)}
                  >
                    清除预览
                  </Button>
                </Space>

                {preview && (
                  <div style={{ marginTop: 16 }}>
                    <Card size="small" title="生成的 SQL" style={{ marginBottom: 12 }}>
                      <pre
                        style={{
                          background: '#f5f5f5',
                          padding: 12,
                          borderRadius: 4,
                          margin: 0,
                          fontSize: 12,
                          whiteSpace: 'pre-wrap',
                          wordBreak: 'break-all',
                        }}
                      >
                        {preview.sql}
                      </pre>
                    </Card>
                    <Card size="small" title={`查询结果（${preview.rowCount} 行）`}>
                      <Table
                        size="small"
                        dataSource={preview.data.slice(0, 100)}
                        columns={previewColumns}
                        pagination={{ pageSize: 10 }}
                        scroll={{ x: 'max-content' }}
                        rowKey={(r, i) => String(i)}
                      />
                    </Card>
                  </div>
                )}
              </div>
            )}

            {/* Step 3: 时间和维度 */}
            {current === 3 && (
              <div>
                <Row gutter={16}>
                  <Col span={12}>
                    <Form.Item name="timeField" label="时间字段（可选）">
                      <Select
                        placeholder="选择时间字段（created_at 等）"
                        loading={loadingColumns}
                        allowClear
                        showSearch
                      >
                        {dateColumns.map((c) => (
                          <Option key={c.name} value={c.name} label={c.name}>
                            <Space>
                              <span>{c.name}</span>
                              <Tag color={TYPE_COLOR[c.type] ?? 'default'}>{c.type}</Tag>
                            </Space>
                          </Option>
                        ))}
                      </Select>
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name="granularity" label="时间粒度" extra="选择后自动按粒度做 GROUP BY">
                      <Select disabled={!timeField} allowClear placeholder="选择时间粒度">
                        {GRAN_OPTIONS.map((g) => (
                          <Option key={g.value} value={g.value}>{g.label}</Option>
                        ))}
                      </Select>
                    </Form.Item>
                  </Col>
                </Row>

                <Form.Item label="时间范围（预览用，保存后可以动态传）">
                  <Form.Item name={['startDate']} noStyle>
                    <RangePicker showTime style={{ width: '100%' }} />
                  </Form.Item>
                </Form.Item>

                <Form.Item name="dimensions" label="维度分组（可选）" extra="例如按商品品类、渠道等维度分组">
                  <Select
                    mode="multiple"
                    placeholder="选择维度字段"
                    loading={loadingColumns}
                    allowClear
                    showSearch
                    maxTagCount="responsive"
                  >
                    {columns
                      .filter((c) => c.type === 'string' || c.type === 'number')
                      .map((c) => (
                        <Option key={c.name} value={c.name} label={c.name}>
                          <Space>
                            <span>{c.name}</span>
                            <Tag color={TYPE_COLOR[c.type] ?? 'default'}>{c.type}</Tag>
                          </Space>
                        </Option>
                      ))}
                  </Select>
                </Form.Item>
              </div>
            )}

            {/* Step 4: 过滤条件 + 保存 */}
            {current === 4 && (
              <div>
                <Form.Item label="指标基础信息">
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <Form.Item
                      name="name"
                      label="指标名称"
                      noStyle
                      rules={[{ required: true, message: '请输入指标名称' }]}
                    >
                      <Input placeholder="例如：GMV、订单量、客单价" style={{ width: 400 }} />
                    </Form.Item>
                    <Form.Item name="description" label="指标描述" noStyle>
                      <TextArea rows={2} placeholder="简要描述该指标的业务含义" style={{ width: 400 }} />
                    </Form.Item>
                  </Space>
                </Form.Item>

                <Form.List name="filters">
                  {(fields, { add, remove }) => (
                    <>
                      <Divider orientation="left">过滤条件（可选）</Divider>
                      <Space direction="vertical" style={{ width: '100%' }}>
                        {fields.map(({ key, name, ...rest }) => (
                          <Space key={key} align="baseline" wrap>
                            <Form.Item
                              {...rest}
                              name={[name, 'field']}
                              noStyle
                              rules={[{ required: true, message: '必填' }]}
                            >
                              <Select
                                placeholder="字段"
                                style={{ width: 180 }}
                                loading={loadingColumns}
                                showSearch
                              >
                                {columns.map((c) => (
                                  <Option key={c.name} value={c.name}>{c.name}</Option>
                                ))}
                              </Select>
                            </Form.Item>
                            <Form.Item
                              {...rest}
                              name={[name, 'operator']}
                              noStyle
                              rules={[{ required: true, message: '必填' }]}
                            >
                              <Select placeholder="操作符" style={{ width: 160 }}>
                                {OPERATOR_OPTIONS.map((o) => (
                                  <Option key={o.value} value={o.value}>{o.label}</Option>
                                ))}
                              </Select>
                            </Form.Item>
                            <Form.Item
                              {...rest}
                              name={[name, 'value']}
                              noStyle
                              rules={[{ required: true, message: '必填' }]}
                            >
                              <Input placeholder="值 (IN/BETWEEN 用逗号分隔)" style={{ width: 240 }} />
                            </Form.Item>
                            <Button danger onClick={() => remove(name)}>删除</Button>
                          </Space>
                        ))}
                        <Button type="dashed" block icon={<FilterOutlined />} onClick={() => add()}>
                          添加过滤条件
                        </Button>
                      </Space>
                    </>
                  )}
                </Form.List>

                <Divider />
                <Space>
                  <Button
                    type="primary"
                    size="large"
                    icon={<SaveOutlined />}
                    onClick={handleSave}
                  >
                    保存指标
                  </Button>
                  <Button icon={<EyeOutlined />} onClick={handlePreview} disabled={!canPreview} loading={loadingPreview}>
                    再预览一次
                  </Button>
                </Space>
              </div>
            )}
          </Form>
        </Card>
      )}

      {/* Step 5: 完成 */}
      {current === 5 && savedMetric && (
        <Card>
          <Result
            status="success"
            title="指标创建成功！"
            subTitle={`${savedMetric.name} 已保存，可以在看板中使用了`}
            extra={[
              <Button type="primary" key="view" onClick={onCancel}>
                返回指标列表
              </Button>,
            ]}
          >
            <div style={{ textAlign: 'left', maxWidth: 600, margin: '0 auto' }}>
              <Alert
                type="info"
                showIcon
                message="指标详情"
                description={
                  <ul style={{ margin: 0, paddingLeft: 20 }}>
                    <li>ID: {savedMetric.id}</li>
                    <li>聚合方式: {savedMetric.aggregation}</li>
                    <li>数据源 ID: {savedMetric.dataSourceId}</li>
                    <li>自动对比: {savedMetric.isAutoCompare ? '开启' : '关闭'}</li>
                  </ul>
                }
              />
            </div>
          </Result>
        </Card>
      )}

      {/* 底部按钮 */}
      {current < 5 && (
        <div style={{ textAlign: 'center', marginTop: 24 }}>
          <Space>
            {current > 0 && (
              <Button onClick={() => setCurrent(current - 1)}>上一步</Button>
            )}
            {current < 4 && (
              <Button
                type="primary"
                onClick={() => {
                  const needValidate =
                    current === 0 ? ['dataSourceId'] :
                    current === 1 ? ['table'] :
                    current === 2 ? ['metricField', 'aggregation'] : [];
                  if (needValidate.length) {
                    form.validateFields(needValidate).then(() => setCurrent(current + 1));
                  } else {
                    setCurrent(current + 1);
                  }
                }}
              >
                下一步
              </Button>
            )}
            {onCancel && current < 5 && (
              <Button onClick={onCancel}>取消</Button>
            )}
          </Space>
        </div>
      )}
    </div>
  );
};

export default VisualMetricEditor;
