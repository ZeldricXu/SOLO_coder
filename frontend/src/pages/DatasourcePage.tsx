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
  InputNumber,
  Tabs,
  Descriptions,
  Spin,
  Switch,
  Typography,
  Row,
  Col,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  TestOutlined,
  DatabaseOutlined,
  ReloadOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { dataSourceService } from '@/services/data-source';
import { tenantService } from '@/services/tenant';
import type { DataSource, DataSourceType, BusinessLine } from '@/types';

const { TextArea } = Input;
const { Option } = Select;
const { Tabs: AntTabs, TabPane } = Tabs;
const { Title, Text } = Typography;

const DATA_SOURCE_TYPE_CONFIG: Record<DataSourceType, { label: string; color: string }> = {
  MYSQL: { label: 'MySQL', color: 'blue' },
  CLICKHOUSE: { label: 'ClickHouse', color: 'orange' },
  POSTGRESQL: { label: 'PostgreSQL', color: 'geekblue' },
  HTTP_API: { label: 'HTTP API', color: 'purple' },
};

const DataSourcePage: React.FC = () => {
  const [dataSources, setDataSources] = useState<DataSource[]>([]);
  const [businessLines, setBusinessLines] = useState<BusinessLine[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [detailVisible, setDetailVisible] = useState(false);
  const [editingDataSource, setEditingDataSource] = useState<DataSource | null>(null);
  const [viewingDataSource, setViewingDataSource] = useState<DataSource | null>(null);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [schemaData, setSchemaData] = useState<Record<string, unknown> | null>(null);
  const [form] = Form.useForm();

  const loadDataSources = async (businessLineId?: string) => {
    setLoading(true);
    try {
      const res = await dataSourceService.list(businessLineId);
      setDataSources(res.data.data);
    } catch (err) {
      message.error('加载数据源列表失败');
    } finally {
      setLoading(false);
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

  useEffect(() => {
    loadDataSources();
    loadBusinessLines();
  }, []);

  const handleCreate = () => {
    setEditingDataSource(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleEdit = (record: DataSource) => {
    setEditingDataSource(record);
    const config = record.config as Record<string, unknown>;
    form.setFieldsValue({
      ...record,
      host: config.host as string,
      port: config.port as number,
      username: config.username as string,
      password: '',
      database: config.database as string,
      url: config.url as string,
      headers: config.headers ? JSON.stringify(config.headers) : '',
    });
    setModalVisible(true);
  };

  const handleView = async (record: DataSource) => {
    setViewingDataSource(record);
    setDetailVisible(true);
    setSchemaLoading(true);
    try {
      const res = await dataSourceService.schema(record.id);
      setSchemaData(res.data.data);
    } catch (err) {
      message.error('加载Schema失败');
    } finally {
      setSchemaLoading(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await dataSourceService.delete(id);
      message.success('删除成功');
      loadDataSources();
    } catch (err) {
      message.error('删除失败');
    }
  };

  const handleTest = async (id: string) => {
    setTestingId(id);
    try {
      const res = await dataSourceService.test(id);
      if (res.data.data.success) {
        message.success('连接测试成功');
      } else {
        message.error(`连接测试失败: ${res.data.data.message}`);
      }
    } catch (err) {
      message.error('连接测试失败');
    } finally {
      setTestingId(null);
    }
  };

  const handleTestWithForm = async () => {
    const values = await form.validateFields();
    const config = buildConfig(values);
    const testData = {
      type: values.type,
      config,
      businessLineId: values.businessLineId,
      name: 'test',
    };
    try {
      const res = await dataSourceService.test('temp');
      if (res.data.data.success) {
        message.success('连接测试成功');
      } else {
        message.error(`连接测试失败: ${res.data.data.message}`);
      }
    } catch (err) {
      message.error('连接测试失败');
    }
  };

  const buildConfig = (values: Record<string, unknown>): Record<string, unknown> => {
    const type = values.type as DataSourceType;
    if (type === 'HTTP_API') {
      return {
        url: values.url,
        headers: values.headers ? JSON.parse(values.headers as string) : {},
      };
    }
    return {
      host: values.host,
      port: values.port,
      username: values.username,
      password: values.password,
      database: values.database,
    };
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const config = buildConfig(values);
      const data = {
        name: values.name as string,
        type: values.type as DataSourceType,
        config,
        poolSize: values.poolSize as number,
        queryTimeout: values.queryTimeout as number,
        businessLineId: values.businessLineId as string,
      };

      if (editingDataSource) {
        await dataSourceService.update(editingDataSource.id, data);
        message.success('更新成功');
      } else {
        await dataSourceService.create(data);
        message.success('创建成功');
      }
      setModalVisible(false);
      loadDataSources();
    } catch (err) {
      console.error(err);
    }
  };

  const inferFieldType = (type: string): string => {
    const typeMap: Record<string, string> = {
      'int': '整数',
      'bigint': '长整数',
      'varchar': '字符串',
      'text': '文本',
      'datetime': '日期时间',
      'date': '日期',
      'float': '浮点数',
      'double': '双精度',
      'decimal': '十进制',
      'boolean': '布尔值',
      'json': 'JSON',
    };
    return typeMap[type.toLowerCase()] || type;
  };

  const columns: ColumnsType<DataSource> = [
    {
      title: '数据源名称',
      dataIndex: 'name',
      key: 'name',
      render: (text, record) => (
        <Space>
          <DatabaseOutlined />
          {text}
        </Space>
      ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      render: (type: DataSourceType) => {
        const config = DATA_SOURCE_TYPE_CONFIG[type];
        return <Tag color={config.color}>{config.label}</Tag>;
      },
    },
    {
      title: '业务线',
      dataIndex: 'businessLineId',
      key: 'businessLineId',
      render: (id) => {
        const bl = businessLines.find((b) => b.id === id);
        return bl?.name || '-';
      },
    },
    {
      title: '状态',
      dataIndex: 'isActive',
      key: 'isActive',
      render: (isActive) =>
        isActive ? <Tag color="green">活跃</Tag> : <Tag color="default">未启用</Tag>,
    },
    {
      title: '最后测试',
      dataIndex: 'lastConnectionTest',
      key: 'lastConnectionTest',
      render: (date) => (date ? dayjs(date).format('YYYY-MM-DD HH:mm') : '-'),
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
          <Button type="link" icon={<EyeOutlined />} onClick={() => handleView(record)}>
            Schema
          </Button>
          <Button
            type="link"
            icon={<TestOutlined />}
            loading={testingId === record.id}
            onClick={() => handleTest(record.id)}
          >
            测试连接
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

  const selectedType = Form.useWatch('type', form);

  return (
    <div style={{ padding: 24 }}>
      <Card
        title="数据源管理"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => loadDataSources()}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
              新建数据源
            </Button>
          </Space>
        }
      >
        <div style={{ marginBottom: 16 }}>
          <Select
            placeholder="选择业务线"
            style={{ width: 200 }}
            allowClear
            onChange={(value) => loadDataSources(value)}
          >
            {businessLines.map((bl) => (
              <Option key={bl.id} value={bl.id}>
                {bl.name}
              </Option>
            ))}
          </Select>
        </div>
        <Table columns={columns} dataSource={dataSources} rowKey="id" loading={loading} />
      </Card>

      <Modal
        title={editingDataSource ? '编辑数据源' : '新建数据源'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        destroyOnClose
        width={700}
        footer={
          <Space>
            <Button onClick={handleTestWithForm} icon={<TestOutlined />}>
              测试连接
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
                label="数据源名称"
                rules={[{ required: true, message: '请输入数据源名称' }]}
              >
                <Input placeholder="请输入数据源名称" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="type"
                label="数据源类型"
                rules={[{ required: true, message: '请选择数据源类型' }]}
              >
                <Select placeholder="请选择数据源类型">
                  {Object.entries(DATA_SOURCE_TYPE_CONFIG).map(([type, config]) => (
                    <Option key={type} value={type}>
                      <Tag color={config.color}>{config.label}</Tag>
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
          </Row>

          {selectedType && selectedType !== 'HTTP_API' ? (
            <>
              <Row gutter={16}>
                <Col span={16}>
                  <Form.Item
                    name="host"
                    label="主机地址"
                    rules={[{ required: true, message: '请输入主机地址' }]}
                  >
                    <Input placeholder="localhost / 192.168.1.1" />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item
                    name="port"
                    label="端口"
                    rules={[{ required: true, message: '请输入端口' }]}
                  >
                    <InputNumber style={{ width: '100%' }} placeholder={3306} />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item
                    name="username"
                    label="用户名"
                    rules={[{ required: true, message: '请输入用户名' }]}
                  >
                    <Input placeholder="请输入用户名" />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item
                    name="password"
                    label="密码"
                    rules={editingDataSource ? [] : [{ required: true, message: '请输入密码' }]}
                  >
                    <Input.Password placeholder={editingDataSource ? '不修改请留空' : '请输入密码'} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item
                name="database"
                label="数据库名"
                rules={[{ required: true, message: '请输入数据库名' }]}
              >
                <Input placeholder="请输入数据库名" />
              </Form.Item>
            </>
          ) : selectedType === 'HTTP_API' ? (
            <>
              <Form.Item
                name="url"
                label="API 地址"
                rules={[{ required: true, message: '请输入API地址' }]}
              >
                <Input placeholder="https://api.example.com/data" />
              </Form.Item>
              <Form.Item name="headers" label="请求头 (JSON)">
                <TextArea rows={4} placeholder='{"Authorization": "Bearer xxx"}' />
              </Form.Item>
            </>
          ) : null}

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="poolSize" label="连接池大小" initialValue={10}>
                <InputNumber style={{ width: '100%' }} min={1} max={100} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="queryTimeout" label="查询超时(秒)" initialValue={30}>
                <InputNumber style={{ width: '100%' }} min={1} max={600} />
              </Form.Item>
            </Col>
          </Row>

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

          <Form.Item name="isActive" label="是否启用" valuePropName="checked" initialValue={true}>
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={
          <Space>
            <DatabaseOutlined />
            {viewingDataSource?.name} - Schema 信息
          </Space>
        }
        open={detailVisible}
        onCancel={() => setDetailVisible(false)}
        footer={null}
        width={900}
      >
        {schemaLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}>
            <Spin />
          </div>
        ) : schemaData ? (
          <AntTabs>
            {Object.entries(schemaData).map(([tableName, columns]) => (
              <TabPane tab={tableName} key={tableName}>
                <Card size="small" title="字段类型推断">
                  <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                      <tr>
                        <th style={headerStyle}>字段名</th>
                        <th style={headerStyle}>原始类型</th>
                        <th style={headerStyle}>推断类型</th>
                        <th style={headerStyle}>说明</th>
                      </tr>
                    </thead>
                    <tbody>
                      {Array.isArray(columns) &&
                        columns.map((col: Record<string, unknown>) => (
                          <tr key={col.name as string}>
                            <td style={cellStyle}>{col.name as string}</td>
                            <td style={cellStyle}>
                              <Tag>{col.type as string}</Tag>
                            </td>
                            <td style={cellStyle}>
                              <Tag color="green">{inferFieldType(col.type as string)}</Tag>
                            </td>
                            <td style={cellStyle}>
                              <Text type="secondary">{(col.nullable as boolean) ? '可空' : '非空'}</Text>
                            </td>
                          </tr>
                        ))}
                    </tbody>
                  </table>
                </Card>
              </TabPane>
            ))}
          </AntTabs>
        ) : null}
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

export default DataSourcePage;
