import React, { useState, useEffect, useCallback } from 'react';
import {
  Table,
  Button,
  Space,
  Typography,
  Modal,
  Form,
  Input,
  Select,
  InputNumber,
  Switch,
  message,
  Popconfirm,
  Tag,
  Tabs,
  Card,
  Row,
  Col,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  BellOutlined,
  CheckOutlined,
} from '@ant-design/icons';
import type { AlertRule, AlertRecord, AlertType, AlertChannelType } from '@/types';
import { AlertType as AlertTypeEnum, AlertChannelType as AlertChannelTypeEnum } from '@/types';
import { alertService } from '@/services/alert';
import { useAuthStore } from '@/store/auth';
import { formatDate } from '@/utils/format';

const { Title } = Typography;
const { Option } = Select;
const { TextArea } = Input;
const { TabPane } = Tabs;

const AlertPage: React.FC = () => {
  const [rules, setRules] = useState<AlertRule[]>([]);
  const [records, setRecords] = useState<AlertRecord[]>([]);
  const [rulesLoading, setRulesLoading] = useState(false);
  const [recordsLoading, setRecordsLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [editingRule, setEditingRule] = useState<AlertRule | null>(null);
  const [activeTab, setActiveTab] = useState('rules');
  const [form] = Form.useForm();
  const user = useAuthStore((state) => state.user);

  const loadRules = useCallback(async () => {
    setRulesLoading(true);
    try {
      const res = await alertService.listRules();
      setRules(res.data.data);
    } catch (err) {
      message.error('加载告警规则失败');
    } finally {
      setRulesLoading(false);
    }
  }, []);

  const loadRecords = useCallback(async () => {
    setRecordsLoading(true);
    try {
      const res = await alertService.listRecords();
      setRecords(res.data.data);
    } catch (err) {
      message.error('加载告警记录失败');
    } finally {
      setRecordsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadRules();
    loadRecords();
  }, [loadRules, loadRecords]);

  const handleCreate = () => {
    setEditingRule(null);
    form.resetFields();
    form.setFieldsValue({
      type: AlertTypeEnum.THRESHOLD,
      silenceMinutes: 30,
      escalationMinutes: 60,
      isActive: true,
      channels: [],
      condition: {},
    });
    setModalVisible(true);
  };

  const handleEdit = (rule: AlertRule) => {
    setEditingRule(rule);
    form.setFieldsValue({
      name: rule.name,
      type: rule.type,
      metricId: rule.metricId,
      silenceMinutes: rule.silenceMinutes,
      escalationMinutes: rule.escalationMinutes,
      isActive: rule.isActive,
      ...rule.condition,
    });
    setModalVisible(true);
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

  const handleToggle = async (id: string) => {
    try {
      await alertService.toggleRule(id);
      message.success('状态更新成功');
      loadRules();
    } catch (err) {
      message.error('状态更新失败');
    }
  };

  const handleAcknowledge = async (id: string) => {
    if (!user) return;
    try {
      await alertService.acknowledgeRecord(id, user.id);
      message.success('确认成功');
      loadRecords();
    } catch (err) {
      message.error('确认失败');
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      const { name, type, metricId, silenceMinutes, escalationMinutes, isActive, ...condition } = values;

      const data = {
        name,
        type,
        metricId,
        silenceMinutes,
        escalationMinutes,
        isActive,
        condition,
        channels: [],
        escalationChannels: null,
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
      // validation error
    }
  };

  const alertTypeLabelMap: Record<AlertType, string> = {
    [AlertTypeEnum.THRESHOLD]: '阈值告警',
    [AlertTypeEnum.FLUCTUATION]: '波动告警',
    [AlertTypeEnum.STREAM_BREAK]: '断流告警',
  };

  const alertTypeColorMap: Record<AlertType, string> = {
    [AlertTypeEnum.THRESHOLD]: 'red',
    [AlertTypeEnum.FLUCTUATION]: 'orange',
    [AlertTypeEnum.STREAM_BREAK]: 'purple',
  };

  const ruleColumns = [
    {
      title: '规则名称',
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: '告警类型',
      dataIndex: 'type',
      key: 'type',
      render: (type: AlertType) => (
        <Tag color={alertTypeColorMap[type]}>{alertTypeLabelMap[type]}</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'isActive',
      key: 'isActive',
      render: (isActive: boolean, record: AlertRule) => (
        <Switch
          checked={isActive}
          onChange={() => handleToggle(record.id)}
          size="small"
        />
      ),
    },
    {
      title: '静默时间',
      dataIndex: 'silenceMinutes',
      key: 'silenceMinutes',
      render: (minutes: number) => `${minutes} 分钟`,
    },
    {
      title: '最近触发',
      dataIndex: 'lastTriggeredAt',
      key: 'lastTriggeredAt',
      render: (time: string | null) => (time ? formatDate(time) : '-'),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_: unknown, record: AlertRule) => (
        <Space size="small">
          <Button type="text" size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            编辑
          </Button>
          <Popconfirm title="确定删除这个告警规则吗？" onConfirm={() => handleDelete(record.id)}>
            <Button type="text" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const recordColumns = [
    {
      title: '告警信息',
      dataIndex: 'message',
      key: 'message',
    },
    {
      title: '当前值',
      dataIndex: 'value',
      key: 'value',
      render: (value: number) => <span style={{ color: '#f5222d', fontWeight: 600 }}>{value}</span>,
    },
    {
      title: '状态',
      dataIndex: 'acknowledged',
      key: 'acknowledged',
      render: (acknowledged: boolean) => (
        <Tag color={acknowledged ? 'green' : 'red'}>
          {acknowledged ? '已确认' : '未确认'}
        </Tag>
      ),
    },
    {
      title: '通知状态',
      dataIndex: 'notified',
      key: 'notified',
      render: (notified: boolean) => (
        <Tag color={notified ? 'blue' : 'default'}>
          {notified ? '已通知' : '未通知'}
        </Tag>
      ),
    },
    {
      title: '触发时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (time: string) => formatDate(time),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_: unknown, record: AlertRecord) => (
        <Space size="small">
          {!record.acknowledged && (
            <Button
              type="text"
              size="small"
              icon={<CheckOutlined />}
              onClick={() => handleAcknowledge(record.id)}
            >
              确认
            </Button>
          )}
        </Space>
      ),
    },
  ];

  const renderConditionFields = () => {
    const type = Form.useWatch('type', form);

    switch (type) {
      case AlertTypeEnum.THRESHOLD:
        return (
          <>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item name="operator" label="比较符" rules={[{ required: true }]}>
                  <Select>
                    <Option value="gt">大于 (>)</Option>
                    <Option value="gte">大于等于 (>=)</Option>
                    <Option value="lt">小于 (<)</Option>
                    <Option value="lte">小于等于 (<=)</Option>
                    <Option value="eq">等于 (=)</Option>
                  </Select>
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="threshold" label="阈值" rules={[{ required: true }]}>
                  <InputNumber style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
          </>
        );
      case AlertTypeEnum.FLUCTUATION:
        return (
          <>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item name="compareType" label="对比类型" rules={[{ required: true }]}>
                  <Select>
                    <Option value="yoy">同比</Option>
                    <Option value="mom">环比</Option>
                  </Select>
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="threshold" label="波动阈值(%)" rules={[{ required: true }]}>
                  <InputNumber style={{ width: '100%' }} min={0} max={100} />
                </Form.Item>
              </Col>
            </Row>
            <Form.Item name="direction" label="波动方向">
              <Select>
                <Option value="up">上涨</Option>
                <Option value="down">下跌</Option>
                <Option value="both">双向</Option>
              </Select>
            </Form.Item>
          </>
        );
      case AlertTypeEnum.STREAM_BREAK:
        return (
          <Form.Item name="breakMinutes" label="断流时长(分钟)" rules={[{ required: true }]}>
            <InputNumber style={{ width: '100%' }} min={1} />
          </Form.Item>
        );
      default:
        return null;
    }
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
          告警管理
        </Title>
        {activeTab === 'rules' && (
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
            新建告警规则
          </Button>
        )}
      </div>

      <Card bodyStyle={{ padding: 0 }}>
        <Tabs activeKey={activeTab} onChange={setActiveTab}>
          <TabPane tab="告警规则" key="rules">
            <Table
              rowKey="id"
              columns={ruleColumns}
              dataSource={rules}
              loading={rulesLoading}
              pagination={false}
            />
          </TabPane>
          <TabPane tab="告警记录" key="records">
            <Table
              rowKey="id"
              columns={recordColumns}
              dataSource={records}
              loading={recordsLoading}
            />
          </TabPane>
        </Tabs>
      </Card>

      <Modal
        title={editingRule ? '编辑告警规则' : '新建告警规则'}
        open={modalVisible}
        onOk={handleSubmit}
        onCancel={() => setModalVisible(false)}
        okText="确定"
        cancelText="取消"
        width={600}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="请输入规则名称" />
          </Form.Item>

          <Form.Item name="type" label="告警类型" rules={[{ required: true, message: '请选择类型' }]}>
            <Select>
              {Object.entries(alertTypeLabelMap).map(([value, label]) => (
                <Option key={value} value={value}>
                  {label}
                </Option>
              ))}
            </Select>
          </Form.Item>

          <Form.Item name="metricId" label="关联指标" rules={[{ required: true, message: '请选择指标' }]}>
            <Select placeholder="请选择指标">
              <Option value="m_1">每日活跃用户</Option>
              <Option value="m_2">订单金额</Option>
              <Option value="m_3">访问量</Option>
            </Select>
          </Form.Item>

          <Card size="small" title="告警条件" style={{ marginBottom: 16 }}>
            {renderConditionFields()}
          </Card>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="silenceMinutes" label="静默时间(分钟)" rules={[{ required: true }]}>
                <InputNumber style={{ width: '100%' }} min={1} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="escalationMinutes" label="升级时间(分钟)" rules={[{ required: true }]}>
                <InputNumber style={{ width: '100%' }} min={1} />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item name="isActive" label="启用状态" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default AlertPage;
