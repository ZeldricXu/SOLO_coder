import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Button,
  Card,
  Table,
  Tag,
  Space,
  Modal,
  message,
  Popconfirm,
  Empty,
  Tabs,
  Select,
  Timeline,
  Descriptions,
  Statistic,
  Row,
  Col,
  Divider,
  Typography
} from 'antd';
import {
  EditOutlined,
  HistoryOutlined,
  ShareAltOutlined,
  ArrowLeftOutlined,
  RollbackOutlined,
  SwapOutlined,
  FileTextOutlined,
  UserOutlined,
  ClockCircleOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { versionApi, documentApi } from '../api/api';
const { TabPane } = Tabs;
const { Option } = Select;
const { Title, Text, Paragraph } = Typography;
const VersionPage = () => {
 const { docId } = useParams();
 const navigate = useNavigate();
 const [loading, setLoading] = useState(false);
 const [document, setDocument] = useState(null);
 const [versions, setVersions] = useState([]);
 const [activeTab, setActiveTab] = useState('list');
 const [compareModalVisible, setCompareModalVisible] = useState(false);
 const [compareFrom, setCompareFrom] = useState(null);
 const [compareTo, setCompareTo] = useState(null);
 const [compareResult, setCompareResult] = useState(null);
 const [compareLoading, setCompareLoading] = useState(false);
 const [selectedVersion, setSelectedVersion] = useState(null);
 const [versionDetail, setVersionDetail] = useState(null);
 const [detailModalVisible, setDetailModalVisible] = useState(false);
 useEffect(() => {
 if (docId) {
 fetchDocument();
 fetchVersions();
 }
 }, [docId]);
 const fetchDocument = async () => {
 try {
 const result = await documentApi.get(docId);
 setDocument(result.data);
 }
 catch (error) {
 message.error(error.message || '获取文档信息失败');
 }
 };
 const fetchVersions = async () => {
 setLoading(true);
 try {
 const result = await versionApi.getHistory(docId);
 setVersions(result.data?.versions || []);
 }
 catch (error) {
 message.error(error.message || '获取版本历史失败');
 }
 finally {
 setLoading(false);
 }
 };
 const handleBack = () => {
 navigate(`/edit/${docId}`);
 };
 const handleViewDetail = async (version) => {
 try {
 const result = await versionApi.getVersion(docId, version.version);
 setVersionDetail(result.data);
 setSelectedVersion(version);
 setDetailModalVisible(true);
 }
 catch (error) {
 message.error(error.message || '获取版本详情失败');
 }
 };
 const handleRestore = async (version) => {
 try {
 await versionApi.restore(docId, version.version);
 message.success(`已恢复到版本 ${version.version}`);
 fetchVersions();
 fetchDocument();
 }
 catch (error) {
 message.error(error.message || '版本恢复失败');
 }
 };
 const handleCompare = () => {
 if (versions.length < 2) {
 message.warning('至少需要2个版本才能进行比对');
 return;
 }
 setCompareFrom(versions[versions.length - 1]?.version);
 setCompareTo(versions[0]?.version);
 setCompareModalVisible(true);
 };
 const executeCompare = async () => {
 if (!compareFrom || !compareTo) {
 message.warning('请选择要比对的两个版本');
 return;
 }
 setCompareLoading(true);
 try {
 const result = await versionApi.compare(docId, compareFrom, compareTo);
 setCompareResult(result.data);
 }
 catch (error) {
 message.error(error.message || '版本比对失败');
 }
 finally {
 setCompareLoading(false);
 }
 };
 const renderDiffLine = (diff, index) => {
 if (diff.type === 'added') {
 return (
 <div key={index} style={{ background: '#f6ffed', padding: '4px 8px' }}>
 <Text type="success">+ {diff.value}</Text>
 </div>
 );
 }
 if (diff.type === 'removed') {
 return (
 <div key={index} style={{ background: '#fff1f0', padding: '4px 8px' }}>
 <Text type="danger">- {diff.value}</Text>
 </div>
 );
 }
 return (
 <div key={index} style={{ padding: '4px 8px' }}>
 <Text type="secondary"> {diff.value}</Text>
 </div>
 );
 };
 const renderDiffContent = () => {
 if (!compareResult) {
 return (
 <div style={{ textAlign: 'center', padding: 40, color: '#999' }}>
 点击"开始比对"按钮查看差异
 </div>
 );
 }
 if (compareResult.is_same) {
 return (
 <div style={{ textAlign: 'center', padding: 40 }}>
 <Text type="success">两个版本内容完全相同</Text>
 </div>
 );
 }
 if (compareResult.diff?.length > 0) {
 return (
 <div style={{ background: '#fff', border: '1px solid #d9d9d9', borderRadius: 4 }}>
 {compareResult.diff.map((diff, index) => renderDiffLine(diff, index))}
 </div>
 );
 }
 if (compareResult.from_content && compareResult.to_content) {
 return (
 <Row gutter={16}>
 <Col span={12}>
 <Card size="small" title={`版本 ${compareFrom}`} style={{ height: '100%' }}>
 <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0 }}>
 {compareResult.from_content}
 </pre>
 </Card>
 </Col>
 <Col span={12}>
 <Card size="small" title={`版本 ${compareTo}`} style={{ height: '100%' }}>
 <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', margin: 0 }}>
 {compareResult.to_content}
 </pre>
 </Card>
 </Col>
 </Row>
 );
 }
 return null;
 };
 const columns = [
 {
 title: '版本',
 dataIndex: 'version',
 key: 'version',
 width: 100,
 render: (version) => (
 <Tag color="blue">{version}</Tag>
 )
 },
 {
 title: '变更描述',
 dataIndex: 'change_desc',
 key: 'change_desc',
 ellipsis: true,
 render: (text) => text || '无描述'
 },
 {
 title: '作者',
 dataIndex: 'author',
 key: 'author',
 width: 120,
 render: (author) => (
 <Space>
 <UserOutlined />
 <span>{author || '未知'}</span>
 </Space>
 )
 },
 {
 title: '创建时间',
 dataIndex: 'created_at',
 key: 'created_at',
 width: 180,
 render: (time) => (
 <Space>
 <ClockCircleOutlined />
 <span>{dayjs(time).format('YYYY-MM-DD HH:mm:ss')}</span>
 </Space>
 )
 },
 {
 title: '操作',
 key: 'actions',
 width: 200,
 render: (_, record) => (
 <Space size="small">
 <Button
 type="text"
 icon={<FileTextOutlined />}
 onClick={() => handleViewDetail(record)}
 >
 查看
 </Button>
 <Popconfirm
 title={`确定要恢复到版本 ${record.version} 吗？`}
 onConfirm={() => handleRestore(record)}
 okText="确定"
 cancelText="取消"
 >
 <Button type="text" danger icon={<RollbackOutlined />}>
 恢复
 </Button>
 </Popconfirm>
 </Space>
 )
 }
 ];
 const renderTimeline = () => (
 <Timeline mode="left" style={{ padding: '20px 0' }}>
 {versions.map((version, index) => (
 <Timeline.Item
 key={version.version_id}
 dot={
 index === 0 ? (
 <HistoryOutlined style={{ fontSize: '16px', color: '#1890ff' }} />
 ) : (
 <HistoryOutlined style={{ fontSize: '14px' }} />
 )
 }
 color={index === 0 ? 'blue' : 'gray'}
 >
 <Card
 size="small"
 style={{ marginBottom: 8 }}
 extra={
 <Space>
 <Button
 type="link"
 size="small"
 onClick={() => handleViewDetail(version)}
 >
 查看详情
 </Button>
 <Popconfirm
 title={`确定要恢复到版本 ${version.version} 吗？`}
 onConfirm={() => handleRestore(version)}
 >
 <Button type="link" size="small" danger>
 恢复
 </Button>
 </Popconfirm>
 </Space>
 }
 >
 <Space direction="vertical" size="small" style={{ width: '100%' }}>
 <Space>
 <Tag color={index === 0 ? 'green' : 'default'}>
 {version.version}
 {index === 0 && ' (当前)'}
 </Tag>
 <Text strong>{version.change_desc || '无变更描述'}</Text>
 </Space>
 <Space size={16}>
 <Text type="secondary">
 作者: {version.author || '未知'}
 </Text>
 <Text type="secondary">
 时间: {dayjs(version.created_at).format('YYYY-MM-DD HH:mm:ss')}
 </Text>
 </Space>
 </Space>
 </Card>
 </Timeline.Item>
 ))}
 </Timeline>
 );
 return (
 <div>
 <Card
 title={
 <Space>
 <Button
 type="text"
 icon={<ArrowLeftOutlined />}
 onClick={handleBack}
 />
 <HistoryOutlined style={{ color: '#1890ff' }} />
 <span>版本管理</span>
 {document && (
 <Tag color="blue">{document.title}</Tag>
 )}
 </Space>
 }
 extra={
 <Space>
 {document && (
 <>
 <Statistic
 title="当前版本"
 value={document.current_version}
 valueStyle={{ color: '#1890ff', fontSize: 16 }}
 />
 <Statistic
 title="版本总数"
 value={versions.length}
 valueStyle={{ color: '#722ed1', fontSize: 16 }}
 />
 </>
 )}
 <Button
 type="primary"
 icon={<SwapOutlined />}
 onClick={handleCompare}
 disabled={versions.length < 2}
 >
 版本比对
 </Button>
 </Space>
 }
 >
 {document && (
 <div style={{ marginBottom: 16, padding: 16, background: '#f5f5f5', borderRadius: 4 }}>
 <Descriptions size="small" column={4}>
 <Descriptions.Item label="文档标题">{document.title}</Descriptions.Item>
 <Descriptions.Item label="当前版本">
 <Tag color="green">{document.current_version}</Tag>
 </Descriptions.Item>
 <Descriptions.Item label="版本总数">{versions.length} 个</Descriptions.Item>
 <Descriptions.Item label="最后更新">
 {dayjs(document.updated_at).format('YYYY-MM-DD HH:mm:ss')}
 </Descriptions.Item>
 </Descriptions>
 </div>
 )}
 {versions.length === 0 && !loading ? (
 <Empty
 image={Empty.PRESENTED_IMAGE_SIMPLE}
 description="暂无版本历史"
 />
 ) : (
 <Tabs activeKey={activeTab} onChange={setActiveTab}>
 <TabPane tab="列表视图" key="list">
 <Table
 columns={columns}
 dataSource={versions}
 rowKey="version_id"
 loading={loading}
 pagination={{
 pageSize: 20,
 showSizeChanger: true,
 showQuickJumper: true,
 showTotal: (total) => `共 ${total} 个版本`
 }}
 scroll={{ x: 800 }}
 />
 </TabPane>
 <TabPane tab="时间轴视图" key="timeline">
 {renderTimeline()}
 </TabPane>
 </Tabs>
 )}
 </Card>
 <Modal
 title="版本比对"
 open={compareModalVisible}
 onCancel={() => {
 setCompareModalVisible(false);
 setCompareResult(null);
 }}
 width={900}
 footer={null}
 >
 <Row gutter={16} style={{ marginBottom: 16 }}>
 <Col span={10}>
 <Select
 style={{ width: '100%' }}
 placeholder="选择版本1"
 value={compareFrom}
 onChange={setCompareFrom}
 >
 {versions.map(v => (
 <Option key={v.version} value={v.version}>
 {v.version} - {v.change_desc || '无描述'}
 </Option>
 ))}
 </Select>
 </Col>
 <Col span={4} style={{ textAlign: 'center' }}>
 <SwapOutlined style={{ fontSize: 24, color: '#1890ff' }} />
 </Col>
 <Col span={10}>
 <Select
 style={{ width: '100%' }}
 placeholder="选择版本2"
 value={compareTo}
 onChange={setCompareTo}
 >
 {versions.map(v => (
 <Option key={v.version} value={v.version}>
 {v.version} - {v.change_desc || '无描述'}
 </Option>
 ))}
 </Select>
 </Col>
 </Row>
 <div style={{ textAlign: 'center', marginBottom: 16 }}>
 <Button
 type="primary"
 icon={<SwapOutlined />}
 onClick={executeCompare}
 loading={compareLoading}
 >
 开始比对
 </Button>
 </div>
 <Divider />
 {renderDiffContent()}
 </Modal>
 <Modal
 title={
 <Space>
 <FileTextOutlined />
 <span>版本详情</span>
 {selectedVersion && (
 <Tag color="blue">{selectedVersion.version}</Tag>
 )}
 </Space>
 }
 open={detailModalVisible}
 onCancel={() => setDetailModalVisible(false)}
 width={800}
 footer={
 <Space>
 {selectedVersion && (
 <Popconfirm
 title={`确定要恢复到版本 ${selectedVersion.version} 吗？`}
 onConfirm={() => {
 handleRestore(selectedVersion);
 setDetailModalVisible(false);
 }}
 >
 <Button type="primary" danger icon={<RollbackOutlined />}>
 恢复到此版本
 </Button>
 </Popconfirm>
 )}
 <Button onClick={() => setDetailModalVisible(false)}>关闭</Button>
 </Space>
 }
 >
 {versionDetail && (
 <>
 <Descriptions bordered size="small" column={2} style={{ marginBottom: 16 }}>
 <Descriptions.Item label="版本">{versionDetail.version}</Descriptions.Item>
 <Descriptions.Item label="作者">{versionDetail.author || '未知'}</Descriptions.Item>
 <Descriptions.Item label="创建时间">
 {dayjs(versionDetail.created_at).format('YYYY-MM-DD HH:mm:ss')}
 </Descriptions.Item>
 <Descriptions.Item label="变更描述">
 {versionDetail.change_desc || '无描述'}
 </Descriptions.Item>
 </Descriptions>
 <Divider>版本内容</Divider>
 <Card size="small">
 {versionDetail.content ? (
 <div
 className="version-content"
 dangerouslySetInnerHTML={{ __html: versionDetail.content }}
 />
 ) : (
 <Text type="secondary">此版本没有内容</Text>
 )}
 </Card>
 </>
 )}
 </Modal>
 </div>
 );
};
export default VersionPage;
