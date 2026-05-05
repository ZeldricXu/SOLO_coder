import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Table,
  Tag,
  Space,
  Modal,
  Form,
  Input,
  Select,
  message,
  Popconfirm,
  Card,
  Row,
  Col
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  HistoryOutlined,
  ShareAltOutlined,
  StarOutlined,
  StarFilled
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { documentApi, categoryApi, favoriteApi } from '../api/api';
import { useApp } from '../context/AppContext';

const { Search } = Input;
const { TextArea } = Input;

const DocumentsPage = () => {
  const navigate = useNavigate();
  const { showNotification } = useApp();
  
  const [loading, setLoading] = useState(false);
  const [docs, setDocs] = useState([]);
  const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
  const [categories, setCategories] = useState([]);
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [form] = Form.useForm();
  const [selectedCategory, setSelectedCategory] = useState(null);
  const [searchKeyword, setSearchKeyword] = useState('');

  useEffect(() => {
    fetchDocuments();
    fetchCategories();
  }, [pagination.current, pagination.pageSize]);

  const fetchDocuments = async () => {
    setLoading(true);
    try {
      const result = await documentApi.list({
        page: pagination.current,
        pageSize: pagination.pageSize
      });
      setDocs(result.data.docs || []);
      setPagination(prev => ({
        ...prev,
        total: result.data.pagination?.total || 0
      }));
    } catch (error) {
      message.error(error.message || '获取文档列表失败');
    } finally {
      setLoading(false);
    }
  };

  const fetchCategories = async () => {
    try {
      const result = await categoryApi.list();
      setCategories([{ category_name: '未分类', category_id: null }, ...(result.data || [])]);
    } catch (error) {
      console.error('获取分类失败:', error);
    }
  };

  const handleCreate = () => {
    setCreateModalVisible(true);
  };

  const handleCreateSubmit = async (values) => {
    try {
      const result = await documentApi.create({
        title: values.title,
        content: values.content || '',
        category: values.category || '未分类',
        tags: values.tags || []
      });
      message.success('文档创建成功');
      setCreateModalVisible(false);
      form.resetFields();
      fetchDocuments();
      
      if (result.data?.doc_id) {
        navigate(`/edit/${result.data.doc_id}`);
      }
    } catch (error) {
      message.error(error.message || '创建文档失败');
    }
  };

  const handleEdit = (docId) => {
    navigate(`/edit/${docId}`);
  };

  const handleDelete = async (docId) => {
    try {
      await documentApi.delete(docId);
      message.success('文档已删除');
      fetchDocuments();
    } catch (error) {
      message.error(error.message || '删除文档失败');
    }
  };

  const handleViewVersions = (docId) => {
    navigate(`/versions/${docId}`);
  };

  const handleShare = (docId) => {
    navigate(`/share/${docId}`);
  };

  const handleToggleFavorite = async (docId, event) => {
    event.stopPropagation();
    try {
      await favoriteApi.toggle(docId);
      message.success('收藏状态已更新');
      fetchDocuments();
    } catch (error) {
      message.error(error.message || '操作失败');
    }
  };

  const getStatusTag = (status) => {
    const statusMap = {
      draft: { color: 'gold', text: '草稿' },
      published: { color: 'green', text: '已发布' },
      archived: { color: 'default', text: '已归档' }
    };
    const config = statusMap[status] || statusMap.draft;
    return <Tag color={config.color}>{config.text}</Tag>;
  };

  const columns = [
    {
      title: '标题',
      dataIndex: 'title',
      key: 'title',
      render: (text, record) => (
        <a onClick={() => handleEdit(record.doc_id)}>{text}</a>
      )
    },
    {
      title: '分类',
      dataIndex: 'category',
      key: 'category',
      width: 120,
      render: (category) => <Tag>{category || '未分类'}</Tag>
    },
    {
      title: '标签',
      dataIndex: 'tags',
      key: 'tags',
      width: 200,
      render: (tags) => (
        <Space size={[0, 4]} wrap>
          {tags?.map(tag => (
            <Tag key={tag} color="blue">{tag}</Tag>
          ))}
        </Space>
      )
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: getStatusTag
    },
    {
      title: '版本',
      dataIndex: 'current_version',
      key: 'current_version',
      width: 80
    },
    {
      title: '更新时间',
      dataIndex: 'updated_at',
      key: 'updated_at',
      width: 180,
      render: (time) => dayjs(time).format('YYYY-MM-DD HH:mm:ss')
    },
    {
      title: '操作',
      key: 'actions',
      width: 220,
      fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          <Button
            type="text"
            icon={<EditOutlined />}
            onClick={() => handleEdit(record.doc_id)}
          />
          <Button
            type="text"
            icon={<HistoryOutlined />}
            onClick={() => handleViewVersions(record.doc_id)}
          />
          <Button
            type="text"
            icon={<ShareAltOutlined />}
            onClick={() => handleShare(record.doc_id)}
          />
          <Popconfirm
            title="确定要删除这个文档吗？"
            onConfirm={() => handleDelete(record.doc_id)}
            okText="确定"
            cancelText="取消"
          >
            <Button type="text" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      )
    }
  ];

  return (
    <div>
      <Card
        title="文档管理"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
            新建文档
          </Button>
        }
      >
        <div style={{ marginBottom: 16 }}>
          <Row gutter={16}>
            <Col span={8}>
              <Search
                placeholder="搜索文档标题..."
                allowClear
                value={searchKeyword}
                onChange={(e) => setSearchKeyword(e.target.value)}
                onSearch={(value) => {
                  setSearchKeyword(value);
                  navigate(`/search?keyword=${encodeURIComponent(value)}`);
                }}
              />
            </Col>
            <Col span={6}>
              <Select
                placeholder="选择分类筛选"
                style={{ width: '100%' }}
                allowClear
                value={selectedCategory}
                onChange={(value) => {
                  setSelectedCategory(value);
                  if (value) {
                    navigate(`/search?category=${encodeURIComponent(value)}`);
                  }
                }}
              >
                {categories.map(cat => (
                  <Select.Option key={cat.category_id || 'uncategorized'} value={cat.category_name}>
                    {cat.category_name}
                  </Select.Option>
                ))}
              </Select>
            </Col>
          </Row>
        </div>

        <Table
          columns={columns}
          dataSource={docs}
          rowKey="doc_id"
          loading={loading}
          pagination={{
            ...pagination,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (total) => `共 ${total} 条文档`,
            onChange: (page, pageSize) => {
              setPagination(prev => ({ ...prev, current: page, pageSize }));
            }
          }}
          scroll={{ x: 1200 }}
        />
      </Card>

      <Modal
        title="新建文档"
        open={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        footer={null}
        width={600}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleCreateSubmit}
        >
          <Form.Item
            name="title"
            label="文档标题"
            rules={[{ required: true, message: '请输入文档标题' }]}
          >
            <Input placeholder="请输入文档标题" />
          </Form.Item>

          <Form.Item
            name="category"
            label="分类"
          >
            <Select placeholder="请选择分类">
              {categories.map(cat => (
                <Select.Option key={cat.category_id || 'uncategorized'} value={cat.category_name}>
                  {cat.category_name}
                </Select.Option>
              ))}
            </Select>
          </Form.Item>

          <Form.Item
            name="tags"
            label="标签"
          >
            <Select
              mode="tags"
              placeholder="输入标签后按回车添加"
              tokenSeparators={[',']}
            />
          </Form.Item>

          <Form.Item
            name="content"
            label="初始内容"
          >
            <TextArea rows={6} placeholder="请输入文档内容（可选）" />
          </Form.Item>

          <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setCreateModalVisible(false)}>取消</Button>
              <Button type="primary" htmlType="submit">创建</Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default DocumentsPage;
