import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Card,
  Table,
  Tag,
  Space,
  message,
  Empty,
  Tabs,
  Statistic,
  Row,
  Col,
  Timeline,
  Typography
} from 'antd';
import {
  EditOutlined,
  HistoryOutlined,
  ShareAltOutlined,
  ClockCircleOutlined,
  FileTextOutlined,
  FolderOutlined,
  TagOutlined,
  StarOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { searchApi } from '../api/api';
dayjs.extend(relativeTime);
const { TabPane } = Tabs;
const { Title, Text } = Typography;
const RecentPage = () => {
 const navigate = useNavigate();
 const [loading, setLoading] = useState(false);
 const [recentDocs, setRecentDocs] = useState([]);
 const [activeTab, setActiveTab] = useState('list');
 useEffect(() => {
 fetchRecentDocs();
 }, []);
 const fetchRecentDocs = async () => {
 setLoading(true);
 try {
 const result = await searchApi.recent(50);
 setRecentDocs(result.data?.docs || []);
 }
 catch (error) {
 message.error(error.message || '获取最近文档失败');
 }
 finally {
 setLoading(false);
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
 render: (time) => (
 <Space>
 <Text type="secondary">{dayjs(time).format('YYYY-MM-DD HH:mm')}</Text>
 <Tag color="default">{dayjs(time).fromNow()}</Tag>
 </Space>
 )
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
 const stats = {
 total: recentDocs.length,
 today: recentDocs.filter(d => dayjs(d.updated_at).isSame(dayjs(), 'day')).length,
 thisWeek: recentDocs.filter(d => dayjs(d.updated_at).isSame(dayjs(), 'week')).length
 };
 const groupByDate = () => {
 const groups = {};
 recentDocs.forEach(doc => {
 const date = dayjs(doc.updated_at).format('YYYY-MM-DD');
 if (!groups[date]) {
 groups[date] = [];
 }
 groups[date].push(doc);
 });
 return Object.entries(groups).sort((a, b) => b[0].localeCompare(a[0]));
 };
 const renderTimeline = () => {
 const grouped = groupByDate();
 return (
 <Timeline mode="left" style={{ padding: '20px 0' }}>
 {grouped.map(([date, docs]) => (
 <Timeline.Item
 key={date}
 dot={<ClockCircleOutlined style={{ fontSize: '16px' }} />}
 label={dayjs(date).format('YYYY年MM月DD日')}
 >
 <Card size="small" style={{ marginBottom: 16 }}>
 {docs.map(doc => (
 <div
 key={doc.doc_id}
 style={{
 padding: '8px 0',
 borderBottom: '1px solid #f0f0f0',
 cursor: 'pointer'
 }}
 onClick={() => handleEdit(doc.doc_id)}
 >
 <Space direction="vertical" size={0} style={{ width: '100%' }}>
 <Space>
 <FileTextOutlined style={{ color: '#1890ff' }} />
 <Text strong>{doc.title}</Text>
 {getStatusTag(doc.status)}
 </Space>
 <Space size={16}>
 <Text type="secondary" style={{ fontSize: 12 }}>
 版本: {doc.current_version}
 </Text>
 <Text type="secondary" style={{ fontSize: 12 }}>
 {dayjs(doc.updated_at).format('HH:mm')}
 </Text>
 {doc.tags?.slice(0, 2).map(tag => (
 <Tag key={tag} color="blue" style={{ fontSize: 11, margin: 0 }}>
 {tag}
 </Tag>
 ))}
 </Space>
 </Space>
 </div>
 ))}
 </Card>
 </Timeline.Item>
 ))}
 </Timeline>
 );
 };
 return (
 <div>
 <Card
 title={
 <Space>
 <ClockCircleOutlined style={{ color: '#1890ff' }} />
 <span>最近文档</span>
 </Space>
 }
 extra={
 <Row gutter={24}>
 <Col>
 <Statistic
 title="今日更新"
 value={stats.today}
 valueStyle={{ color: '#3f8600', fontSize: 18 }}
 />
 </Col>
 <Col>
 <Statistic
 title="本周更新"
 value={stats.thisWeek}
 valueStyle={{ color: '#1890ff', fontSize: 18 }}
 />
 </Col>
 <Col>
 <Statistic
 title="总计"
 value={stats.total}
 valueStyle={{ color: '#722ed1', fontSize: 18 }}
 prefix={<FileTextOutlined />}
 />
 </Col>
 </Row>
 }
 >
 {recentDocs.length === 0 && !loading ? (
 <Empty
 image={Empty.PRESENTED_IMAGE_SIMPLE}
 description="暂无最近访问的文档"
 >
 <Button
 type="primary"
 icon={<FileTextOutlined />}
 onClick={() => navigate('/documents')}
 >
 去创建文档
 </Button>
 </Empty>
 ) : (
 <Tabs activeKey={activeTab} onChange={setActiveTab}>
 <TabPane tab="列表视图" key="list">
 <Table
 columns={columns}
 dataSource={recentDocs}
 rowKey="doc_id"
 loading={loading}
 pagination={{
 pageSize: 20,
 showSizeChanger: true,
 showQuickJumper: true,
 showTotal: (total) => `共 ${total} 条文档`
 }}
 scroll={{ x: 1200 }}
 />
 </TabPane>
 <TabPane tab="时间轴视图" key="timeline">
 {renderTimeline()}
 </TabPane>
 </Tabs>
 )}
 </Card>
 </div>
 );
};
export default RecentPage;
