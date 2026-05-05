import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Button,
  Card,
  Table,
  Tag,
  Space,
  Modal,
  Form,
  Input,
  Select,
  message,
  Popconfirm,
  Empty,
  Tabs,
  DatePicker,
  Descriptions,
  Statistic,
  Row,
  Col,
  Divider,
  Typography,
  Radio,
  CopyOutlined
} from 'antd';
import {
  EditOutlined,
  ShareAltOutlined,
  ArrowLeftOutlined,
  UserOutlined,
  TeamOutlined,
  GlobalOutlined,
  LockOutlined,
  UnlockOutlined,
  EyeOutlined,
  DeleteOutlined,
  LinkOutlined,
  PlusOutlined,
  CopyToClipboard,
  CopyOutlined as CopyIcon
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { shareApi, documentApi } from '../api/api';
const { TabPane } = Tabs;
const { Option } = Select;
const { TextArea } = Input;
const { RadioGroup } = Radio;
const { Title, Text, Paragraph } = Typography;
const SharePage = () => {
 const { docId } = useParams();
 const navigate = useNavigate();
 const [loading, setLoading] = useState(false);
 const [document, setDocument] = useState(null);
 const [shares, setShares] = useState([]);
 const [activeTab, setActiveTab] = useState('list');
 const [createModalVisible, setCreateModalVisible] = useState(false);
 const [form] = Form.useForm();
 const [accessInfo, setAccessInfo] = useState(null);
 useEffect(() => {
 if (docId) {
 fetchDocument();
 fetchShares();
 checkAccess();
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
 const fetchShares = async () => {
 setLoading(true);
 try {
 const result = await shareApi.list(docId);
 setShares(result.data?.shares || []);
 }
 catch (error) {
 message.error(error.message || '获取分享列表失败');
 }
 finally {
 setLoading(false);
 }
 };
 const checkAccess = async () => {
 try {
 const result = await shareApi.checkAccess(docId);
 setAccessInfo(result.data);
 }
 catch (error) {
 console.error('检查访问权限失败:', error);
 }
 };
 const handleBack = () => {
 navigate(`/edit/${docId}`);
 };
 const handleCreate = () => {
 form.resetFields();
 setCreateModalVisible(true);
 };
 const handleCreateSubmit = async (values) => {
 try {
 const shareData = {
 share_type: values.share_type,
 permission: values.permission,
 target_id: values.target_id || null,
 expires_at: values.expires_at ? values.expires_at.toISOString() : null
 };
 await shareApi.share(docId, shareData);
 message.success('分享创建成功');
 setCreateModalVisible(false);
 form.resetFields();
 fetchShares();
 checkAccess();
 }
 catch (error) {
 message.error(error.message || '创建分享失败');
 }
 };
 const handleRevoke = async (shareId) => {
 try {
 await shareApi.revoke(shareId);
 message.success('分享已撤销');
 fetchShares();
 checkAccess();
 }
 catch (error) {
 message.error(error.message || '撤销分享失败');
 }
 };
 const getShareTypeIcon = (type) => {
 switch (type) {
 case 'user':
 return <UserOutlined />;
 case 'team':
 return <TeamOutlined />;
 case 'public':
 return <GlobalOutlined />;
 default:
 return <ShareAltOutlined />;
 }
 };
 const getShareTypeText = (type) => {
 const map = {
 user: '用户分享',
 team: '团队分享',
 public: '公开分享'
 };
 return map[type] || type;
 };
 const getPermissionTag = (permission) => {
 const map = {
 read: { color: 'blue', text: '只读', icon: <EyeOutlined /> },
 write: { color: 'green', text: '可编辑', icon: <EditOutlined /> },
 admin: { color: 'purple', text: '管理', icon: <LockOutlined /> }
 };
 const config = map[permission] || map.read;
 return <Tag color={config.color} icon={config.icon}>{config.text}</Tag>;
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
 title: '分享类型',
 dataIndex: 'share_type',
 key: 'share_type',
 width: 120,
 render: (type) => (
 <Space>
 {getShareTypeIcon(type)}
 <span>{getShareTypeText(type)}</span>
 </Space>
 )
 },
 {
 title: '目标',
 dataIndex: 'target_id',
 key: 'target_id',
 width: 150,
 render: (target, record) => {
 if (record.share_type === 'public') {
 return <Tag color="green">公开访问</Tag>;
 }
 return target || '全部';
 }
 },
 {
 title: '权限',
 dataIndex: 'permission',
 key: 'permission',
 width: 120,
 render: getPermissionTag
 },
 {
 title: '创建者',
 dataIndex: 'created_by',
 key: 'created_by',
 width: 120,
 render: (createdBy) => (
 <Space>
 <UserOutlined />
 <span>{createdBy || '未知'}</span>
 </Space>
 )
 },
 {
 title: '创建时间',
 dataIndex: 'created_at',
 key: 'created_at',
 width: 180,
 render: (time) => dayjs(time).format('YYYY-MM-DD HH:mm:ss')
 },
 {
 title: '过期时间',
 dataIndex: 'expires_at',
 key: 'expires_at',
 width: 180,
 render: (time) => {
 if (!time) {
 return <Text type="secondary">永不过期</Text>;
 }
 const isExpired = dayjs(time).isBefore(dayjs());
 return (
 <Tag color={isExpired ? 'red' : 'orange'}>
 {isExpired ? '已过期' : dayjs(time).format('YYYY-MM-DD HH:mm')}
 </Tag>
 );
 }
 },
 {
 title: '操作',
 key: 'actions',
 width: 150,
 render: (_, record) => (
 <Space size="small">
 <Popconfirm
 title="确定要撤销这个分享吗？"
 onConfirm={() => handleRevoke(record.share_id)}
 okText="确定"
 cancelText="取消"
 >
 <Button type="text" danger icon={<DeleteOutlined />}>
 撤销
 </Button>
 </Popconfirm>
 </Space>
 )
 }
 ];
 const stats = {
 total: shares.length,
 public: shares.filter(s => s.share_type === 'public').length,
 team: shares.filter(s => s.share_type === 'team').length,
 user: shares.filter(s => s.share_type === 'user').length
 };
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
 <ShareAltOutlined style={{ color: '#1890ff' }} />
 <span>分享管理</span>
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
 title="文档状态"
 value={
 <Tag>{document.status}</Tag>
 }
 />
 <Statistic
 title="分享总数"
 value={stats.total}
 valueStyle={{ color: '#1890ff', fontSize: 16 }}
 prefix={<ShareAltOutlined />}
 />
 </>
 )}
 <Button
 type="primary"
 icon={<PlusOutlined />}
 onClick={handleCreate}
 >
 新建分享
 </Button>
 </Space>
 }
 >
 {document && (
 <div style={{ marginBottom: 16, padding: 16, background: '#f5f5f5', borderRadius: 4 }}>
 <Descriptions size="small" column={4}>
 <Descriptions.Item label="文档标题">{document.title}</Descriptions.Item>
 <Descriptions.Item label="当前状态">
 {getStatusTag(document.status)}
 </Descriptions.Item>
 <Descriptions.Item label="当前版本">
 <Tag color="blue">{document.current_version}</Tag>
 </Descriptions.Item>
 <Descriptions.Item label="最后更新">
 {dayjs(document.updated_at).format('YYYY-MM-DD HH:mm:ss')}
 </Descriptions.Item>
 </Descriptions>
 {accessInfo && (
 <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px solid #d9d9d9' }}>
 <Space size={24}>
 <Text type="secondary">
 访问权限: {accessInfo.has_access ? (
 <Tag color="green" icon={<UnlockOutlined />}>可访问</Tag>
 ) : (
 <Tag color="red" icon={<LockOutlined />}>无权限</Tag>
 )}
 </Text>
 {accessInfo.permission && (
 <Text type="secondary">
 当前权限: {getPermissionTag(accessInfo.permission)}
 </Text>
 )}
 {accessInfo.is_owner && (
 <Tag color="purple" icon={<LockOutlined />}>文档所有者</Tag>
 )}
 </Space>
 </div>
 )}
 </div>
 )}
 <Row gutter={16} style={{ marginBottom: 16 }}>
 <Col span={6}>
 <Card size="small">
 <Statistic
 title="公开分享"
 value={stats.public}
 valueStyle={{ color: '#52c41a' }}
 prefix={<GlobalOutlined />}
 />
 </Card>
 </Col>
 <Col span={6}>
 <Card size="small">
 <Statistic
 title="团队分享"
 value={stats.team}
 valueStyle={{ color: '#1890ff' }}
 prefix={<TeamOutlined />}
 />
 </Card>
 </Col>
 <Col span={6}>
 <Card size="small">
 <Statistic
 title="用户分享"
 value={stats.user}
 valueStyle={{ color: '#722ed1' }}
 prefix={<UserOutlined />}
 />
 </Card>
 </Col>
 <Col span={6}>
 <Card size="small">
 <Statistic
 title="总计"
 value={stats.total}
 valueStyle={{ color: '#fa8c16' }}
 prefix={<ShareAltOutlined />}
 />
 </Card>
 </Col>
 </Row>
 {shares.length === 0 && !loading ? (
 <Empty
 image={Empty.PRESENTED_IMAGE_SIMPLE}
 description="暂无分享记录"
 >
 <Button
 type="primary"
 icon={<PlusOutlined />}
 onClick={handleCreate}
 >
 创建分享
 </Button>
 </Empty>
 ) : (
 <Tabs activeKey={activeTab} onChange={setActiveTab}>
 <TabPane tab="分享列表" key="list">
 <Table
 columns={columns}
 dataSource={shares}
 rowKey="share_id"
 loading={loading}
 pagination={{
 pageSize: 20,
 showSizeChanger: true,
 showQuickJumper: true,
 showTotal: (total) => `共 ${total} 条分享`
 }}
 scroll={{ x: 1200 }}
 />
 </TabPane>
 </Tabs>
 )}
 </Card>
 <Modal
 title="新建分享"
 open={createModalVisible}
 onCancel={() => setCreateModalVisible(false)}
 footer={null}
 width={600}
 >
 <Form
 form={form}
 layout="vertical"
 onFinish={handleCreateSubmit}
 initialValues={{
 share_type: 'user',
 permission: 'read'
 }}
 >
 <Form.Item
 name="share_type"
 label="分享类型"
 rules={[{ required: true, message: '请选择分享类型' }]}
 >
 <RadioGroup>
 <Space direction="vertical">
 <Radio value="user">
 <Space>
 <UserOutlined />
 <span>用户分享 - 分享给指定用户</span>
 </Space>
 </Radio>
 <Radio value="team">
 <Space>
 <TeamOutlined />
 <span>团队分享 - 分享给指定团队</span>
 </Space>
 </Radio>
 <Radio value="public">
 <Space>
 <GlobalOutlined />
 <span>公开分享 - 任何人均可访问</span>
 </Space>
 </Radio>
 </Space>
 </RadioGroup>
 </Form.Item>
 <Form.Item
 name="target_id"
 label="目标ID"
 extra="对于用户分享，填写用户ID；对于团队分享，填写团队ID；公开分享可以留空"
 >
 <Input placeholder="请输入目标ID（可选）" />
 </Form.Item>
 <Form.Item
 name="permission"
 label="访问权限"
 rules={[{ required: true, message: '请选择访问权限' }]}
 >
 <Select>
 <Option value="read">
 <Space>
 <EyeOutlined />
 <span>只读 - 仅可查看文档内容</span>
 </Space>
 </Option>
 <Option value="write">
 <Space>
 <EditOutlined />
 <span>可编辑 - 可查看和编辑文档</span>
 </Space>
 </Option>
 <Option value="admin">
 <Space>
 <LockOutlined />
 <span>管理 - 完全控制，包括分享和权限管理</span>
 </Space>
 </Option>
 </Select>
 </Form.Item>
 <Form.Item
 name="expires_at"
 label="过期时间"
 extra="设置分享链接的过期时间，留空表示永不过期"
 >
 <DatePicker
 showTime
 placeholder="选择过期时间"
 style={{ width: '100%' }}
 disabledDate={(current) => current && current < dayjs().startOf('day')}
 />
 </Form.Item>
 <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
 <Space>
 <Button onClick={() => setCreateModalVisible(false)}>取消</Button>
 <Button type="primary" htmlType="submit">创建分享</Button>
 </Space>
 </Form.Item>
 </Form>
 </Modal>
 </div>
 );
};
export default SharePage;
