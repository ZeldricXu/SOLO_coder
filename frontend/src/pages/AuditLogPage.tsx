import React, { useState, useEffect } from 'react';
import {
  Card,
  Button,
  Table,
  Space,
  Select,
  DatePicker,
  Input,
  Tag,
  Typography,
  Modal,
  Descriptions,
  Empty,
} from 'antd';
import {
  ReloadOutlined,
  SearchOutlined,
  FileTextOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs, { Dayjs } from 'dayjs';
import { auditService } from '@/services/audit';
import type { AuditLog } from '@/types';

const { RangePicker } = DatePicker;
const { Option } = Select;
const { Text, Title } = Typography;

const ACTION_COLORS: Record<string, string> = {
  CREATE: 'green',
  UPDATE: 'blue',
  DELETE: 'red',
  LOGIN: 'purple',
  LOGOUT: 'default',
  EXPORT: 'orange',
  IMPORT: 'cyan',
};

const RESOURCE_COLORS: Record<string, string> = {
  DASHBOARD: 'blue',
  DATASOURCE: 'green',
  METRIC: 'orange',
  ALERT: 'red',
  USER: 'purple',
  TENANT: 'cyan',
};

const AuditLogPage: React.FC = () => {
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [actions, setActions] = useState<string[]>([]);
  const [resources, setResources] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [detailVisible, setDetailVisible] = useState(false);
  const [viewingLog, setViewingLog] = useState<AuditLog | null>(null);
  const [filters, setFilters] = useState({
    userId: '',
    action: '',
    resource: '',
    timeRange: null as [Dayjs, Dayjs] | null,
  });

  const loadLogs = async () => {
    setLoading(true);
    try {
      const params: Record<string, unknown> = {
        page,
        limit: pageSize,
      };
      if (filters.userId) params.userId = filters.userId;
      if (filters.action) params.action = filters.action;
      if (filters.resource) params.resource = filters.resource;
      if (filters.timeRange) {
        params.startTime = filters.timeRange[0].toISOString();
        params.endTime = filters.timeRange[1].toISOString();
      }
      const res = await auditService.list(params);
      setLogs(res.data.data.data);
      setTotal(res.data.data.total);
    } catch (err) {
      message.error('加载审计日志失败');
    } finally {
      setLoading(false);
    }
  };

  const loadActions = async () => {
    try {
      const res = await auditService.getActions();
      setActions(res.data.data);
    } catch (err) {
      console.error('加载操作类型失败', err);
    }
  };

  const loadResources = async () => {
    try {
      const res = await auditService.getResources();
      setResources(res.data.data);
    } catch (err) {
      console.error('加载资源类型失败', err);
    }
  };

  useEffect(() => {
    loadLogs();
    loadActions();
    loadResources();
  }, [page, pageSize]);

  const handleViewDetail = (record: AuditLog) => {
    setViewingLog(record);
    setDetailVisible(true);
  };

  const handleSearch = () => {
    setPage(1);
    loadLogs();
  };

  const handleReset = () => {
    setFilters({
      userId: '',
      action: '',
      resource: '',
      timeRange: null,
    });
    setPage(1);
    loadLogs();
  };

  const columns: ColumnsType<AuditLog> = [
    {
      title: '操作时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (date) => dayjs(date).format('YYYY-MM-DD HH:mm:ss'),
    },
    {
      title: '操作人',
      dataIndex: 'userEmail',
      key: 'userEmail',
      width: 180,
      render: (text, record) => (
        <Space>
          <Text strong>{text}</Text>
          <Tag color="default" style={{ fontSize: 11 }}>
            {record.userId.substring(0, 8)}
          </Tag>
        </Space>
      ),
    },
    {
      title: '操作',
      dataIndex: 'action',
      key: 'action',
      width: 120,
      render: (action) => {
        const color = ACTION_COLORS[action] || 'default';
        return <Tag color={color}>{action}</Tag>;
      },
    },
    {
      title: '资源类型',
      dataIndex: 'resource',
      key: 'resource',
      width: 120,
      render: (resource) => {
        const color = RESOURCE_COLORS[resource] || 'default';
        return <Tag color={color}>{resource}</Tag>;
      },
    },
    {
      title: '资源ID',
      dataIndex: 'resourceId',
      key: 'resourceId',
      width: 120,
      render: (id) => id || '-',
    },
    {
      title: 'IP地址',
      dataIndex: 'ip',
      key: 'ip',
      width: 130,
    },
    {
      title: '租户',
      dataIndex: 'tenantId',
      key: 'tenantId',
      width: 100,
      render: (id) => id || '-',
    },
    {
      title: '操作',
      key: 'actions',
      width: 80,
      render: (_, record) => (
        <Button
          type="link"
          icon={<EyeOutlined />}
          size="small"
          onClick={() => handleViewDetail(record)}
        >
          详情
        </Button>
      ),
    },
  ];

  return (
    <div style={{ padding: 24 }}>
      <Card
        title={
          <Space>
            <FileTextOutlined />
            审计日志
          </Space>
        }
        extra={
          <Button icon={<ReloadOutlined />} onClick={loadLogs}>
            刷新
          </Button>
        }
      >
        <Card size="small" style={{ marginBottom: 16, background: '#fafafa' }}>
          <Space wrap>
            <Input.Search
              placeholder="搜索用户邮箱"
              style={{ width: 200 }}
              allowClear
              value={filters.userId}
              onChange={(e) => setFilters({ ...filters, userId: e.target.value })}
              onSearch={handleSearch}
            />
            <Select
              placeholder="操作类型"
              style={{ width: 150 }}
              allowClear
              value={filters.action || undefined}
              onChange={(value) => setFilters({ ...filters, action: value || '' })}
            >
              {actions.map((action) => (
                <Option key={action} value={action}>
                  <Tag color={ACTION_COLORS[action] || 'default'}>{action}</Tag>
                </Option>
              ))}
            </Select>
            <Select
              placeholder="资源类型"
              style={{ width: 150 }}
              allowClear
              value={filters.resource || undefined}
              onChange={(value) => setFilters({ ...filters, resource: value || '' })}
            >
              {resources.map((resource) => (
                <Option key={resource} value={resource}>
                  <Tag color={RESOURCE_COLORS[resource] || 'default'}>{resource}</Tag>
                </Option>
              ))}
            </Select>
            <RangePicker
              showTime
              value={filters.timeRange}
              onChange={(dates) =>
                setFilters({ ...filters, timeRange: dates as [Dayjs, Dayjs] | null })
              }
            />
            <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
              查询
            </Button>
            <Button onClick={handleReset}>重置</Button>
          </Space>
        </Card>

        <Table
          columns={columns}
          dataSource={logs}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1000 }}
          pagination={{
            current: page,
            pageSize,
            total,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (t) => `共 ${t} 条记录`,
            onChange: (p, ps) => {
              setPage(p);
              setPageSize(ps);
            },
          }}
        />
      </Card>

      <Modal
        title={
          <Space>
            <FileTextOutlined />
            审计日志详情
          </Space>
        }
        open={detailVisible}
        onCancel={() => setDetailVisible(false)}
        footer={null}
        width={700}
      >
        {viewingLog && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="操作时间">
              {dayjs(viewingLog.createdAt).format('YYYY-MM-DD HH:mm:ss')}
            </Descriptions.Item>
            <Descriptions.Item label="操作人">
              {viewingLog.userEmail} (ID: {viewingLog.userId})
            </Descriptions.Item>
            <Descriptions.Item label="操作">
              <Tag color={ACTION_COLORS[viewingLog.action] || 'default'}>
                {viewingLog.action}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="资源类型">
              <Tag color={RESOURCE_COLORS[viewingLog.resource] || 'default'}>
                {viewingLog.resource}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="资源ID">
              {viewingLog.resourceId || '-'}
            </Descriptions.Item>
            <Descriptions.Item label="IP地址">
              {viewingLog.ip}
            </Descriptions.Item>
            <Descriptions.Item label="租户ID">
              {viewingLog.tenantId || '-'}
            </Descriptions.Item>
            <Descriptions.Item label="详情">
              {viewingLog.details ? (
                <pre
                  style={{
                    background: '#f5f5f5',
                    padding: 12,
                    borderRadius: 4,
                    margin: 0,
                    fontSize: 12,
                    maxHeight: 300,
                    overflow: 'auto',
                  }}
                >
                  {JSON.stringify(viewingLog.details, null, 2)}
                </pre>
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无详情" />
              )}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </div>
  );
};

export default AuditLogPage;
