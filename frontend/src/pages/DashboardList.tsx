import React, { useState, useEffect, useRef } from 'react';
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
  Upload,
  DatePicker,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  ExportOutlined,
  ImportOutlined,
  EyeOutlined,
  LineChartOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { dashboardService } from '@/services/dashboard';
import { tenantService } from '@/services/tenant';
import type { Dashboard, BusinessLine } from '@/types';

const { RangePicker } = DatePicker;
const { TextArea } = Input;
const { Option } = Select;

const DashboardList: React.FC = () => {
  const navigate = useNavigate();
  const [dashboards, setDashboards] = useState<Dashboard[]>([]);
  const [businessLines, setBusinessLines] = useState<BusinessLine[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingDashboard, setEditingDashboard] = useState<Dashboard | null>(null);
  const [form] = Form.useForm();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const loadDashboards = async (businessLineId?: string) => {
    setLoading(true);
    try {
      const res = await dashboardService.list(businessLineId);
      setDashboards(res.data.data);
    } catch (err) {
      message.error('加载看板列表失败');
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
    loadDashboards();
    loadBusinessLines();
  }, []);

  const handleCreate = () => {
    setEditingDashboard(null);
    form.resetFields();
    setModalVisible(true);
  };

  const handleEdit = (record: Dashboard) => {
    setEditingDashboard(record);
    form.setFieldsValue(record);
    setModalVisible(true);
  };

  const handleDelete = async (id: string) => {
    try {
      await dashboardService.delete(id);
      message.success('删除成功');
      loadDashboards();
    } catch (err) {
      message.error('删除失败');
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingDashboard) {
        await dashboardService.update(editingDashboard.id, values);
        message.success('更新成功');
      } else {
        await dashboardService.create(values);
        message.success('创建成功');
      }
      setModalVisible(false);
      loadDashboards();
    } catch (err) {
      console.error(err);
    }
  };

  const handleExport = async (record: Dashboard) => {
    try {
      const res = await dashboardService.export(record.id);
      const dataStr = JSON.stringify(res.data.data, null, 2);
      const blob = new Blob([dataStr], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${record.name}-${dayjs().format('YYYYMMDD')}.json`;
      a.click();
      URL.revokeObjectURL(url);
      message.success('导出成功');
    } catch (err) {
      message.error('导出失败');
    }
  };

  const handleImportClick = () => {
    fileInputRef.current?.click();
  };

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = async (event) => {
      try {
        const data = JSON.parse(event.target?.result as string);
        await dashboardService.import(data);
        message.success('导入成功');
        loadDashboards();
      } catch (err) {
        message.error('导入失败，请检查文件格式');
      }
    };
    reader.readAsText(file);
    e.target.value = '';
  };

  const columns: ColumnsType<Dashboard> = [
    {
      title: '看板名称',
      dataIndex: 'name',
      key: 'name',
      render: (text, record) => (
        <Space>
          <LineChartOutlined />
          <a onClick={() => navigate(`/dashboards/${record.id}`)}>{text}</a>
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
      title: '业务线',
      dataIndex: 'businessLineId',
      key: 'businessLineId',
      render: (id) => {
        const bl = businessLines.find((b) => b.id === id);
        return bl?.name || '-';
      },
    },
    {
      title: '是否公开',
      dataIndex: 'isPublic',
      key: 'isPublic',
      render: (isPublic) =>
        isPublic ? <Tag color="green">公开</Tag> : <Tag color="default">私有</Tag>,
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
            icon={<EyeOutlined />}
            onClick={() => navigate(`/dashboards/${record.id}`)}
          >
            查看
          </Button>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            编辑
          </Button>
          <Button type="link" icon={<ExportOutlined />} onClick={() => handleExport(record)}>
            导出
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
        title="看板管理"
        extra={
          <Space>
            <Button icon={<ImportOutlined />} onClick={handleImportClick}>
              导入
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
              新建看板
            </Button>
            <input
              ref={fileInputRef}
              type="file"
              accept=".json"
              style={{ display: 'none' }}
              onChange={handleImport}
            />
          </Space>
        }
      >
        <div style={{ marginBottom: 16 }}>
          <Space>
            <Select
              placeholder="选择业务线"
              style={{ width: 200 }}
              allowClear
              onChange={(value) => loadDashboards(value)}
            >
              {businessLines.map((bl) => (
                <Option key={bl.id} value={bl.id}>
                  {bl.name}
                </Option>
              ))}
            </Select>
            <RangePicker />
          </Space>
        </div>
        <Table
          columns={columns}
          dataSource={dashboards}
          rowKey="id"
          loading={loading}
        />
      </Card>

      <Modal
        title={editingDashboard ? '编辑看板' : '新建看板'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label="看板名称"
            rules={[{ required: true, message: '请输入看板名称' }]}
          >
            <Input placeholder="请输入看板名称" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <TextArea rows={3} placeholder="请输入描述" />
          </Form.Item>
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
          <Form.Item name="isPublic" label="是否公开" valuePropName="checked">
            <Select defaultValue={false}>
              <Option value={true}>公开</Option>
              <Option value={false}>私有</Option>
            </Select>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default DashboardList;
