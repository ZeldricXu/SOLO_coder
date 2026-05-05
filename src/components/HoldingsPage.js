import React, { useState } from 'react';
import {
  Table, Button, Modal, Form, Input, InputNumber, Select, DatePicker,
  Popconfirm, message, Space, Typography, Tag, Card, Tooltip
} from 'antd';
import { 
  PlusOutlined, EditOutlined, DeleteOutlined, ShopOutlined,
  QuestionCircleOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';

const { Title, Text } = Typography;
const { Option } = Select;

const sectorOptions = [
  '白酒', '银行', '保险', '医药', '科技', '消费', '新能源', '半导体', '房地产', '军工', '其他'
];

const HoldingsPage = ({ holdings, onHoldingsChange }) => {
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingHolding, setEditingHolding] = useState(null);
  const [form] = Form.useForm();

  const showAddModal = () => {
    setEditingHolding(null);
    form.resetFields();
    setIsModalOpen(true);
  };

  const showEditModal = (record) => {
    setEditingHolding(record);
    form.setFieldsValue({
      ...record,
      buy_date: record.buy_date ? dayjs(record.buy_date) : null
    });
    setIsModalOpen(true);
  };

  const handleCancel = () => {
    setIsModalOpen(false);
    setEditingHolding(null);
    form.resetFields();
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      
      const formattedValues = {
        ...values,
        buy_date: values.buy_date ? values.buy_date.format('YYYY-MM-DD') : dayjs().format('YYYY-MM-DD')
      };

      if (editingHolding) {
        await window.electronAPI.updateHolding(editingHolding.holding_id, formattedValues);
        message.success('持仓更新成功');
      } else {
        await window.electronAPI.addHolding({
          ...formattedValues,
          current_price: formattedValues.avg_cost
        });
        message.success('持仓添加成功');
      }

      handleCancel();
      onHoldingsChange();
    } catch (error) {
      message.error('操作失败: ' + error.message);
    }
  };

  const handleDelete = async (holdingId) => {
    try {
      await window.electronAPI.deleteHolding(holdingId);
      message.success('持仓删除成功');
      onHoldingsChange();
    } catch (error) {
      message.error('删除失败: ' + error.message);
    }
  };

  const columns = [
    {
      title: '股票代码',
      dataIndex: 'stock_code',
      key: 'stock_code',
      width: 100,
      fixed: 'left'
    },
    {
      title: '股票名称',
      dataIndex: 'stock_name',
      key: 'stock_name',
      width: 100
    },
    {
      title: '持仓数量',
      dataIndex: 'shares',
      key: 'shares',
      width: 100,
      render: (text) => <span>{text?.toLocaleString() || 0} 股</span>
    },
    {
      title: (
        <span>
          买入成本价
          <Tooltip title="不包含交易费用的买入成本">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'avg_cost',
      key: 'avg_cost',
      width: 110,
      render: (text) => <span>¥{text?.toFixed(2) || 0}</span>
    },
    {
      title: (
        <span>
          实际成本价
          <Tooltip title="包含所有交易费用（佣金、印花税、过户费）">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'avg_cost_with_commission',
      key: 'avg_cost_with_commission',
      width: 110,
      render: (text, record) => {
        if (text > 0) {
          return <span style={{ color: '#fa8c16' }}>¥{text?.toFixed(2) || 0}</span>;
        }
        return <Text type="secondary">--</Text>;
      }
    },
    {
      title: '现价',
      dataIndex: 'current_price',
      key: 'current_price',
      width: 100,
      render: (text, record) => {
        const changeRate = record.change_rate || 0;
        const isPositive = changeRate > 0;
        return (
          <span className={isPositive ? 'profit-positive' : changeRate < 0 ? 'profit-negative' : ''}>
            ¥{text?.toFixed(2) || '--'}
          </span>
        );
      }
    },
    {
      title: (
        <span>
          市值
          <Tooltip title="当前股价 × 持仓数量">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'market_value',
      key: 'market_value',
      width: 130,
      render: (text) => <span>¥{text?.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) || '--'}</span>
    },
    {
      title: (
        <span>
          实际盈亏
          <Tooltip title="基于实际成本价计算（扣除所有交易费用）">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'real_profit',
      key: 'real_profit',
      width: 130,
      render: (text, record) => {
        if (record.real_profit === undefined || record.real_profit === null) {
          if (record.profit === undefined || record.profit === null) return <span>--</span>;
          const isPositive = record.profit > 0;
          return (
            <span className={isPositive ? 'profit-positive' : record.profit < 0 ? 'profit-negative' : ''}>
              {isPositive ? '+' : ''}¥{record.profit.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
            </span>
          );
        }
        const isPositive = record.real_profit > 0;
        return (
          <span className={isPositive ? 'profit-positive' : record.real_profit < 0 ? 'profit-negative' : ''}>
            {isPositive ? '+' : ''}¥{record.real_profit.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </span>
        );
      }
    },
    {
      title: (
        <span>
          实际盈亏比例
          <Tooltip title="基于实际成本价计算">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'real_profit_rate',
      key: 'real_profit_rate',
      width: 110,
      render: (text, record) => {
        if (record.real_profit_rate === undefined || record.real_profit_rate === null) {
          if (record.profit_rate === undefined || record.profit_rate === null) return <span>--</span>;
          const isPositive = record.profit_rate > 0;
          return (
            <span className={isPositive ? 'profit-positive' : record.profit_rate < 0 ? 'profit-negative' : ''}>
              {isPositive ? '+' : ''}{record.profit_rate.toFixed(2)}%
            </span>
          );
        }
        const isPositive = record.real_profit_rate > 0;
        return (
          <span className={isPositive ? 'profit-positive' : record.real_profit_rate < 0 ? 'profit-negative' : ''}>
            {isPositive ? '+' : ''}{record.real_profit_rate.toFixed(2)}%
          </span>
        );
      }
    },
    {
      title: (
        <span>
          累计佣金
          <Tooltip title="该股票所有交易产生的佣金">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'total_commission',
      key: 'total_commission',
      width: 100,
      render: (text) => (text > 0 ? <span style={{ color: '#fa8c16' }}>¥{text?.toFixed(2) || 0}</span> : <Text type="secondary">--</Text>)
    },
    {
      title: '买入日期',
      dataIndex: 'buy_date',
      key: 'buy_date',
      width: 120
    },
    {
      title: '行业',
      dataIndex: 'sector',
      key: 'sector',
      width: 100,
      render: (text) => <Tag color="blue">{text || '未分类'}</Tag>
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      fixed: 'right',
      render: (_, record) => (
        <Space>
          <Button 
            type="link" 
            icon={<EditOutlined />}
            onClick={() => showEditModal(record)}
          >
            编辑
          </Button>
          <Popconfirm
            title="确认删除"
            description={`确定要删除持仓 ${record.stock_name} 吗？`}
            onConfirm={() => handleDelete(record.holding_id)}
            okText="确定"
            cancelText="取消"
          >
            <Button type="link" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ];

  return (
    <div>
      <div className="flex-between mb-24">
        <Title level={4}>
          <ShopOutlined style={{ marginRight: 8 }} />
          持仓管理
        </Title>
        <Space>
          <Text type="secondary" style={{ fontSize: 12 }}>
            💡 提示：通过"交易记录"录入的交易会自动更新持仓的实际成本
          </Text>
          <Button type="primary" icon={<PlusOutlined />} onClick={showAddModal}>
            添加持仓
          </Button>
        </Space>
      </div>

      <Card>
        <Table
          className="holding-table"
          columns={columns}
          dataSource={holdings}
          rowKey="holding_id"
          pagination={{ 
            pageSize: 10, 
            showSizeChanger: true, 
            showTotal: (total) => `共 ${total} 条` 
          }}
          scroll={{ x: 1800 }}
        />
      </Card>

      <Modal
        title={editingHolding ? '编辑持仓' : '添加持仓'}
        open={isModalOpen}
        onOk={handleSubmit}
        onCancel={handleCancel}
        okText="确定"
        cancelText="取消"
        width={600}
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            shares: 100,
            sector: '其他'
          }}
        >
          <Form.Item
            name="stock_code"
            label="股票代码"
            rules={[{ required: true, message: '请输入股票代码' }]}
          >
            <Input placeholder="例如: 600519" />
          </Form.Item>

          <Form.Item
            name="stock_name"
            label="股票名称"
            rules={[{ required: true, message: '请输入股票名称' }]}
          >
            <Input placeholder="例如: 贵州茅台" />
          </Form.Item>

          <Form.Item
            name="shares"
            label="持仓数量 (股)"
            rules={[{ required: true, message: '请输入持仓数量' }]}
          >
            <InputNumber 
              min={1} 
              style={{ width: '100%' }} 
              placeholder="请输入持仓数量"
            />
          </Form.Item>

          <Form.Item
            name="avg_cost"
            label={
              <span>
                买入成本价 (元)
                <Tooltip title="不包含交易费用">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 10 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入成本价' }]}
          >
            <InputNumber 
              min={0.01} 
              precision={2}
              style={{ width: '100%' }} 
              placeholder="请输入成本价"
            />
          </Form.Item>

          <Form.Item
            name="buy_date"
            label="买入日期"
          >
            <DatePicker style={{ width: '100%' }} placeholder="请选择买入日期" />
          </Form.Item>

          <Form.Item
            name="sector"
            label="所属行业"
          >
            <Select placeholder="请选择所属行业">
              {sectorOptions.map(sector => (
                <Option key={sector} value={sector}>{sector}</Option>
              ))}
            </Select>
          </Form.Item>

          <Card size="small" style={{ background: '#fafafa', border: '1px dashed #d9d9d9' }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              💡 提示：如果有交易记录，建议通过"交易记录"页面录入交易，系统会自动计算实际成本（包含佣金等费用）。
            </Text>
          </Card>
        </Form>
      </Modal>
    </div>
  );
};

export default HoldingsPage;
