import React, { useState, useEffect } from 'react';
import {
  Table, Button, Modal, Form, InputNumber, Select,
  Popconfirm, message, Space, Typography, Tag, Card,
  Empty, Switch
} from 'antd';
import { 
  PlusOutlined, DeleteOutlined, BellOutlined,
  ArrowUpOutlined, ArrowDownOutlined
} from '@ant-design/icons';

const { Title, Text } = Typography;
const { Option } = Select;

const AlertsPage = () => {
  const [alerts, setAlerts] = useState([]);
  const [loading, setLoading] = useState(false);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [form] = Form.useForm();

  const fetchAlerts = async () => {
    try {
      setLoading(true);
      const data = await window.electronAPI.getAlerts();
      setAlerts(data || []);
    } catch (error) {
      message.error('加载预警数据失败: ' + error.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAlerts();
  }, []);

  const showAddModal = () => {
    form.resetFields();
    form.setFieldsValue({
      alert_type: 'price',
      condition: 'above'
    });
    setIsModalOpen(true);
  };

  const handleCancel = () => {
    setIsModalOpen(false);
    form.resetFields();
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      
      await window.electronAPI.addAlert({
        stock_code: values.stock_code,
        alert_type: values.alert_type,
        target_price: values.target_price,
        condition: values.condition
      });
      
      message.success('预警添加成功');
      handleCancel();
      fetchAlerts();
    } catch (error) {
      message.error('添加失败: ' + error.message);
    }
  };

  const handleDelete = async (alertId) => {
    try {
      await window.electronAPI.deleteAlert(alertId);
      message.success('预警删除成功');
      fetchAlerts();
    } catch (error) {
      message.error('删除失败: ' + error.message);
    }
  };

  const columns = [
    {
      title: '股票代码',
      dataIndex: 'stock_code',
      key: 'stock_code',
      width: 120
    },
    {
      title: '预警类型',
      dataIndex: 'alert_type',
      key: 'alert_type',
      width: 120,
      render: (text) => (
        <Tag color="blue">
          {text === 'price' ? '价格预警' : text}
        </Tag>
      )
    },
    {
      title: '预警条件',
      dataIndex: 'condition',
      key: 'condition',
      width: 120,
      render: (text) => (
        <Tag color={text === 'above' ? 'red' : 'green'}>
          {text === 'above' ? '高于' : '低于'}
        </Tag>
      )
    },
    {
      title: '目标价格',
      dataIndex: 'target_price',
      key: 'target_price',
      width: 120,
      render: (text) => <span>¥{text?.toFixed(2) || 0}</span>
    },
    {
      title: '状态',
      dataIndex: 'is_active',
      key: 'is_active',
      width: 100,
      render: (text) => (
        <Tag color={text === 1 ? 'green' : 'default'}>
          {text === 1 ? '启用' : '禁用'}
        </Tag>
      )
    },
    {
      title: '上次触发',
      dataIndex: 'last_triggered',
      key: 'last_triggered',
      width: 180,
      render: (text) => text || <Text type="secondary">未触发</Text>
    },
    {
      title: '创建时间',
      dataIndex: 'created_at',
      key: 'created_at',
      width: 180,
      render: (text) => text?.substring(0, 19) || '--'
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_, record) => (
        <Popconfirm
          title="确认删除"
          description="确定要删除这条预警吗？"
          onConfirm={() => handleDelete(record.alert_id)}
          okText="确定"
          cancelText="取消"
        >
          <Button type="link" danger icon={<DeleteOutlined />}>
            删除
          </Button>
        </Popconfirm>
      )
    }
  ];

  return (
    <div>
      <div className="flex-between mb-24">
        <Title level={4}>
          <BellOutlined style={{ marginRight: 8 }} />
          预警通知
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={showAddModal}>
          添加预警
        </Button>
      </div>

      <Card>
        <Table
          columns={columns}
          dataSource={alerts}
          rowKey="alert_id"
          loading={loading}
          pagination={{ 
            pageSize: 10, 
            showSizeChanger: true, 
            showTotal: (total) => `共 ${total} 条预警` 
          }}
          locale={{ 
            emptyText: (
              <div className="empty-container">
                <Empty 
                  description={
                    <div>
                      <Title level={5}>暂无预警设置</Title>
                      <Text type="secondary">点击上方"添加预警"按钮设置价格提醒</Text>
                    </div>
                  }
                />
              </div>
            )
          }}
        />
      </Card>

      <Modal
        title="添加价格预警"
        open={isModalOpen}
        onOk={handleSubmit}
        onCancel={handleCancel}
        okText="确定"
        cancelText="取消"
        width={500}
      >
        <Form
          form={form}
          layout="vertical"
        >
          <Form.Item
            name="stock_code"
            label="股票代码"
            rules={[{ required: true, message: '请输入股票代码' }]}
          >
            <Select 
              showSearch 
              placeholder="请输入或选择股票代码"
              optionFilterProp="children"
              filterOption={(input, option) =>
                (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
              }
              options={[
                { value: '600519', label: '600519 贵州茅台' },
                { value: '601318', label: '601318 中国平安' },
                { value: '000858', label: '000858 五粮液' },
                { value: '600036', label: '600036 招商银行' },
                { value: '000001', label: '000001 平安银行' },
                { value: '002415', label: '002415 海康威视' },
                { value: '600276', label: '600276 恒瑞医药' },
                { value: '601166', label: '601166 兴业银行' },
                { value: '000333', label: '000333 美的集团' },
                { value: '600887', label: '600887 伊利股份' }
              ]}
            />
          </Form.Item>

          <Form.Item
            name="condition"
            label="预警条件"
            rules={[{ required: true, message: '请选择预警条件' }]}
          >
            <Select>
              <Option value="above">价格高于</Option>
              <Option value="below">价格低于</Option>
            </Select>
          </Form.Item>

          <Form.Item
            name="target_price"
            label="目标价格 (元)"
            rules={[{ required: true, message: '请输入目标价格' }]}
          >
            <InputNumber 
              min={0.01} 
              precision={2}
              style={{ width: '100%' }} 
              placeholder="请输入目标价格"
            />
          </Form.Item>

          <Form.Item
            name="alert_type"
            label="预警类型"
            rules={[{ required: true, message: '请选择预警类型' }]}
          >
            <Select disabled>
              <Option value="price">价格预警</Option>
            </Select>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default AlertsPage;
