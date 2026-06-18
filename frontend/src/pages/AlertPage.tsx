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
  Tabs,
  Switch,
  Typography,
  Row,
  Col,
  Divider,
  InputNumber,
  List,
  Empty,
  Badge,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  BellOutlined,
  BellFilled,
  SettingOutlined,
  HistoryOutlined,
  MailOutlined,
  WechatOutlined,
  PhoneOutlined,
  CheckCircleOutlined,
  ExclamationCircleOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { alertService } from '@/services/alert';
import { metricService } from '@/services/metric';
import { tenantService } from '@/services/tenant';
import { useAuthStore } from '@/store/auth';
import type {
  AlertRule,
  AlertRecord,
  AlertType,
  AlertChannelType,
  AlertChannel,
  Metric,
  BusinessLine,
} from '@/types';

const { TextArea } = Input;
const { Option } = Select;
const { Tabs: AntTabs, TabPane } = Tabs;
const { Text, Title } = Typography;

const ALERT_TYPE_CONFIG: Record<AlertType, { label: string; icon: React.ReactNode; color: string }> = {
  THRESHOLD: { label: '阈值告警', icon: <ExclamationCircleOutlined />, color: 'red' },
  FLUCTUATION: { label: '波动告警', icon: <WarningOutlined />, color: 'orange' },
  STREAM_BREAK: { label: '断流告警', icon: <BellFilled />, color: 'purple' },
};

const CHANNEL_TYPE_CONFIG: Record<AlertChannelType, { label: string; icon: React.ReactNode; color: string }> = {
  EMAIL: { label: '邮件', icon: <MailOutlined />, color: 'blue' },
  WECOM: { label: '企业微信', icon: <WechatOutlined />, color: 'green' },
  DINGTALK: { label: '钉钉', icon: <PhoneOutlined />, color: 'geekblue' },
};

const AlertPage: React.FC = () => {
  const { user } = useAuthStore();
  const [alertRules, setAlertRules] = useState<AlertRule[]>([]);
  const [alertRecords, setAlertRecords] = useState<AlertRecord[]>([]);
  const [metrics, setMetrics] = useState<Metric[]>([]);
  const [businessLines, setBusinessLines] = useState<BusinessLine[]>([]);
  const [loading, setLoading] = useState(false);
  const [recordsLoading, setRecordsLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [historyVisible, setHistoryVisible] = useState(false);
  const [editingRule, setEditingRule] = useState<AlertRule | null>(null);
  const [viewingRule, setViewingRule] = useState<AlertRule | null>(null);
  const [historyData, setHistoryData] = useState<AlertRecord[]>([]);
  const [activeTab, setActiveTab] = useState('rules');
  const [channelForm] = Form.useForm();
  const [ruleForm] = Form.useForm();
  const [channels, setChannels] = useState<AlertChannel[]>([]);
  const [escalationChannels, setEscalationChannels] = useState<AlertChannel[]>([]);

  const loadRules = async (businessLineId?: string) => {
    setLoading(true);
    try {
      const res = await alertService.listRules(undefined, businessLineId);
      setAlertRules(res.data.data);
    } catch (err) {
      message.error('加载告警规则失败');
    } finally {
      setLoading(false);
    }
  };

  const loadRecords = async () => {
    setRecordsLoading(true);
    try {
      const res = await alertService.listRecords();
      setAlertRecords(res.data.data);
    } catch (err) {
      message.error('加载告警记录失败');
    } finally {
      setRecordsLoading(false);
    }
  };

  const loadMetrics = async () => {
    try {
      const res = await metricService.list();
      setMetrics(res.data.data);
    } catch (err) {
      console.error('加载指标失败', err);
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
    loadRules();
    loadRecords();
    loadMetrics();
    loadBusinessLines();
  }, []);

  const handleCreate = () => {
    setEditingRule(null);
    ruleForm.resetFields();
    setChannels([]);
    setEscalationChannels([]);
    setModalVisible(true);
  };

  const handleEdit = (record: AlertRule) => {
    setEditingRule(record);
    ruleForm.setFieldsValue({
      ...record,
      condition: JSON.stringify(record.condition, null, 2),
    });
    setChannels(record.channels || []);
    setEscalationChannels(record.escalationChannels || []);
    setModalVisible(true);
  };

  const handleViewHistory = async (record: AlertRule) => {
    setViewingRule(record);
    setHistoryVisible(true);
    try {
      const res = await alertService.getHistory(record.id);
      setHistoryData(res.data.data);
    } catch (err) {
      message.error('加载历史记录失败');
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await alertService.deleteRule(id);
      message.success('删除成功');
      loadRules();
    } catch (err) {
      message.error('删除失败');
    }
  };

  const handleToggle = async (record: AlertRule) => {
    try {
      await alertService.toggleRule(record.id);
      message.success(record.isActive ? '已禁用' : '已启用');
      loadRules();
    } catch (err) {
      message.error('操作失败');
    }
  };

  const handleAcknowledge = async (record: AlertRecord) => {
    if (!user) return;
    try {
      await alertService.acknowledgeRecord(record.id, user.id);
      message.success('已确认');
      loadRecords();
      if (viewingRule) {
        const res = await alertService.getHistory(viewingRule.id);
        setHistoryData(res.data.data);
      }
    } catch (err) {
      message.error('操作失败');
    }
  };

  const handleAddChannel = () => {
    channelForm.validateFields().then((values) => {
      const newChannel: AlertChannel = {
        type: values.type,
        target: values.target,
      };
      if (values.isEscalation) {
        setEscalationChannels([...escalationChannels, newChannel]);
      } else {
        setChannels([...channels, newChannel]);
      }
      channelForm.resetFields();
    });
  };

  const handleRemoveChannel = (index: number, isEscalation: boolean) => {
    if (isEscalation) {
      setEscalationChannels(escalationChannels.filter((_, i) => i !== index));
    } else {
      setChannels(channels.filter((_, i) => i !== index));
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await ruleForm.validateFields();
      const data = {
        ...values,
        condition: values.condition ? JSON.parse(values.condition) : {},
        channels,
        escalationChannels: escalationChannels.length > 0 ? escalationChannels : null,
      };

      if (editingRule) {
        await alertService.updateRule(editingRule.id, data);
        message.success('更新成功');
      } else {
        await alertService.createRule(data);
        message.success('创建成功');
      }
      setModalVisible(false);
      loadRules();
    } catch (err) {
      console.error(err);
    }
  };

  const selectedAlertType = Form.useWatch('type', ruleForm);

  const ruleColumns: ColumnsType<AlertRule> = [
    {
      title: '规则名称',
      dataIndex: 'name',
      key: 'name',
      render: (text, record) => (
        <Space>
          {ALERT_TYPE_CONFIG[record.type].icon}
          {text}
        </Space>
      ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      render: (type: AlertType) => {
        const config = ALERT_TYPE_CONFIG[type];
        return <Tag color={config.color}>{config.label}</Tag>;
      },
    },
    {
      title: '关联指标',
      dataIndex: 'metricId',
      key: 'metricId',
      render: (id) => {
        const metric = metrics.find((m) => m.id === id);
        return metric?.name || '-';
      },
    },
    {
      title: '通知渠道',
      dataIndex: 'channels',
      key: 'channels',
      render: (chs: AlertChannel[]) => (
        <Space wrap>
          {chs.map((ch, idx) => {
            const config = CHANNEL_TYPE_CONFIG[ch.type];
            return (
              <Tag key={idx} color={config.color}>
                {config.icon} {config.label}
              </Tag>
            );
          })}
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'isActive',
      key: 'isActive',
      render: (isActive) => (
        <Badge
          status={isActive ? 'success' : 'default'}
          text={isActive ? '运行中' : '已禁用'}
        />
      ),
    },
    {
      title: '最近触发',
      dataIndex: 'lastTriggeredAt',
      key: 'lastTriggeredAt',
      render: (date) => (date ? dayjs(date).format('YYYY-MM-DD HH:mm') : '-'),
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
            icon={<HistoryOutlined />}
            onClick={() => handleViewHistory(record)}
          >
            历史
          </Button>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            编辑
          </Button>
          <Button type="link" onClick={() => handleToggle(record)}>
            {record.isActive ? '禁用' : '启用'}
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

  const recordColumns: ColumnsType<AlertRecord> = [
    {
      title: '告警时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (date) => dayjs(date).format('YYYY-MM-DD HH:mm:ss'),
    },
    {
      title: '规则',
      dataIndex: 'ruleId',
      key: 'ruleId',
      render: (id) => {
        const rule = alertRules.find((r) => r.id === id);
        return rule?.name || '-';
      },
    },
    {
      title: '告警值',
      dataIndex: 'value',
      key: 'value',
      render: (value) => <Text type="danger" strong>{value}</Text>,
    },
    {
      title: '告警信息',
      dataIndex: 'message',
      key: 'message',
    },
    {
      title: '状态',
      key: 'status',
      render: (_, record) => (
        <Space>
          {record.acknowledged ? (
            <Tag color="green">
              <CheckCircleOutlined /> 已确认
            </Tag>
          ) : (
            <Tag color="red">
              <ExclamationCircleOutlined /> 待处理
            </Tag>
          )}
          {record.notified ? (
            <Tag color="blue">已通知</Tag>
          ) : (
            <Tag color="orange">未通知</Tag>
          )}
        </Space>
      ),
    },
    {
      title: '确认人',
      dataIndex: 'acknowledgedBy',
      key: 'acknowledgedBy',
      render: (by) => by || '-',
    },
    {
      title: '操作',
      key: 'actions',
      render: (_, record) =>
        !record.acknowledged ? (
          <Button type="link" onClick={() => handleAcknowledge(record)}>
            确认
          </Button>
        ) : null,
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Card
        title={
          <Space>
            <BellOutlined />
            告警管理
          </Space>
        }
        extra={
          <Space>
            <Button icon={<SettingOutlined />}>通知渠道配置</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
              新建规则
            </Button>
          </Space>
        }
      >
        <AntTabs activeKey={activeTab} onChange={setActiveTab}>
          <TabPane tab="告警规则" key="rules">
            <div style={{ marginBottom: 16 }}>
              <Space>
                <Select
                  placeholder="选择业务线"
                  style={{ width: 200 }}
                  allowClear
                  onChange={(value) => loadRules(value)}
                >
                  {businessLines.map((bl) => (
                    <Option key={bl.id} value={bl.id}>
                      {bl.name}
                    </Option>
                  ))}
                </Select>
                <Select placeholder="告警类型" style={{ width: 150 }} allowClear>
                  {Object.entries(ALERT_TYPE_CONFIG).map(([type, config]) => (
                    <Option key={type} value={type}>
                      {config.label}
                    </Option>
                  ))}
                </Select>
              </Space>
            </div>
            <Table columns={ruleColumns} dataSource={alertRules} rowKey="id" loading={loading} />
          </TabPane>

          <TabPane tab="告警记录" key="records">
            <Table
              columns={recordColumns}
              dataSource={alertRecords}
              rowKey="id"
              loading={recordsLoading}
            />
          </TabPane>
        </AntTabs>
      </Card>

      <Modal
        title={editingRule ? '编辑告警规则' : '新建告警规则'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        destroyOnClose
        width={800}
      >
        <Form form={ruleForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="name"
                label="规则名称"
                rules={[{ required: true, message: '请输入规则名称' }]}
              >
                <Input placeholder="请输入规则名称" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="type"
                label="告警类型"
                rules={[{ required: true, message: '请选择告警类型' }]}
              >
                <Select placeholder="请选择告警类型">
                  {Object.entries(ALERT_TYPE_CONFIG).map(([type, config]) => (
                    <Option key={type} value={type}>
                      <Space>
                        {config.icon}
                        {config.label}
                      </Space>
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
          </Row>

          <Form.Item
            name="metricId"
            label="关联指标"
            rules={[{ required: true, message: '请选择关联指标' }]}
          >
            <Select placeholder="请选择关联指标" showSearch optionFilterProp="children">
              {metrics.map((m) => (
                <Option key={m.id} value={m.id}>
                  {m.name}
                </Option>
              ))}
            </Select>
          </Form.Item>

          {selectedAlertType === 'THRESHOLD' && (
            <Form.Item
              name="condition"
              label="阈值条件 (JSON)"
              rules={[{ required: true, message: '请输入阈值条件' }]}
            >
              <TextArea
                rows={4}
                placeholder='{"operator": ">", "threshold": 100, "duration": 5}'
              />
            </Form.Item>
          )}

          {selectedAlertType === 'FLUCTUATION' && (
            <Form.Item
              name="condition"
              label="波动条件 (JSON)"
              rules={[{ required: true, message: '请输入波动条件' }]}
            >
              <TextArea
                rows={4}
                placeholder='{"comparison": "day_over_day", "threshold": 0.3, "direction": "both"}'
              />
            </Form.Item>
          )}

          {selectedAlertType === 'STREAM_BREAK' && (
            <Form.Item
              name="condition"
              label="断流条件 (JSON)"
              rules={[{ required: true, message: '请输入断流条件' }]}
            >
              <TextArea rows={4} placeholder='{"timeoutMinutes": 30}' />
            </Form.Item>
          )}

          <Divider orientation="left">通知配置</Divider>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="silenceMinutes"
                label="静默时间(分钟)"
                initialValue={60}
                rules={[{ required: true, message: '请输入静默时间' }]}
              >
                <InputNumber style={{ width: '100%' }} min={1} max={1440} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="escalationMinutes"
                label="升级时间(分钟)"
                initialValue={120}
                rules={[{ required: true, message: '请输入升级时间' }]}
              >
                <InputNumber style={{ width: '100%' }} min={1} max={1440} />
              </Form.Item>
            </Col>
          </Row>

          <Divider orientation="left">通知渠道</Divider>

          <Card size="small" title="主要通知" style={{ marginBottom: 16 }}>
            <List
              dataSource={channels}
              renderItem={(item, index) => (
                <List.Item
                  actions={[
                    <Button type="link" danger onClick={() => handleRemoveChannel(index, false)}>
                      删除
                    </Button>,
                  ]}
                >
                  <Space>
                    {CHANNEL_TYPE_CONFIG[item.type].icon}
                    <Tag color={CHANNEL_TYPE_CONFIG[item.type].color}>
                      {CHANNEL_TYPE_CONFIG[item.type].label}
                    </Tag>
                    {item.target}
                  </Space>
                </List.Item>
              )}
              locale={{ emptyText: '暂无渠道' }}
            />
          </Card>

          <Card size="small" title="升级通知" style={{ marginBottom: 16 }}>
            <List
              dataSource={escalationChannels}
              renderItem={(item, index) => (
                <List.Item
                  actions={[
                    <Button type="link" danger onClick={() => handleRemoveChannel(index, true)}>
                      删除
                    </Button>,
                  ]}
                >
                  <Space>
                    {CHANNEL_TYPE_CONFIG[item.type].icon}
                    <Tag color={CHANNEL_TYPE_CONFIG[item.type].color}>
                      {CHANNEL_TYPE_CONFIG[item.type].label}
                    </Tag>
                    {item.target}
                  </Space>
                </List.Item>
              )}
              locale={{ emptyText: '暂无渠道' }}
            />
          </Card>

          <Card size="small" title="添加渠道">
            <Form form={channelForm} layout="horizontal">
              <Row gutter={16}>
                <Col span={8}>
                  <Form.Item
                    name="type"
                    rules={[{ required: true, message: '请选择渠道类型' }]}
                  >
                    <Select placeholder="渠道类型">
                      {Object.entries(CHANNEL_TYPE_CONFIG).map(([type, config]) => (
                        <Option key={type} value={type}>
                          <Space>
                            {config.icon}
                            {config.label}
                          </Space>
                        </Option>
                      ))}
                    </Select>
                  </Form.Item>
                </Col>
                <Col span={10}>
                  <Form.Item
                    name="target"
                    rules={[{ required: true, message: '请输入目标地址' }]}
                  >
                    <Input placeholder="邮箱地址 / Webhook地址" />
                  </Form.Item>
                </Col>
                <Col span={4}>
                  <Form.Item name="isEscalation" valuePropName="checked" initialValue={false}>
                    <Switch checkedChildren="升级" unCheckedChildren="主要" />
                  </Form.Item>
                </Col>
                <Col span={2}>
                  <Button type="primary" onClick={handleAddChannel}>
                    添加
                  </Button>
                </Col>
              </Row>
            </Form>
          </Card>

          <Form.Item
            name="isActive"
            label="是否启用"
            valuePropName="checked"
            initialValue={true}
            style={{ marginTop: 16 }}
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={
          <Space>
            <HistoryOutlined />
            {viewingRule?.name} - 告警历史
          </Space>
        }
        open={historyVisible}
        onCancel={() => setHistoryVisible(false)}
        footer={null}
        width={900}
      >
        {historyData.length > 0 ? (
          <Table
            columns={recordColumns}
            dataSource={historyData}
            rowKey="id"
            pagination={false}
            size="small"
          />
        ) : (
          <Empty description="暂无告警记录" />
        )}
      </Modal>
    </div>
  );
};

export default AlertPage;
