import React, { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
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
  Statistic,
  Empty,
  message,
  DatePicker,
  Tabs,
  Divider
} from 'antd';
import {
  SearchOutlined,
  EditOutlined,
  HistoryOutlined,
  ShareAltOutlined,
  DeleteOutlined,
  FileTextOutlined,
  TagOutlined,
  FolderOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { searchApi, documentApi, categoryApi } from '../api/api';
const { RangePicker } = DatePicker;
const { Search } = Input;
const { Option } = Select;
const { TabPane } = Tabs;
const SearchPage = () => {
 const navigate = useNavigate();
 const [searchParams, setSearchParams] = useSearchParams();
 const [loading, setLoading] = useState(false);
 const [results, setResults] = useState([]);
 const [statistics, setStatistics] = useState(null);
 const [keyword, setKeyword] = useState(searchParams.get('keyword') || '');
 const [selectedCategory, setSelectedCategory] = useState(searchParams.get('category') || null);
 const [selectedTags, setSelectedTags] = useState([]);
 const [categories, setCategories] = useState([]);
 const [popularTags, setPopularTags] = useState([]);
 const [dateRange, setDateRange] = useState(null);
 const [pagination, setPagination] = useState({ current: 1, pageSize: 10, total: 0 });
 const [activeTab, setActiveTab] = useState('all');
 useEffect(() => {
 fetchCategories();
 fetchPopularTags();
 const initialKeyword = searchParams.get('keyword');
 const initialCategory = searchParams.get('category');
 if (initialKeyword || initialCategory) {
 if (initialKeyword)
 setKeyword(initialKeyword);
 if (initialCategory)
 setSelectedCategory(initialCategory);
 executeSearch();
 }
 }, []);
 const fetchCategories = async () => {
 try {
 const result = await categoryApi.list();
 setCategories([{ category_name: '未分类', category_id: null }, ...(result.data || [])]);
 }
 catch (error) {
 console.error('获取分类失败:', error);
 }
 };
 const fetchPopularTags = async () => {
 try {
 const result = await categoryApi.popularTags(20);
 setPopularTags(result.data?.tags || []);
 }
 catch (error) {
 console.error('获取热门标签失败:', error);
 }
 };
 const executeSearch = async () => {
 if (!keyword?.trim() && !selectedCategory && selectedTags.length === 0) {
 message.info('请输入搜索条件');
 return;
 }
 setLoading(true);
 try {
 const params = {
 page: pagination.current,
 pageSize: pagination.pageSize
 };
 if (keyword?.trim()) {
 params.keyword = keyword.trim();
 }
 if (selectedCategory) {
 params.category = selectedCategory;
 }
 if (selectedTags.length > 0) {
 params.tags = selectedTags.join(',');
 }
 if (dateRange && dateRange.length === 2) {
 params.start_date = dateRange[0].format('YYYY-MM-DD');
 params.end_date = dateRange[1].format('YYYY-MM-DD');
 }
 const result = await searchApi.search(params);
 setResults(result.data?.docs || []);
 setStatistics(result.data?.statistics);
 setPagination(prev => ({
 ...prev,
 total: result.data?.pagination?.total || 0
 }));
 }
 catch (error) {
 message.error(error.message || '搜索失败');
 }
 finally {
 setLoading(false);
 }
 };
 const handleSearch = (value) => {
 setKeyword(value);
 setPagination(prev => ({ ...prev, current: 1 }));
 executeSearch();
 };
 const handleCategoryChange = (value) => {
 setSelectedCategory(value);
 setPagination(prev => ({ ...prev, current: 1 }));
 };
 const handleTagsChange = (value) => {
 setSelectedTags(value);
 setPagination(prev => ({ ...prev, current: 1 }));
 };
 const handleDateRangeChange = (dates) => {
 setDateRange(dates);
 setPagination(prev => ({ ...prev, current: 1 }));
 };
 const handleQuickSearch = (quickKeyword) => {
 setKeyword(quickKeyword);
 setPagination(prev => ({ ...prev, current: 1 }));
 executeSearch();
 };
 const handleTagClick = (tag) => {
 if (!selectedTags.includes(tag)) {
 const newTags = [...selectedTags, tag];
 setSelectedTags(newTags);
 setPagination(prev => ({ ...prev, current: 1 }));
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
 title: '更新时间',
 dataIndex: 'updated_at',
 key: 'updated_at',
 width: 180,
 render: (time) => dayjs(time).format('YYYY-MM-DD HH:mm:ss')
 },
 {
 title: '操作',
 key: 'actions',
 width: 150,
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
 </Space>
 )
 }
 ];
 const renderSearchResults = () => {
 if (loading) {
 return <div style={{ textAlign: 'center', padding: 50 }}>搜索中...</div>;
 }
 if (results.length === 0 && (keyword || selectedCategory || selectedTags.length > 0)) {
 return (
 <Empty
 image={Empty.PRESENTED_IMAGE_SIMPLE}
 description="未找到匹配的文档"
 />
 );
 }
 return (
 <Table
 columns={columns}
 dataSource={results}
 rowKey="doc_id"
 loading={loading}
 pagination={{
 ...pagination,
 showSizeChanger: true,
 showQuickJumper: true,
 showTotal: (total) => `共 ${total} 条结果`,
 onChange: (page, pageSize) => {
 setPagination(prev => ({ ...prev, current: page, pageSize }));
 executeSearch();
 }
 }}
 scroll={{ x: 1200 }}
 />
 );
 };
 return (
 <div>
 <Card title="文档检索">
 <div style={{ marginBottom: 24 }}>
 <Row gutter={16}>
 <Col span={12}>
 <Search
 placeholder="输入关键字搜索文档..."
 allowClear
 value={keyword}
 onChange={(e) => setKeyword(e.target.value)}
 onSearch={handleSearch}
 enterButton={
 <Button type="primary" icon={<SearchOutlined />}>
 搜索
 </Button>
 }
 size="large"
 />
 </Col>
 </Row>
 </div>
 <Row gutter={24}>
 <Col span={18}>
 <Tabs activeKey={activeTab} onChange={setActiveTab}>
 <TabPane tab="搜索结果" key="all">
 {renderSearchResults()}
 </TabPane>
 </Tabs>
 </Col>
 <Col span={6}>
 {statistics && (
 <Card title="搜索统计" size="small" style={{ marginBottom: 16 }}>
 <Row gutter={16}>
 <Col span={12}>
 <Statistic
 title="总文档数"
 value={statistics.total}
 prefix={<FileTextOutlined />}
 />
 </Col>
 <Col span={12}>
 <Statistic
 title="匹配数"
 value={statistics.matched}
 valueStyle={{ color: '#3f8600' }}
 />
 </Col>
 </Row>
 {statistics.by_category && Object.keys(statistics.by_category).length > 0 && (
 <div style={{ marginTop: 16 }}>
 <Divider style={{ margin: '12px 0' }} />
 <div style={{ fontWeight: 500, marginBottom: 8 }}>按分类分布</div>
 {Object.entries(statistics.by_category).map(([cat, count]) => (
 <div
 key={cat}
 style={{
 display: 'flex',
 justifyContent: 'space-between',
 padding: '4px 0'
 }}
 >
 <Tag>{cat}</Tag>
 <span>{count} 个</span>
 </div>
 ))}
 </div>
 )}
 </Card>
 )}
 <Card title="热门标签" size="small" style={{ marginBottom: 16 }} extra={<TagOutlined />}>
 <Space size={[4, 8]} wrap>
 {popularTags.map(tag => (
 <Tag
 key={tag}
 color={selectedTags.includes(tag) ? 'blue' : 'default'}
 style={{ cursor: 'pointer' }}
 onClick={() => handleTagClick(tag)}
 >
 {tag}
 </Tag>
 ))}
 </Space>
 </Card>
 <Card title="快速筛选" size="small">
 <div style={{ marginBottom: 12 }}>
 <div style={{ fontWeight: 500, marginBottom: 8 }}>分类筛选</div>
 <Select
 placeholder="选择分类"
 style={{ width: '100%' }}
 allowClear
 value={selectedCategory}
 onChange={handleCategoryChange}
 >
 {categories.map(cat => (
 <Option key={cat.category_id || 'uncategorized'} value={cat.category_name}>
 {cat.category_name}
 </Option>
 ))}
 </Select>
 </div>
 <div style={{ marginBottom: 12 }}>
 <div style={{ fontWeight: 500, marginBottom: 8 }}>标签筛选</div>
 <Select
 mode="tags"
 placeholder="选择或输入标签"
 style={{ width: '100%' }}
 value={selectedTags}
 onChange={handleTagsChange}
 tokenSeparators={[',']}
 />
 </div>
 <div style={{ marginBottom: 12 }}>
 <div style={{ fontWeight: 500, marginBottom: 8 }}>时间范围</div>
 <RangePicker
 style={{ width: '100%' }}
 value={dateRange}
 onChange={handleDateRangeChange}
 />
 </div>
 <Button
 type="primary"
 block
 icon={<SearchOutlined />}
 onClick={executeSearch}
 loading={loading}
 >
 执行搜索
 </Button>
 </Card>
 </Col>
 </Row>
 </Card>
 </div>
 );
};
export default SearchPage;
