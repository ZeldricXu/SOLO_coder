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
  Typography,
  Row,
  Col,
  Avatar,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  UserOutlined,
  ReloadOutlined,
  KeyOutlined,
  SafetyOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { userService } from '@/services/user';
import { tenantService } from '@/services/tenant';
import type { User, Role, Tenant, BusinessLine } from '@/types';

const { Option } = Select;
const { Text } = Typography;

const ROLE_CONFIG: Record<Role, { label: string; color: string }> = {
  SUPER_ADMIN: { label: '超级管理员', color: 'red' },
  TENANT_ADMIN: { label: '租户管理员', color: 'orange' },
  EDITOR: { label: '编辑者', color: 'blue' },
  VIEWER: { label: '查看者', color: 'green' },
};

const UserManagementPage: React.FC = () => {
  const [users, setUsers] = useState<User[]>([]);
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [businessLines, setBusinessLines] = useState<BusinessLine[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [modalVisible, setModalVisible] = useState(false);
  const [passwordModalVisible, setPasswordModalVisible] = useState(false);
  const [editingUser, setEditingUser] = useState<User | null>(null);
  const [selectedUser, setSelectedUser] = useState<User | null>(null);
  const [form] = Form.useForm();
  const [passwordForm] = Form.useForm();

  const loadUsers = async (params?: Record<string, unknown>) => {
    setLoading(true);
    try {
      const res = await userService.list({
        page,
        limit: pageSize,
        ...params,
      });
      setUsers(res.data.data.data);
      setTotal(res.data.data.total);
    } catch (err) {
      message.error('加载用户列表失败');
    } finally {
      setLoading(false);
    }
  };

  const loadTenants = async () => {
    try {
      const res = await tenantService.list();
      setTenants(res.data.data);
    } catch (err) {
      console.error('加载租户列表失败', err);
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
    loadUsers();
    loadTenants();
    loadBusinessLines();
  }, [page, pageSize]);

  const handleCreate = () => {
    setEditingUser(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleEdit = (record: User) => {
    setEditingUser(record);
    form.setFieldsValue({
      ...record,
      confirmPassword: '',
    });
    setModalVisible(true);
  };

  const handleResetPassword = (record: User) => {
    setSelectedUser(record);
    passwordForm.resetFields();
    setPasswordModalVisible(true);
  };

  const handleUpdateRole = async (record: User, role: Role) => {
    try {
      await userService.updateRole(record.id, role);
      message.success('角色更新成功');
      loadUsers();
    } catch (err) {
      message.error('角色更新失败');
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await userService.delete(id);
      message.success('删除成功');
      loadUsers();
    } catch (err) {
      message.error('删除失败');
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const data = {
        email: values.email,
        name: values.name,
        role: values.role,
        tenantId: values.tenantId,
        ...(values.password ? { password: values.password } : {}),
      };

      if (editingUser) {
        await userService.update(editingUser.id, data);
        message.success('更新成功');
      } else {
        await userService.create({ ...data, password: values.password });
        message.success('创建成功');
      }
      setModalVisible(false);
      loadUsers();
    } catch (err) {
      console.error(err);
    }
  };

  const handleResetPasswordSubmit = async () => {
    if (!selectedUser) return;
    try {
      const values = await passwordForm.validateFields();
      await userService.resetPassword(selectedUser.id, values.newPassword);
      message.success('密码重置成功');
      setPasswordModalVisible(false);
    } catch (err) {
      message.error('密码重置失败');
    }
  };

  const columns: ColumnsType<User> = [
    {
      title: '用户',
      dataIndex: 'name',
      key: 'name',
      render: (text, record) => (
        <Space>
          <Avatar size="small" icon={<UserOutlined />} src={undefined}>
            {text?.charAt(0)}
          </Avatar>
          <div>
            <div>{text}</div>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {record.email}
            </Text>
          </div>
        </Space>
      ),
    },
    {
      title: '角色',
      dataIndex: 'role',
      key: 'role',
      render: (role: Role, record) => {
        const config = ROLE_CONFIG[role];
        return (
          <Select
            value={role}
            style={{ width: 140 }}
            onChange={(newRole) => handleUpdateRole(record, newRole)}
          >
            {Object.entries(ROLE_CONFIG).map(([r, c]) => (
              <Option key={r} value={r}>
                <Tag color={c.color}>{c.label}</Tag>
              </Option>
            ))}
          </Select>
        );
      },
    },
    {
      title: '租户',
      dataIndex: 'tenantId',
      key: 'tenantId',
      render: (id) => {
        const tenant = tenants.find((t) => t.id === id);
        return tenant?.name || '-';
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
          <Button type="link" icon={<KeyOutlined />} onClick={() => handleResetPassword(record)}>
            重置密码
          </Button>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            编辑
          </Button>
          <Popconfirm title="确定删除该用户?" onConfirm={() => handleDelete(record.id)}>
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
        title={
          <Space>
            <SafetyOutlined />
            用户管理
          </Space>
        }
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => loadUsers()}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
              新建用户
            </Button>
          </Space>
        }
      >
        <div style={{ marginBottom: 16 }}>
          <Space>
            <Input.Search
              placeholder="搜索用户"
              style={{ width: 250 }}
              allowClear
              onSearch={(value) => loadUsers({ keyword: value })}
            />
            <Select
              placeholder="选择角色"
              style={{ width: 150 }}
              allowClear
              onChange={(value) => loadUsers({ role: value })}
            >
              {Object.entries(ROLE_CONFIG).map(([role, config]) => (
                <Option key={role} value={role}>
                  <Tag color={config.color}>{config.label}</Tag>
                </Option>
              ))}
            </Select>
            <Select
              placeholder="选择租户"
              style={{ width: 200 }}
              allowClear
              onChange={(value) => loadUsers({ tenantId: value })}
            >
              {tenants.map((tenant) => (
                <Option key={tenant.id} value={tenant.id}>
                  {tenant.name}
                </Option>
              ))}
            </Select>
          </Space>
        </div>
        <Table
          columns={columns}
          dataSource={users}
          rowKey="id"
          loading={loading}
          pagination={{
            current: page,
            pageSize,
            total,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, ps) => {
              setPage(p);
              setPageSize(ps);
            },
          }}
        />
      </Card>

      <Modal
        title={editingUser ? '编辑用户' : '新建用户'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="name"
                label="姓名"
                rules={[{ required: true, message: '请输入姓名' }]}
              >
                <Input placeholder="请输入姓名" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="email"
                label="邮箱"
                rules={[
                  { required: true, message: '请输入邮箱' },
                  { type: 'email', message: '请输入有效邮箱' },
                ]}
              >
                <Input placeholder="请输入邮箱" />
              </Form.Item>
            </Col>
          </Row>

          {!editingUser && (
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="password"
                  label="密码"
                  rules={[
                    { required: true, message: '请输入密码' },
                    { min: 6, message: '密码至少6位' },
                  ]}
                >
                  <Input.Password placeholder="请输入密码" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="confirmPassword"
                  label="确认密码"
                  dependencies={['password']}
                  rules={[
                    { required: true, message: '请确认密码' },
                    ({ getFieldValue }) => ({
                      validator(_, value) {
                        if (!value || getFieldValue('password') === value) {
                          return Promise.resolve();
                        }
                        return Promise.reject(new Error('两次密码不一致'));
                      },
                    }),
                  ]}
                >
                  <Input.Password placeholder="请确认密码" />
                </Form.Item>
              </Col>
            </Row>
          )}

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="role"
                label="角色"
                rules={[{ required: true, message: '请选择角色' }]}
              >
                <Select placeholder="请选择角色">
                  {Object.entries(ROLE_CONFIG).map(([role, config]) => (
                    <Option key={role} value={role}>
                      <Tag color={config.color}>{config.label}</Tag>
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="tenantId" label="租户">
                <Select placeholder="请选择租户" allowClear>
                  {tenants.map((tenant) => (
                    <Option key={tenant.id} value={tenant.id}>
                      {tenant.name}
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      <Modal
        title={
          <Space>
            <KeyOutlined />
            重置密码 - {selectedUser?.name}
          </Space>
        }
        open={passwordModalVisible}
        onOk={handleResetPasswordSubmit}
        onCancel={() => setPasswordModalVisible(false)}
        destroyOnClose
      >
        <Form form={passwordForm} layout="vertical">
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 6, message: '密码至少6位' },
            ]}
          >
            <Input.Password placeholder="请输入新密码" />
          </Form.Item>
          <Form.Item
            name="confirmPassword"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请确认新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次密码不一致'));
                },
              }),
            ]}
          >
            <Input.Password placeholder="请确认新密码" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default UserManagementPage;
