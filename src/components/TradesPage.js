import React, { useState, useEffect } from 'react';
import {
  Table, Button, Modal, Form, Input, InputNumber, Select, DatePicker,
  Popconfirm, message, Space, Typography, Tag, Card, Row, Col, Statistic,
  Empty, Spin, Tooltip
} from 'antd';
import { 
  PlusOutlined, DeleteOutlined, TransactionOutlined, 
  ArrowUpOutlined, ArrowDownOutlined, DashboardOutlined,
  DollarOutlined, QuestionCircleOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';

const { Title, Text } = Typography;
const { Option } = Select;

const TradesPage = () => {
  const [trades, setTrades] = useState([]);
  const [statistics, setStatistics] = useState(null);
  const [loading, setLoading] = useState(false);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [form] = Form.useForm();

  const fetchTrades = async () => {
    try {
      setLoading(true);
      const [allTrades, stats] = await Promise.all([
        window.electronAPI.getAllTrades(),
        window.electronAPI.getTradeStatistics()
      ]);
      setTrades(allTrades);
      setStatistics(stats);
    } catch (error) {
      message.error('加载交易记录失败: ' + error.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTrades();
  }, []);

  const showAddModal = () => {
    form.resetFields();
    form.setFieldsValue({
      trade_type: 'buy',
      commission: 0,
      stamp_duty: 0,
      transfer_fee: 0,
      trade_date: dayjs()
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
      
      const formattedValues = {
        ...values,
        trade_date: values.trade_date.format('YYYY-MM-DD')
      };

      await window.electronAPI.addTrade(formattedValues);
      message.success('交易记录添加成功，持仓成本已自动更新');
      handleCancel();
      fetchTrades();
    } catch (error) {
      message.error('添加失败: ' + error.message);
    }
  };

  const handleDelete = async (tradeId) => {
    try {
      await window.electronAPI.deleteTrade(tradeId);
      message.success('交易记录删除成功');
      fetchTrades();
    } catch (error) {
      message.error('删除失败: ' + error.message);
    }
  };

  const columns = [
    {
      title: '交易日期',
      dataIndex: 'trade_date',
      key: 'trade_date',
      width: 120,
      fixed: 'left'
    },
    {
      title: '股票代码',
      dataIndex: 'stock_code',
      key: 'stock_code',
      width: 100
    },
    {
      title: '股票名称',
      dataIndex: 'stock_name',
      key: 'stock_name',
      width: 100
    },
    {
      title: '交易类型',
      dataIndex: 'trade_type',
      key: 'trade_type',
      width: 100,
      render: (text) => (
        <Tag color={text === 'buy' ? 'red' : 'green'}>
          {text === 'buy' ? '买入' : '卖出'}
        </Tag>
      )
    },
    {
      title: '数量',
      dataIndex: 'shares',
      key: 'shares',
      width: 100,
      render: (text) => <span>{text?.toLocaleString() || 0} 股</span>
    },
    {
      title: '价格',
      dataIndex: 'price',
      key: 'price',
      width: 100,
      render: (text) => <span>¥{text?.toFixed(2) || 0}</span>
    },
    {
      title: '成交金额',
      dataIndex: 'amount',
      key: 'amount',
      width: 130,
      render: (text) => <span>¥{text?.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) || 0}</span>
    },
    {
      title: (
        <span>
          佣金
          <Tooltip title="券商交易佣金">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'commission',
      key: 'commission',
      width: 90,
      render: (text) => <span>¥{text?.toFixed(2) || 0}</span>
    },
    {
      title: (
        <span>
          印花税
          <Tooltip title="仅卖出时收取，通常为成交金额的0.1%">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'stamp_duty',
      key: 'stamp_duty',
      width: 90,
      render: (text) => (text > 0 ? <span>¥{text?.toFixed(2) || 0}</span> : <Text type="secondary">--</Text>)
    },
    {
      title: (
        <span>
          过户费
          <Tooltip title="证券交易过户费">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'transfer_fee',
      key: 'transfer_fee',
      width: 90,
      render: (text) => (text > 0 ? <span>¥{text?.toFixed(2) || 0}</span> : <Text type="secondary">--</Text>)
    },
    {
      title: (
        <span>
          总费用
          <Tooltip title="佣金+印花税+过户费">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'total_fees',
      key: 'total_fees',
      width: 100,
      render: (text) => (text > 0 ? <span style={{ color: '#fa8c16' }}>¥{text?.toFixed(2) || 0}</span> : <Text type="secondary">--</Text>)
    },
    {
      title: (
        <span>
          实现盈亏
          <Tooltip title="卖出时的实际盈亏（扣除所有费用）">
            <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
          </Tooltip>
        </span>
      ),
      dataIndex: 'realized_profit',
      key: 'realized_profit',
      width: 120,
      render: (text, record) => {
        if (record.trade_type !== 'sell' || text === undefined || text === null) {
          return <Text type="secondary">--</Text>;
        }
        const isPositive = text > 0;
        return (
          <span className={isPositive ? 'profit-positive' : 'profit-negative'}>
            {isPositive ? '+' : ''}¥{text.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </span>
        );
      }
    },
    {
      title: '备注',
      dataIndex: 'notes',
      key: 'notes',
      width: 120,
      ellipsis: true
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      fixed: 'right',
      render: (_, record) => (
        <Popconfirm
          title="确认删除"
          description="确定要删除这条交易记录吗？"
          onConfirm={() => handleDelete(record.trade_id)}
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
          <TransactionOutlined style={{ marginRight: 8 }} />
          交易记录
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={showAddModal}>
          录入交易
        </Button>
      </div>

      {statistics && (
        <div>
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={12} sm={6} md={4}>
              <Card size="small">
                <Statistic
                  title="总交易次数"
                  value={statistics.total_trades}
                  prefix={<DashboardOutlined />}
                />
              </Card>
            </Col>
            <Col xs={12} sm={6} md={4}>
              <Card size="small">
                <Statistic
                  title="买入金额"
                  value={statistics.total_buy_amount}
                  precision={2}
                  prefix="¥"
                  valueStyle={{ color: '#ff4d4f' }}
                />
              </Card>
            </Col>
            <Col xs={12} sm={6} md={4}>
              <Card size="small">
                <Statistic
                  title="卖出金额"
                  value={statistics.total_sell_amount}
                  precision={2}
                  prefix="¥"
                  valueStyle={{ color: '#52c41a' }}
                />
              </Card>
            </Col>
            <Col xs={12} sm={6} md={4}>
              <Card size="small">
                <Statistic
                  title="净现金流"
                  value={statistics.net_cash_flow}
                  precision={2}
                  prefix="¥"
                  valueStyle={{ color: statistics.net_cash_flow >= 0 ? '#52c41a' : '#ff4d4f' }}
                />
              </Card>
            </Col>
          </Row>

          <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
            <Col xs={12} sm={6} md={4}>
              <Card size="small">
                <Statistic
                  title={
                    <span>
                      佣金合计
                      <Tooltip title="所有交易的券商佣金">
                        <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 10 }} />
                      </Tooltip>
                    </span>
                  }
                  value={statistics.total_commission}
                  precision={2}
                  prefix={<DollarOutlined />}
                  valueStyle={{ color: '#fa8c16' }}
                />
              </Card>
            </Col>
            <Col xs={12} sm={6} md={4}>
              <Card size="small">
                <Statistic
                  title={
                    <span>
                      印花税合计
                      <Tooltip title="仅卖出时收取">
                        <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 10 }} />
                      </Tooltip>
                    </span>
                  }
                  value={statistics.total_stamp_duty || 0}
                  precision={2}
                  prefix="¥"
                />
              </Card>
            </Col>
            <Col xs={12} sm={6} md={4}>
              <Card size="small">
                <Statistic
                  title={
                    <span>
                      过户费合计
                      <Tooltip title="证券交易过户费">
                        <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 10 }} />
                      </Tooltip>
                    </span>
                  }
                  value={statistics.total_transfer_fee || 0}
                  precision={2}
                  prefix="¥"
                />
              </Card>
            </Col>
            <Col xs={12} sm={6} md={4}>
              <Card size="small">
                <Statistic
                  title={
                    <span>
                      实现盈亏
                      <Tooltip title="已卖出股票的实际盈亏（扣除所有费用）">
                        <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 10 }} />
                      </Tooltip>
                    </span>
                  }
                  value={statistics.total_realized_profit || 0}
                  precision={2}
                  prefix={<span style={{ color: (statistics.total_realized_profit || 0) >= 0 ? '#ff4d4f' : '#52c41a' }}>{(statistics.total_realized_profit || 0) >= 0 ? '+' : ''}¥</span>}
                  valueStyle={{ color: (statistics.total_realized_profit || 0) >= 0 ? '#ff4d4f' : '#52c41a' }}
                />
              </Card>
            </Col>
          </Row>

          {statistics.total_fees > 0 && (
            <Card size="small" style={{ marginBottom: 24 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                <DollarOutlined style={{ marginRight: 4 }} />
                费用总计: ¥{statistics.total_fees.toLocaleString()} (佣金 ¥{statistics.total_commission?.toLocaleString() || 0} + 印花税 ¥{statistics.total_stamp_duty?.toLocaleString() || 0} + 过户费 ¥{statistics.total_transfer_fee?.toLocaleString() || 0})
                <span style={{ marginLeft: 16 }}>
                  提示：实际盈亏已扣除所有交易费用，持仓成本会自动根据交易记录更新。
                </span>
              </Text>
            </Card>
          )}
        </div>
      )}

      <Card>
        <Table
          columns={columns}
          dataSource={trades}
          rowKey="trade_id"
          loading={loading}
          pagination={{ 
            pageSize: 20, 
            showSizeChanger: true, 
            showTotal: (total) => `共 ${total} 条记录` 
          }}
          scroll={{ x: 1800 }}
          locale={{ 
            emptyText: (
              <div className="empty-container">
                <Empty 
                  description={
                    <div>
                      <Title level={5}>暂无交易记录</Title>
                      <Text type="secondary">点击上方"录入交易"按钮开始记录您的交易</Text>
                    </div>
                  }
                />
              </div>
            )
          }}
        />
      </Card>

      <Modal
        title="录入交易记录"
        open={isModalOpen}
        onOk={handleSubmit}
        onCancel={handleCancel}
        okText="确定"
        cancelText="取消"
        width={700}
      >
        <Form
          form={form}
          layout="vertical"
        >
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="trade_date"
                label="交易日期"
                rules={[{ required: true, message: '请选择交易日期' }]}
              >
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="trade_type"
                label="交易类型"
                rules={[{ required: true, message: '请选择交易类型' }]}
              >
                <Select>
                  <Option value="buy">买入</Option>
                  <Option value="sell">卖出</Option>
                </Select>
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="stock_code"
                label="股票代码"
                rules={[{ required: true, message: '请输入股票代码' }]}
              >
                <Input placeholder="例如: 600519" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="stock_name"
                label="股票名称"
              >
                <Input placeholder="例如: 贵州茅台" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="shares"
                label="数量 (股)"
                rules={[{ required: true, message: '请输入交易数量' }]}
              >
                <InputNumber 
                  min={1} 
                  style={{ width: '100%' }} 
                  placeholder="请输入交易数量"
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="price"
                label="价格 (元)"
                rules={[{ required: true, message: '请输入交易价格' }]}
              >
                <InputNumber 
                  min={0.01} 
                  precision={2}
                  style={{ width: '100%' }} 
                  placeholder="请输入交易价格"
                />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                name="commission"
                label={
                  <span>
                    佣金 (元)
                    <Tooltip title="券商收取的交易佣金">
                      <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 10 }} />
                    </Tooltip>
                  </span>
                }
              >
                <InputNumber 
                  min={0} 
                  precision={2}
                  style={{ width: '100%' }} 
                  placeholder="请输入佣金"
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="stamp_duty"
                label={
                  <span>
                    印花税 (元)
                    <Tooltip title="卖出时收取，通常为成交金额的0.1%">
                      <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 10 }} />
                    </Tooltip>
                  </span>
                }
              >
                <InputNumber 
                  min={0} 
                  precision={2}
                  style={{ width: '100%' }} 
                  placeholder="印花税"
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="transfer_fee"
                label={
                  <span>
                    过户费 (元)
                    <Tooltip title="证券交易过户费">
                      <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 10 }} />
                    </Tooltip>
                  </span>
                }
              >
                <InputNumber 
                  min={0} 
                  precision={2}
                  style={{ width: '100%' }} 
                  placeholder="过户费"
                />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item
            name="notes"
            label="备注"
          >
            <Input.TextArea rows={3} placeholder="交易备注信息" />
          </Form.Item>

          <Card size="small" style={{ background: '#fafafa', border: '1px dashed #d9d9d9' }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              <TipIcon />
              提示：录入交易后，系统会自动计算持仓的实际成本（包含所有交易费用），并更新盈亏统计。
              卖出时将自动计算实现盈亏。
            </Text>
          </Card>
        </Form>
      </Modal>
    </div>
  );
};

const TipIcon = () => (
  <span style={{ marginRight: 4 }}>💡</span>
);

export default TradesPage;
