import React, { useState, useEffect, useCallback } from 'react';
import {
  Card,
  Button,
  Input,
  Space,
  Typography,
  Modal,
  Form,
  message,
  Popconfirm,
  Upload,
} from 'antd';
import {
  PlusOutlined,
  SearchOutlined,
  ExportOutlined,
  ImportOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import type { UploadProps } from 'antd';
import { useNavigate } from 'react-router-dom';
import type { Dashboard } from '@/types';
import { dashboardService } from '@/services/dashboard';
import { formatDate } from '@/utils/format';

const { Title } = Typography;

const DashboardList: React.FC = () => {
  const [dashboards, setDashboards] = useState<Dashboard[]>([]);
  const [loading, setLoading] = useState(false);
  const [searchText, setSearchText] = useState('');
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [editingDashboard, setEditingDashboard] = useState<Dashboard | null>(null);
  const [form] = Form.useForm();
  const navigate = useNavigate();

  const loadDashboards = useCallback(async () => {
    setLoading(true);
    try {
      const res = await dashboardService.list();
      setDashboards(res.data.data);
    } catch (err) {
      message.error('加载看板列表失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadDashboards();
  }, [loadDashboards]);

  const filteredDashboards = dashboards.filter((d) =>
    d.name.toLowerCase().includes(searchText.toLowerCase()),
  );

  const handleCreate = () => {
    setEditingDashboard(null);
    form.resetFields();
    setCreateModalVisible(true);
  };

  const handleEdit = (dashboard: Dashboard) => {
    setEditingDashboard(dashboard);
    form.setFieldsValue({
      name: dashboard.name,
      description: dashboard.description,
    });
    setCreateModalVisible(true);
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
      setCreateModalVisible(false);
      loadDashboards();
    } catch (err) {
      // validation error
    }
  };

  const handleExport = async (id: string) => {
    try {
      const res = await dashboardService.export(id);
      const dataStr = JSON.stringify(res.data.data, null, 2);
      const blob = new Blob([dataStr], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `dashboard-${id}.json`;
      a.click();
      URL.revokeObjectURL(url);
      message.success('导出成功');
    } catch (err) {
      message.error('导出失败');
    }
  };

  const handleImport: UploadProps['beforeUpload'] = (file) => {
    const reader = new FileReader();
    reader.onload = async (e) => {
      try {
        const data = JSON.parse(e.target?.result as string);
        await dashboardService.import(data);
        message.success('导入成功');
        loadDashboards();
      } catch (err) {
        message.error('导入失败，请检查文件格式');
      }
    };
    reader.readAsText(file);
    return false;
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
          看板列表
        </Title>
        <Space>
          <Input
            placeholder="搜索看板"
            prefix={<SearchOutlined />}
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            style={{ width: 240 }}
          />
          <Upload beforeUpload={handleImport} showUploadList={false}>
            <Button icon={<ImportOutlined />}>导入</Button>
          </Upload>
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
            新建看板
          </Button>
        </Space>
      </div>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
          gap: 16,
        }}
      >
        {filteredDashboards.map((dashboard) => (
          <Card
            key={dashboard.id}
            hoverable
            loading={loading}
            onClick={() => navigate(`/dashboards/${dashboard.id}`)}
            actions={[
              <EyeOutlined
                key="view"
                onClick={(e) => {
                  e.stopPropagation();
                  navigate(`/dashboards/${dashboard.id}`);
                }}
              />,
              <EditOutlined
                key="edit"
                onClick={(e) => {
                  e.stopPropagation();
                  handleEdit(dashboard);
                }}
              />,
              <ExportOutlined
                key="export"
                onClick={(e) => {
                  e.stopPropagation();
                  handleExport(dashboard.id);
                }}
              />,
              <Popconfirm
                key="delete"
                title="确定删除这个看板吗？"
                onConfirm={(e) => {
                  e?.stopPropagation();
                  handleDelete(dashboard.id);
                }}
                onClick={(e) => e.stopPropagation()}
              >
                <DeleteOutlined />
              </Popconfirm>,
            ]}
          >
            <Card.Meta
              title={dashboard.name}
              description={
                <div style={{ marginTop: 8 }}>
                  <p style={{ color: '#8c8c8c', marginBottom: 8 }}>
                    {dashboard.description || '暂无描述'}
                  </p>
                  <p style={{ color: '#bfbfbf', fontSize: 12, margin: 0 }}>
                    更新于 {formatDate(dashboard.updatedAt)}
                  </p>
                </div>
              }
            />
          </Card>
        ))}
      </div>

      {filteredDashboards.length === 0 && !loading && (
        <div style={{ textAlign: 'center', padding: '60px 0', color: '#8c8c8c' }}>
          {searchText ? '没有找到匹配的看板' : '暂无看板，点击右上角创建第一个'}
        </div>
      )}

      <Modal
        title={editingDashboard ? '编辑看板' : '新建看板'}
        open={createModalVisible}
        onOk={handleSubmit}
        onCancel={() => setCreateModalVisible(false)}
        okText="确定"
        cancelText="取消"
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
            <Input.TextArea placeholder="请输入描述" rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default DashboardList;
