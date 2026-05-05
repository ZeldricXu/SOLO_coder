import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Card,
  Table,
  Tag,
  Space,
  message,
  Popconfirm,
  Empty,
  Input,
  Select,
  Row,
  Col,
  Statistic
} from 'antd';
import {
  EditOutlined,
  HistoryOutlined,
  ShareAltOutlined,
  DeleteOutlined,
  StarFilled,
  StarOutlined,
  FileTextOutlined,
  FolderOutlined,
  TagOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { favoriteApi, categoryApi } from '../api/api';
const { Search } = Input;
const { Option } = Select;
const FavoritesPage = () => {
 const navigate = useNavigate();
 const [loading, setLoading] = useState(false);
 const [favorites, setFavorites] = useState([]);
 const [categories, setCategories] = useState([]);
 const [selectedCategory, setSelectedCategory] = useState(null);
 const [searchKeyword, setSearchKeyword] = useState('');
 const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
 useEffect(() => {
 fetchFavorites();
 fetchCategories();
 }, [pagination.current, pagination.pageSize]);
 const fetchFavorites = async () => {
 setLoading(true);
 try {
 const params = {
 page: pagination.current,
 pageSize: pagination.pageSize
 };
 if (selectedCategory) {
 params.category = selectedCategory;
 }
 if (searchKeyword?.trim()) {
 params.keyword = searchKeyword.trim();
 }
 const result = await favoriteApi.list(params);
 setFavorites(result.data?.docs || []);
 setPagination(prev => ({
 ...prev,
 total: result.data?.pagination?.total || 0
 }));
 }
 catch (error) {
 message.error(error.message || '获取收藏列表失败');
 }
 finally {
 setLoading(false);
 }
 };
 const fetchCategories = async () => {
 try {
 const result = await categoryApi.list();
 setCategories([{ category_name: '全部', category_id: null }, ...(result.data || [])]);
 }
 catch (error) {
 console.error('获取分类失败:', error);
 }
 };
 const handleToggleFavorite = async (docId, e) => {
 e.stopPropagation();
 try {
 await favoriteApi.toggle(docId);
 message.success('已取消收藏');
 fetchFavorites();
 }
 catch (error) {
 message.error(error.message || '操作失败');
 }
 };
 const handleEdit = (docId) => {
 navigate(`/edit/${docId}`);
 };
 const handleViewVersions = (docId) => {
 navigate(`/versions/${docId}`);
 };
 const handleShare = (docId) => {
 navigate(`/share/${docId}`);
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
 const handleSearch = (value) => {
 setSearchKeyword(value);
 setPagination(prev => ({ ...prev, current: 1 }));
 setTimeout(() => fetchFavorites(), 0);
 };
 const handleCategoryChange = (value) => {
 setSelectedCategory(value === null ? null : value);
 setPagination(prev => ({ ...prev, current: 1 }));
 setTimeout(() => fetchFavorites(), 0);
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
 render: (category) => (
 <Tag icon={<FolderOutlined />}>{category || '未分类'}</Tag>
 )
 },
 {
 title: '标签',
 dataIndex: 'tags',
 key: 'tags',
 width: 200,
 render: (tags) => (
 <Space size={[0, 4]} wrap>
 {tags?.map(tag => (
 <Tag key={tag} color="blue" icon={<TagOutlined />}>{tag}</Tag>
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
 width: 200,
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
 title="确定要取消收藏吗？"
 onConfirm={(e) => handleToggleFavorite(record.doc_id, e)}
 okText="确定"
 cancelText="取消"
 >
 <Button type="text" icon={<StarFilled style={{ color: '#faad14' }} />} />
 </Popconfirm>
 </Space>
 )
 }
 ];
 const totalFavorites = pagination.total;
 return (
 <div>
 <Card
 title={
 <Space>
 <StarFilled style={{ color: '#faad14' }} />
 <span>我的收藏</span>
 </Space>
 }
 extra={
 <Row gutter={16}>
 <Col>
 <Statistic
 title="收藏总数"
 value={totalFavorites}
 valueStyle={{ color: '#faad14', fontSize: 18 }}
 prefix={<StarFilled />}
 />
 </Col>
 </Row>
 }
 >
 <div style={{ marginBottom: 16 }}>
 <Row gutter={16}>
 <Col span={8}>
 <Search
 placeholder="搜索收藏的文档..."
 allowClear
 value={searchKeyword}
 onChange={(e) => setSearchKeyword(e.target.value)}
 onSearch={handleSearch}
 enterButton
 />
 </Col>
 <Col span={6}>
 <Select
 placeholder="按分类筛选"
 style={{ width: '100%' }}
 allowClear
 value={selectedCategory}
 onChange={handleCategoryChange}
 >
 {categories.map(cat => (
 <Option key={cat.category_id || 'all'} value={cat.category_name}>
 {cat.category_name}
 </Option>
 ))}
 </Select>
 </Col>
 </Row>
 </div>
 {favorites.length === 0 && !loading ? (
 <Empty
 image={Empty.PRESENTED_IMAGE_SIMPLE}
 description="暂无收藏的文档"
 >
 <Button
 type="primary"
 icon={<FileTextOutlined />}
 onClick={() => navigate('/documents')}
 >
 去浏览文档
 </Button>
 </Empty>
 ) : (
 <Table
 columns={columns}
 dataSource={favorites}
 rowKey="doc_id"
 loading={loading}
 pagination={{
 ...pagination,
 showSizeChanger: true,
 showQuickJumper: true,
 showTotal: (total) => `共 ${total} 条收藏`,
 onChange: (page, pageSize) => {
 setPagination(prev => ({ ...prev, current: page, pageSize }));
 }
 }}
 scroll={{ x: 1200 }}
 />
 )}
 </Card>
 </div>
 );
};
export default FavoritesPage;
