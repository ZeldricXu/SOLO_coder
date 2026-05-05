import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Card,
  Input,
  Select,
  Tag,
  Space,
  Table,
  Row,
  Col,
  Modal,
  Form,
  message,
  Popconfirm,
  Tree,
  Empty,
  Descriptions,
  Statistic,
  List,
  Typography
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  FolderOutlined,
  TagOutlined,
  FileTextOutlined,
  ArrowLeftOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { categoryApi, searchApi } from '../api/api';
const { TextArea } = Input;
const { Option } = Select;
const { Title, Text } = Typography;
const CategoriesPage = () => {
 const navigate = useNavigate();
 const [loading, setLoading] = useState(false);
 const [categories, setCategories] = useState([]);
 const [popularTags, setPopularTags] = useState([]);
 const [selectedCategory, setSelectedCategory] = useState(null);
 const [categoryDocs, setCategoryDocs] = useState([]);
 const [createModalVisible, setCreateModalVisible] = useState(false);
 const [editModalVisible, setEditModalVisible] = useState(false);
 const [form] = Form.useForm();
 const [editForm] = Form.useForm();
 const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
 useEffect(() => {
 fetchCategories();
 fetchPopularTags();
 }, []);
 const fetchCategories = async () => {
 setLoading(true);
 try {
 const result = await categoryApi.list(true);
 setCategories(result.data || []);
 }
 catch (error) {
 message.error(error.message || '获取分类列表失败');
 }
 finally {
 setLoading(false);
 }
 };
 const fetchPopularTags = async () => {
 try {
 const result = await categoryApi.popularTags(30);
 setPopularTags(result.data?.tags || []);
 }
 catch (error) {
 console.error('获取热门标签失败:', error);
 }
 };
 const fetchCategoryDocs = async (categoryName) => {
 setLoading(true);
 try {
 const result = await searchApi.byCategory(categoryName, {
 page: pagination.current,
 pageSize: pagination.pageSize
 });
 setCategoryDocs(result.data?.docs || []);
 setPagination(prev => ({
 ...prev,
 total: result.data?.pagination?.total || 0
 }));
 }
 catch (error) {
 message.error(error.message || '获取分类文档失败');
 }
 finally {
 setLoading(false);
 }
 };
 const handleCreate = () => {
 form.resetFields();
 setCreateModalVisible(true);
 };
 const handleCreateSubmit = async (values) => {
 try {
 await categoryApi.create({
 category_name: values.category_name,
 description: values.description,
 parent_category: values.parent_category || null
 });
 message.success('分类创建成功');
 setCreateModalVisible(false);
 form.resetFields();
 fetchCategories();
 }
 catch (error) {
 message.error(error.message || '创建分类失败');
 }
 };
 const handleEdit = (category) => {
 editForm.setFieldsValue({
 category_name: category.category_name,
 description: category.description,
 parent_category: category.parent_category
 });
 setSelectedCategory(category);
 setEditModalVisible(true);
 };
 const handleEditSubmit = async (values) => {
 if (!selectedCategory)
 return;
 try {
 await categoryApi.update(selectedCategory.category_id, {
 category_name: values.category_name,
 description: values.description,
 parent_category: values.parent_category || null
 });
 message.success('分类更新成功');
 setEditModalVisible(false);
 fetchCategories();
 if (selectedCategory.category_name) {
 fetchCategoryDocs(values.category_name);
 }
 }
 catch (error) {
 message.error(error.message || '更新分类失败');
 }
 };
 const handleDelete = async (categoryId) => {
 try {
 await categoryApi.delete(categoryId);
 message.success('分类删除成功');
 fetchCategories();
 if (selectedCategory?.category_id === categoryId) {
 setSelectedCategory(null);
 setCategoryDocs([]);
 }
 }
 catch (error) {
 message.error(error.message || '删除分类失败');
 }
 };
 const handleSelectCategory = (category) => {
 setSelectedCategory(category);
 setPagination(prev => ({ ...prev, current: 1 }));
 if (category) {
 fetchCategoryDocs(category.category_name);
 }
 };
 const handleTagClick = (tag) => {
 navigate(`/search?keyword=${encodeURIComponent(tag)}`);
 };
 const handleEditDoc = (docId) => {
 navigate(`/edit/${docId}`);
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
 <a onClick={() => handleEditDoc(record.doc_id)}>{text}</a>
 )
 },
 {
 title: '标签',
 dataIndex: 'tags',
 key: 'tags',
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
 width: 100,
 render: (_, record) => (
 <Button
 type="text"
 icon={<EditOutlined />}
 onClick={() => handleEditDoc(record.doc_id)}
 />
 )
 }
 ];
 const totalDocs = categories.reduce((sum, cat) => sum + (cat.doc_count || 0), 0);
 const treeData = categories.map(cat => ({
 title: (
 <Space>
 <FolderOutlined />
 <span>{cat.category_name}</span>
 <Tag color="blue" style={{ marginLeft: 8 }}>
 {cat.doc_count || 0}
 </Tag>
 </Space>
 ),
 key: cat.category_id,
 value: cat
 }));
 return (
 <div>
 <Row gutter={24}>
 <Col span={6}>
 <Card
 title="分类管理"
 extra={
 <Button
 type="primary"
 size="small"
 icon={<PlusOutlined />}
 onClick={handleCreate}
 >
 新建分类
 </Button>
 }
 style={{ marginBottom: 16 }}
 >
 <div style={{ marginBottom: 16 }}>
 <Statistic
 title="总文档数"
 value={totalDocs}
 prefix={<FileTextOutlined />}
 />
 </div>
 {categories.length > 0 ? (
 <Tree
 treeData={treeData}
 defaultExpandAll
 onSelect={(selectedKeys, info) => {
 if (selectedKeys.length > 0 && info.node) {
 handleSelectCategory(info.node.value);
 }
 else {
 setSelectedCategory(null);
 setCategoryDocs([]);
 }
 }}
 />
 ) : (
 <Empty
 image={Empty.PRESENTED_IMAGE_SIMPLE}
 description="暂无分类"
 />
 )}
 </Card>
 <Card title="热门标签" extra={<TagOutlined />}>
 <Space size={[4, 8]} wrap>
 {popularTags.map(tag => (
 <Tag
 key={tag}
 color="default"
 style={{ cursor: 'pointer' }}
 onClick={() => handleTagClick(tag)}
 >
 {tag}
 </Tag>
 ))}
 </Space>
 </Card>
 </Col>
 <Col span={18}>
 {selectedCategory ? (
 <Card
 title={
 <Space>
 <Button
 type="text"
 icon={<ArrowLeftOutlined />}
 onClick={() => {
 setSelectedCategory(null);
 setCategoryDocs([]);
 }}
 />
 <FolderOutlined />
 <span>{selectedCategory.category_name}</span>
 </Space>
 }
 extra={
 <Space>
 <Tag color="blue">{selectedCategory.doc_count || 0} 个文档</Tag>
 <Button
 icon={<EditOutlined />}
 size="small"
 onClick={() => handleEdit(selectedCategory)}
 >
 编辑
 </Button>
 <Popconfirm
 title="确定要删除这个分类吗？"
 onConfirm={() => handleDelete(selectedCategory.category_id)}
 okText="确定"
 cancelText="取消"
 >
 <Button danger icon={<DeleteOutlined />} size="small">
 删除
 </Button>
 </Popconfirm>
 </Space>
 }
 >
 {selectedCategory.description && (
 <div style={{ marginBottom: 16, padding: 12, background: '#f5f5f5', borderRadius: 4 }}>
 <Text type="secondary">{selectedCategory.description}</Text>
 </div>
 )}
 <Row gutter={16} style={{ marginBottom: 24 }}>
 <Col span={6}>
 <Statistic
 title="文档数量"
 value={selectedCategory.doc_count || 0}
 />
 </Col>
 <Col span={6}>
 <Statistic
 title="创建时间"
 value={dayjs(selectedCategory.created_at).format('YYYY-MM-DD')}
 />
 </Col>
 </Row>
 <Title level={5}>分类下的文档</Title>
 <Table
 columns={columns}
 dataSource={categoryDocs}
 rowKey="doc_id"
 loading={loading}
 pagination={{
 ...pagination,
 showSizeChanger: true,
 showQuickJumper: true,
 showTotal: (total) => `共 ${total} 条文档`,
 onChange: (page, pageSize) => {
 setPagination(prev => ({ ...prev, current: page, pageSize }));
 if (selectedCategory) {
 fetchCategoryDocs(selectedCategory.category_name);
 }
 }
 }}
 scroll={{ x: 1000 }}
 />
 </Card>
 ) : (
 <Card title="分类概览">
 <Empty
 description="请从左侧选择一个分类查看详情，或创建新分类"
 />
 <div style={{ textAlign: 'center', marginTop: 24 }}>
 <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
 创建新分类
 </Button>
 </div>
 </Card>
 )}
 </Col>
 </Row>
 <Modal
 title="新建分类"
 open={createModalVisible}
 onCancel={() => setCreateModalVisible(false)}
 footer={null}
 width={500}
 >
 <Form
 form={form}
 layout="vertical"
 onFinish={handleCreateSubmit}
 >
 <Form.Item
 name="category_name"
 label="分类名称"
 rules={[{ required: true, message: '请输入分类名称' }]}
 >
 <Input placeholder="请输入分类名称" />
 </Form.Item>
 <Form.Item
 name="parent_category"
 label="父级分类"
 >
 <Select
 placeholder="请选择父级分类（可选）"
 allowClear
 >
 {categories.map(cat => (
 <Option key={cat.category_id} value={cat.category_id}>
 {cat.category_name}
 </Option>
 ))}
 </Select>
 </Form.Item>
 <Form.Item
 name="description"
 label="分类描述"
 >
 <TextArea rows={4} placeholder="请输入分类描述（可选）" />
 </Form.Item>
 <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
 <Space>
 <Button onClick={() => setCreateModalVisible(false)}>取消</Button>
 <Button type="primary" htmlType="submit">创建</Button>
 </Space>
 </Form.Item>
 </Form>
 </Modal>
 <Modal
 title="编辑分类"
 open={editModalVisible}
 onCancel={() => setEditModalVisible(false)}
 footer={null}
 width={500}
 >
 <Form
 form={editForm}
 layout="vertical"
 onFinish={handleEditSubmit}
 >
 <Form.Item
 name="category_name"
 label="分类名称"
 rules={[{ required: true, message: '请输入分类名称' }]}
 >
 <Input placeholder="请输入分类名称" />
 </Form.Item>
 <Form.Item
 name="parent_category"
 label="父级分类"
 >
 <Select
 placeholder="请选择父级分类（可选）"
 allowClear
 >
 {categories.filter(cat => cat.category_id !== selectedCategory?.category_id).map(cat => (
 <Option key={cat.category_id} value={cat.category_id}>
 {cat.category_name}
 </Option>
 ))}
 </Select>
 </Form.Item>
 <Form.Item
 name="description"
 label="分类描述"
 >
 <TextArea rows={4} placeholder="请输入分类描述（可选）" />
 </Form.Item>
 <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
 <Space>
 <Button onClick={() => setEditModalVisible(false)}>取消</Button>
 <Button type="primary" htmlType="submit">保存</Button>
 </Space>
 </Form.Item>
 </Form>
 </Modal>
 </div>
 );
};
export default CategoriesPage;
