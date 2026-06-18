import React, { useState, useEffect } from 'react';
import {
  Card,
  Button,
  Table,
  Space,
  Modal,
  Form,
  Input,
  Tag,
  message,
  Popconfirm,
  Typography,
  Row,
  Col,
  Tabs,
  Divider,
  List,
  Empty,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ApartmentOutlined,
  ReloadOutlined,
  BuildingOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { tenantService } from '@/services/tenant';
import type { Tenant, BusinessLine } from '@/types';

const { Option } = Select;
const { Text } = Typography;
const { Tabs: AntTabs, TabPane } = Tabs;

const TenantManagementPage: React.FC = () => {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [businessLinesMap, setBusinessLinesMap] = useState<Record<string, BusinessLine[]>>({});
  const [loading, setLoading] = useState(false);
  const [tenantModalVisible, setTenantModalVisible] = useState(false);
  const [businessLineModalVisible, setBusinessLineModalVisible] = useState(false);
  const [editingTenant, setEditingTenant] = useState<Tenant | null>(null);
  const [editingBusinessLine, setEditingBusinessLine] = useState<BusinessLine | null>(null);
  const [selectedTenant, setSelectedTenant] = useState<Tenant | null>(null);
  const [activeTab, setActiveTab] = useState('tenants');
  const [tenantForm] = Form.useForm();
  const [businessLineForm] = Form.useForm();

  const loadTenants = async () => {
    setLoading(true);
    try {
      const res = await tenantService.list();
      setTenants(res.data.data);
      res.data.data.forEach((tenant) => {
        loadBusinessLines(tenant.id);
      });
    } catch (err) {
      message.error('加载租户列表失败');
    } finally {
      setLoading(false);
    }
  };

  const loadBusinessLines = async (tenantId: string) => {
    try {
      const res = await tenantService.listBusinessLines(tenantId);
      setBusinessLinesMap((prev) => ({
        ...prev,
        [tenantId]: res.data.data,
      }));
    } catch (err) {
      console.error(`加载租户 ${tenantId} 业务线失败`, err);
    }
  };

  useEffect(() => {
    loadTenants();
  }, []);

  const handleCreateTenant = () => {
    setEditingTenant(null);
    tenantForm.resetFields();
    setTenantModalVisible(true);
  };

  const handleEditTenant = (record: Tenant) => {
    setEditingTenant(record);
    tenantForm.setFieldsValue(record);
    setTenantModalVisible(true);
  };

  const handleDeleteTenant = async (id: string) => {
    try {
      await tenantService.delete(id);
      message.success('删除成功');
      loadTenants();
    } catch (err) {
      message.error('删除失败');
    }
  };

  const handleSubmitTenant = async () => {
    try {
      const values = await tenantForm.validateFields();
      if (editingTenant) {
        await tenantService.update(editingTenant.id, values);
        message.success('更新成功');
      } else {
        await tenantService.create(values);
        message.success('创建成功');
      }
      setTenantModalVisible(false);
      loadTenants();
    } catch (err) {
      console.error(err);
    }
  };

  const handleCreateBusinessLine = (tenant: Tenant) => {
    setSelectedTenant(tenant);
    setEditingBusinessLine(null);
    businessLineForm.resetFields();
    setBusinessLineModalVisible(true);
  };

  const handleEditBusinessLine = (tenant: Tenant, record: BusinessLine) => {
    setSelectedTenant(tenant);
    setEditingBusinessLine(record);
    businessLineForm.setFieldsValue(record);
    setBusinessLineModalVisible(true);
  };

  const handleDeleteBusinessLine = async (tenantId: string, businessLineId: string) => {
    try {
      await tenantService.deleteBusinessLine(tenantId, businessLineId);
      message.success('删除成功');
      loadBusinessLines(tenantId);
    } catch (err) {
      message.error('删除失败');
    }
  };

  const handleSubmitBusinessLine = async () => {
    if (!selectedTenant) return;
    try {
      const values = await businessLineForm.validateFields();
      if (editingBusinessLine) {
        await tenantService.updateBusinessLine(
          selectedTenant.id,
          editingBusinessLine.id,
          values,
        );
        message.success('更新成功');
      } else {
        await tenantService.createBusinessLine(selectedTenant.id, values);
        message.success('创建成功');
      }
      setBusinessLineModalVisible(false);
      loadBusinessLines(selectedTenant.id);
    } catch (err) {
      console.error(err);
    }
  };

  const tenantColumns: ColumnsType<Tenant> = [
    {
      title: '租户名称',
      dataIndex: 'name',
      key: 'name',
      render: (text, record) => (
        <Space>
          <ApartmentOutlined />
          <Text strong>{text}</Text>
          <Tag color="blue">{record.slug}</Tag>
        </Space>
      ),
    },
    {
      title: '业务线数量',
      key: 'businessLineCount',
      render: (_, record) => {
        const count = businessLinesMap[record.id]?.length || 0;
        return (
          <Space>
            <BuildingOutlined />
            {count} 个
          </Space>
        );
      },
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
            icon={<BuildingOutlined />}
            onClick={() => handleCreateBusinessLine(record)}
          >
            添加业务线
          </Button>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEditTenant(record)}>
            编辑
          </Button>
          <Popconfirm title="确定删除该租户?" onConfirm={() => handleDeleteTenant(record.id)}>
            <Button type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const expandableRow = {
    expandedRowRender: (record: Tenant) => {
      const businessLines = businessLinesMap[record.id] || [];
      return (
        <div style={{ padding: '0 48px' }}>
          <Card size="small" title={<Space><BuildingOutlined /> 业务线列表</Space>}>
            {businessLines.length > 0 ? (
              <Table
                size="small"
                dataSource={businessLines}
                rowKey="id"
                pagination={false}
                columns={[
                  {
                    title: '业务线名称',
                    dataIndex: 'name',
                    key: 'name',
                    render: (text, bl) => (
                      <Space>
                        <TeamOutlined />
                        {text}
                        <Tag color="geekblue">{bl.code}</Tag>
                      </Space>
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
                    render: (_, bl) => (
                      <Space>
                        <Button
                          type="link"
                          size="small"
                          icon={<EditOutlined />}
                          onClick={() => handleEditBusinessLine(record, bl)}
                        >
                          编辑
                        </Button>
                        <Popconfirm
                          title="确定删除该业务线?"
                          onConfirm={() => handleDeleteBusinessLine(record.id, bl.id)}
                        >
                          <Button type="link" danger size="small" icon={<DeleteOutlined />}>
                            删除
                          </Button>
                        </Popconfirm>
                      </Space>
                    ),
                  },
                ]}
              />
            ) : (
              <Empty
                description="暂无业务线"
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            )}
          </Card>
        </div>
      );
    },
  };

  return (
    <div style={{ padding: 24 }}>
      <Card
        title={
          <Space>
            <ApartmentOutlined />
            租户管理
          </Space>
        }
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={loadTenants}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreateTenant}>
              新建租户
            </Button>
          </Space>
        }
      >
        <Table
          columns={tenantColumns}
          dataSource={tenants}
          rowKey="id"
          loading={loading}
          expandable={expandableRow}
        />
      </Card>

      <Modal
        title={editingTenant ? '编辑租户' : '新建租户'}
        open={tenantModalVisible}
        onOk={handleSubmitTenant}
        onCancel={() => setTenantModalVisible(false)}
        destroyOnClose
      >
        <Form form={tenantForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="name"
                label="租户名称"
                rules={[{ required: true, message: '请输入租户名称' }]}
              >
                <Input placeholder="请输入租户名称" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="slug"
                label="租户标识"
                rules={[
                  { required: true, message: '请输入租户标识' },
                  { pattern: /^[a-z0-9-]+$/, message: '只能包含小写字母、数字和横杠' },
                ]}
              >
                <Input placeholder="如: default, company-a" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      <Modal
        title={
          <Space>
            <BuildingOutlined />
            {editingBusinessLine ? '编辑业务线' : '新建业务线'}
            {selectedTenant && (
              <Tag color="blue">租户: {selectedTenant.name}</Tag>
            )}
          </Space>
        }
        open={businessLineModalVisible}
        onOk={handleSubmitBusinessLine}
        onCancel={() => setBusinessLineModalVisible(false)}
        destroyOnClose
      >
        <Form form={businessLineForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="name"
                label="业务线名称"
                rules={[{ required: true, message: '请输入业务线名称' }]}
              >
                <Input placeholder="请输入业务线名称" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="code"
                label="业务线编码"
                rules={[
                  { required: true, message: '请输入业务线编码' },
                  { pattern: /^[A-Z0-9_]+$/, message: '只能包含大写字母、数字和下划线' },
                ]}
              >
                <Input placeholder="如: DATA_TEAM, PRODUCT" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
};

export default TenantManagementPage;
