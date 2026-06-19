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
  InputNumber,
  message,
  Popconfirm,
  Tag,
  Drawer,
  Descriptions,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  PlayCircleOutlined,
  DatabaseOutlined,
} from '@ant-design/icons';
import type { DataSource, DataSourceType } from '@/types';
import { DataSourceType as DataSourceTypeEnum } from '@/types';
import { dataSourceService } from '@/services/data-source';
import { formatDate } from '@/utils/format';

const { Title } = Typography;
const { Option } = Select;
const { TextArea } = Input;

const DataSourcePage: React.FC = () => {
  const [dataSources, setDataSources] = useState<DataSource[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingDataSource, setEditingDataSource] = useState<DataSource | null>(null);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [schemaDrawerVisible, setSchemaDrawerVisible] = useState(false);
  const [schemaData, setSchemaData] = useState<Record<string, unknown> | null>(null);
  const [schemaLoading, setSchemaLoading] = useState(false);
  const [form] = Form.useForm();

  const loadDataSources = useCallback(async () => {
    setLoading(true);
    try {
      const res = await dataSourceService.list();
      setDataSources(res.data.data);
    } catch (err) {
      message.error('加载数据源列表失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadDataSources();
  }, [loadDataSources]);

  const handleCreate = () => {
    setEditingDataSource(null);
    form.resetFields();
    form.setFieldsValue({
      type: DataSourceTypeEnum.MYSQL,
      poolSize: 10,
      queryTimeout: 30,
      isActive: true,
    });
    setModalVisible(true);
  };

  const handleEdit = (ds: DataSource) => {
    setEditingDataSource(ds);
    form.setFieldsValue({
      name: ds.name,
      type: ds.type,
      poolSize: ds.poolSize,
      queryTimeout: ds.queryTimeout,
      ...ds.config,
    });
    setModalVisible(true);
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
        message.success('连接成功');
      } else {
        message.error(res.data.data.message || '连接失败');
      }
    } catch (err) {
      message.error('测试连接失败');
    } finally {
      setTestingId(null);
    }
  };

  const handleViewSchema = async (id: string) => {
    setSchemaDrawerVisible(true);
    setSchemaLoading(true);
    try {
      const res = await dataSourceService.schema(id);
      setSchemaData(res.data.data);
    } catch (err) {
      message.error('加载Schema失败');
    } finally {
      setSchemaLoading(false);
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const { name, type, poolSize, queryTimeout, ...config } = values;

      const data = {
        name,
        type,
        poolSize,
        queryTimeout,
        config,
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
      // validation error
    }
  };

  const typeColorMap: Record<DataSourceType, string> = {
    [DataSourceTypeEnum.MYSQL]: 'blue',
    [DataSourceTypeEnum.CLICKHOUSE]: 'green',
    [DataSourceTypeEnum.POSTGRESQL]: 'cyan',
    [DataSourceTypeEnum.HTTP_API]: 'purple',
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
      render: (type: DataSourceType) => (
        <Tag color={typeColorMap[type]}>{type}</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'isActive',
      key: 'isActive',
      render: (isActive: boolean) => (
        <Tag color={isActive ? 'green' : 'default'}>
          {isActive ? '启用' : '禁用'}
        </Tag>
      ),
    },
    {
      title: '连接池大小',
      dataIndex: 'poolSize',
      key: 'poolSize',
    },
    {
      title: '最后测试',
      dataIndex: 'lastConnectionTest',
      key: 'lastConnectionTest',
      render: (time: string | null) => time ? formatDate(time) : '-',
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
      render: (_: unknown, record: DataSource) => (
        <Space size="small">
          <Button
            type="text"
            size="small"
            icon={<PlayCircleOutlined />}
            loading={testingId === record.id}
            onClick={() => handleTest(record.id)}
          >
            测试连接
          </Button>
          <Button
            type="text"
            size="small"
            icon={<DatabaseOutlined />}
            onClick={() => handleViewSchema(record.id)}
          >
            查看Schema
          </Button>
          <Button
            type="text"
            size="small"
            icon={<EditOutlined />}
            onClick={() => handleEdit(record)}
          >
            编辑
          </Button>
          <Popconfirm title="确定删除这个数据源吗？" onConfirm={() => handleDelete(record.id)}>
            <Button type="text" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const renderConfigFields = () => {
    const type = Form.useWatch('type', form);

    switch (type) {
      case DataSourceTypeEnum.MYSQL:
      case DataSourceTypeEnum.POSTGRESQL:
        return (
          <>
            <Form.Item name="host" label="主机" rules={[{ required: true, message: '请输入主机' }]}>
              <Input placeholder="localhost" />
            </Form.Item>
            <Form.Item name="port" label="端口" rules={[{ required: true, message: '请输入端口' }]}>
              <InputNumber style={{ width: '100%' }} placeholder={type === DataSourceTypeEnum.MYSQL ? 3306 : 5432} />
            </Form.Item>
            <Form.Item name="database" label="数据库" rules={[{ required: true, message: '请输入数据库名' }]}>
              <Input placeholder="mydb" />
            </Form.Item>
            <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input placeholder="root" />
            </Form.Item>
            <Form.Item name="password" label="密码">
              <Input.Password placeholder="请输入密码" />
            </Form.Item>
          </>
        );
      case DataSourceTypeEnum.CLICKHOUSE:
        return (
          <>
            <Form.Item name="host" label="主机" rules={[{ required: true, message: '请输入主机' }]}>
              <Input placeholder="localhost" />
            </Form.Item>
            <Form.Item name="port" label="端口" rules={[{ required: true, message: '请输入端口' }]}>
              <InputNumber style={{ width: '100%' }} placeholder={8123} />
            </Form.Item>
            <Form.Item name="database" label="数据库" rules={[{ required: true, message: '请输入数据库名' }]}>
              <Input placeholder="default" />
            </Form.Item>
            <Form.Item name="username" label="用户名">
              <Input placeholder="default" />
            </Form.Item>
            <Form.Item name="password" label="密码">
              <Input.Password placeholder="请输入密码" />
            </Form.Item>
          </>
        );
      case DataSourceTypeEnum.HTTP_API:
        return (
          <>
            <Form.Item name="url" label="API地址" rules={[{ required: true, message: '请输入API地址' }]}>
              <Input placeholder="https://api.example.com" />
            </Form.Item>
            <Form.Item name="method" label="请求方法">
              <Select>
                <Option value="GET">GET</Option>
                <Option value="POST">POST</Option>
              </Select>
            </Form.Item>
            <Form.Item name="headers" label="请求头（JSON）">
              <TextArea rows={3} placeholder='{"Authorization": "Bearer xxx"}' />
            </Form.Item>
          </>
        );
      default:
        return null;
    }
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
          数据源管理
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
          新建数据源
        </Button>
      </div>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={dataSources}
        loading={loading}
      />

      <Modal
        title={editingDataSource ? '编辑数据源' : '新建数据源'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        okText="确定"
        cancelText="取消"
        width={600}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="数据源名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="请输入数据源名称" />
          </Form.Item>
          <Form.Item name="type" label="数据源类型" rules={[{ required: true, message: '请选择类型' }]}>
            <Select>
              <Option value={DataSourceTypeEnum.MYSQL}>MySQL</Option>
              <Option value={DataSourceTypeEnum.CLICKHOUSE}>ClickHouse</Option>
              <Option value={DataSourceTypeEnum.POSTGRESQL}>PostgreSQL</Option>
              <Option value={DataSourceTypeEnum.HTTP_API}>HTTP API</Option>
            </Select>
          </Form.Item>

          {renderConfigFields()}

          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item name="poolSize" label="连接池大小" style={{ flex: 1 }}>
              <InputNumber style={{ width: '100%' }} min={1} max={100} />
            </Form.Item>
            <Form.Item name="queryTimeout" label="查询超时（秒）" style={{ flex: 1 }}>
              <InputNumber style={{ width: '100%' }} min={1} max={300} />
            </Form.Item>
          </div>
        </Form>
      </Modal>

      <Drawer
        title="Schema 信息"
        placement="right"
        width={520}
        onClose={() => setSchemaDrawerVisible(false)}
        open={schemaDrawerVisible}
        loading={schemaLoading}
      >
        {schemaData && (
          <Descriptions column={1} bordered size="small">
            {Object.entries(schemaData).map(([key, value]) => (
              <Descriptions.Item key={key} label={key}>
                {typeof value === 'object' ? JSON.stringify(value) : String(value)}
              </Descriptions.Item>
            ))}
          </Descriptions>
        )}
      </Drawer>
    </div>
  );
};

export default DataSourcePage;
