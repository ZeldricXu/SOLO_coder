import React, { useState, useEffect } from 'react';
import {
  Card, Form, InputNumber, Select, Input, Button, message, Divider,
  Typography, Space, Tabs, Radio, Row, Col, Alert, Tooltip
} from 'antd';
import {
  SettingOutlined, DatabaseOutlined, MoneyCollectOutlined,
  ReloadOutlined, SaveOutlined, QuestionCircleOutlined
} from '@ant-design/icons';

const { Title, Text, Paragraph } = Typography;
const { Option } = Select;
const { TabPane } = Tabs;

const SettingsPage = () => {
  const [form] = Form.useForm();
  const [dataSourceConfig, setDataSourceConfig] = useState(null);
  const [commissionConfig, setCommissionConfig] = useState(null);
  const [refreshConfig, setRefreshConfig] = useState(null);
  const [refreshStrategyTypes, setRefreshStrategyTypes] = useState([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    loadConfigs();
  }, []);

  const loadConfigs = async () => {
    try {
      setLoading(true);
      const [dsConfig, commConfig, refConfig, strategyTypes] = await Promise.all([
        window.electronAPI.getDataSourceConfig(),
        window.electronAPI.getCommissionConfig(),
        window.electronAPI.getRefreshConfig(),
        window.electronAPI.getRefreshStrategyTypes()
      ]);
      
      setDataSourceConfig(dsConfig);
      setCommissionConfig(commConfig);
      setRefreshConfig(refConfig);
      setRefreshStrategyTypes(strategyTypes.types || []);
      
      form.setFieldsValue({
        ...dsConfig,
        ...commConfig,
        ...refConfig
      });
    } catch (error) {
      message.error('加载配置失败: ' + error.message);
    } finally {
      setLoading(false);
    }
  };

  const saveDataSourceConfig = async (values) => {
    try {
      setSaving(true);
      
      const { type, batch_size, concurrent_requests, tushare_token, sina_api_url } = values;
      
      await Promise.all([
        window.electronAPI.setConfig('data_source.type', type),
        window.electronAPI.setConfig('data_source.batch_size', batch_size),
        window.electronAPI.setConfig('data_source.concurrent_requests', concurrent_requests),
        window.electronAPI.setConfig('data_source.tushare_token', tushare_token || ''),
        window.electronAPI.setConfig('data_source.sina_api_url', sina_api_url)
      ]);
      
      message.success('数据源配置已保存');
    } catch (error) {
      message.error('保存配置失败: ' + error.message);
    } finally {
      setSaving(false);
    }
  };

  const saveCommissionConfig = async (values) => {
    try {
      setSaving(true);
      
      const { 
        buy_rate, sell_rate, min_fee,
        stamp_duty_rate, transfer_fee_rate, min_transfer_fee
      } = values;
      
      await Promise.all([
        window.electronAPI.setConfig('commission.buy_rate', buy_rate / 10000),
        window.electronAPI.setConfig('commission.sell_rate', sell_rate / 10000),
        window.electronAPI.setConfig('commission.min_fee', min_fee),
        window.electronAPI.setConfig('commission.stamp_duty_rate', stamp_duty_rate / 1000),
        window.electronAPI.setConfig('commission.transfer_fee_rate', transfer_fee_rate / 100000),
        window.electronAPI.setConfig('commission.min_transfer_fee', min_transfer_fee)
      ]);
      
      message.success('佣金配置已保存');
    } catch (error) {
      message.error('保存配置失败: ' + error.message);
    } finally {
      setSaving(false);
    }
  };

  const saveRefreshConfig = async (values) => {
    try {
      setSaving(true);
      
      const {
        default_interval_ms, high_volatility_interval_ms, low_volatility_interval_ms,
        volatility_threshold, high_volatility_ratio, stable_threshold
      } = values;
      
      await Promise.all([
        window.electronAPI.setConfig('refresh.default_interval_ms', default_interval_ms * 1000),
        window.electronAPI.setConfig('refresh.high_volatility_interval_ms', high_volatility_interval_ms * 1000),
        window.electronAPI.setConfig('refresh.low_volatility_interval_ms', low_volatility_interval_ms * 1000),
        window.electronAPI.setConfig('refresh.volatility_threshold', volatility_threshold),
        window.electronAPI.setConfig('refresh.high_volatility_ratio', high_volatility_ratio),
        window.electronAPI.setConfig('refresh.stable_threshold', stable_threshold)
      ]);
      
      message.success('刷新策略配置已保存');
    } catch (error) {
      message.error('保存配置失败: ' + error.message);
    } finally {
      setSaving(false);
    }
  };

  const handleStrategyChange = async (strategyType) => {
    try {
      const result = await window.electronAPI.setRefreshStrategy(strategyType);
      if (result.success) {
        message.success('刷新策略已切换');
      } else {
        message.error('切换策略失败: ' + result.error);
      }
    } catch (error) {
      message.error('切换策略失败: ' + error.message);
    }
  };

  const renderDataSourceConfig = () => (
    <Form
      form={form}
      layout="vertical"
      initialValues={dataSourceConfig || {}}
      onFinish={saveDataSourceConfig}
    >
      <Alert
        message="数据源配置说明"
        description={
          <div>
            <Paragraph>
              <Text strong>Mock 数据源</Text>：使用模拟数据，适合开发测试。
            </Paragraph>
            <Paragraph>
              <Text strong>新浪数据源</Text>：使用新浪财经API，免费但有限制。
            </Paragraph>
            <Paragraph>
              <Text strong>Tushare 数据源</Text>：专业数据平台，需要Token。支持更多股票。
            </Paragraph>
          </div>
        }
        type="info"
        showIcon
        style={{ marginBottom: 24 }}
      />
      
      <Row gutter={16}>
        <Col span={12}>
          <Form.Item
            name="type"
            label="数据源类型"
            rules={[{ required: true, message: '请选择数据源类型' }]}
          >
            <Select>
              <Option value="mock">Mock (模拟数据)</Option>
              <Option value="sina">新浪财经</Option>
              <Option value="tushare">Tushare</Option>
            </Select>
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item
            name="batch_size"
            label={
              <span>
                批量请求大小
                <Tooltip title="每批请求的股票数量，不同API有不同的限制">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入批量请求大小' }]}
          >
            <InputNumber min={1} max={100} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={12}>
          <Form.Item
            name="concurrent_requests"
            label={
              <span>
                并发请求数
                <Tooltip title="同时进行的批量请求数量">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入并发请求数' }]}
          >
            <InputNumber min={1} max={10} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item
            name="tushare_token"
            label={
              <span>
                Tushare Token
                <Tooltip title="仅在使用Tushare数据源时需要">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
          >
            <Input.Password placeholder="请输入Tushare Token" />
          </Form.Item>
        </Col>
      </Row>

      <Form.Item
        name="sina_api_url"
        label={
          <span>
            新浪API地址
            <Tooltip title="新浪行情API的基础URL">
              <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
            </Tooltip>
          </span>
        }
      >
        <Input placeholder="https://hq.sinajs.cn" />
      </Form.Item>

      <Form.Item>
        <Space>
          <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saving}>
            保存数据源配置
          </Button>
          <Button icon={<ReloadOutlined />} onClick={loadConfigs}>
            刷新
          </Button>
        </Space>
      </Form.Item>
    </Form>
  );

  const renderCommissionConfig = () => (
    <Form
      form={form}
      layout="vertical"
      onFinish={saveCommissionConfig}
    >
      <Alert
        message="佣金费率说明"
        description={
          <div>
            <Paragraph>
              <Text strong>佣金费率</Text>：券商收取的交易手续费，通常为万3（0.03%）。
              买入和卖出都需要支付佣金。
            </Paragraph>
            <Paragraph>
              <Text strong>最低佣金</Text>：每笔交易的最低佣金，通常为5元。
            </Paragraph>
            <Paragraph>
              <Text strong>印花税</Text>：仅卖出时收取，默认千1（0.1%）。
            </Paragraph>
            <Paragraph>
              <Text strong>过户费</Text>：证券交易过户费，默认十万分之2。
            </Paragraph>
          </div>
        }
        type="info"
        showIcon
        style={{ marginBottom: 24 }}
      />

      <Row gutter={16}>
        <Col span={12}>
          <Form.Item
            name="buy_rate_display"
            label={
              <span>
                买入佣金费率 (万分之)
                <Tooltip title="例如：万3 输入 3">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入买入佣金费率' }]}
            initialValue={commissionConfig ? commissionConfig.buy_rate * 10000 : 3}
          >
            <InputNumber min={0} max={100} step={0.1} precision={1} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item
            name="sell_rate_display"
            label={
              <span>
                卖出佣金费率 (万分之)
                <Tooltip title="例如：万3 输入 3">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入卖出佣金费率' }]}
            initialValue={commissionConfig ? commissionConfig.sell_rate * 10000 : 3}
          >
            <InputNumber min={0} max={100} step={0.1} precision={1} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={12}>
          <Form.Item
            name="min_fee"
            label={
              <span>
                最低佣金 (元)
                <Tooltip title="每笔交易的最低佣金，通常为5元">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入最低佣金' }]}
          >
            <InputNumber min={0} max={100} precision={2} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item
            name="stamp_duty_rate_display"
            label={
              <span>
                印花税率 (千分之)
                <Tooltip title="例如：千1 输入 1，仅卖出时收取">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入印花税率' }]}
            initialValue={commissionConfig ? commissionConfig.stamp_duty_rate * 1000 : 1}
          >
            <InputNumber min={0} max={10} step={0.1} precision={1} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={12}>
          <Form.Item
            name="transfer_fee_rate_display"
            label={
              <span>
                过户费率 (十万分之)
                <Tooltip title="例如：十万分之2 输入 2">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入过户费率' }]}
            initialValue={commissionConfig ? commissionConfig.transfer_fee_rate * 100000 : 2}
          >
            <InputNumber min={0} max={10} step={0.1} precision={1} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={12}>
          <Form.Item
            name="min_transfer_fee"
            label={
              <span>
                最低过户费 (元)
                <Tooltip title="每笔交易的最低过户费">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入最低过户费' }]}
          >
            <InputNumber min={0} max={10} precision={2} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
      </Row>

      <Alert
        message="费率计算示例"
        description={
          <Text type="secondary">
            买入 1000 股，价格 10 元，佣金万3，最低5元：
            成交金额 = 1000 × 10 = 10000 元
            佣金 = 10000 × 0.03% = 3 元，低于最低佣金，实际佣金 = 5 元
          </Text>
        }
        type="success"
        showIcon
        style={{ marginBottom: 24 }}
      />

      <Form.Item>
        <Space>
          <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saving}>
            保存佣金配置
          </Button>
          <Button icon={<ReloadOutlined />} onClick={loadConfigs}>
            刷新
          </Button>
        </Space>
      </Form.Item>
    </Form>
  );

  const renderRefreshConfig = () => (
    <Form
      form={form}
      layout="vertical"
      onFinish={saveRefreshConfig}
    >
      <Alert
        message="刷新策略说明"
        description={
          <div>
            <Paragraph>
              <Text strong>波动率策略</Text>：根据市场波动率动态调整刷新间隔。
              当市场波动剧烈时自动提高刷新频率，市场平稳时降低频率。
            </Paragraph>
            <Paragraph>
              <Text strong>固定间隔策略</Text>：使用固定的刷新间隔，不随市场波动变化。
            </Paragraph>
            <Paragraph>
              <Text strong>时段策略</Text>：根据交易时段（如开盘、收盘）调整刷新间隔。
            </Paragraph>
          </div>
        }
        type="info"
        showIcon
        style={{ marginBottom: 24 }}
      />

      <Form.Item
        label="刷新策略类型"
        style={{ marginBottom: 24 }}
      >
        <Radio.Group 
          onChange={(e) => handleStrategyChange(e.target.value)}
          defaultValue="volatility"
        >
          <Space direction="vertical">
            {refreshStrategyTypes.map(strategy => (
              <Radio.Button key={strategy.id} value={strategy.id}>
                <div>
                  <Text strong>{strategy.name}</Text>
                  <br />
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {strategy.description}
                  </Text>
                </div>
              </Radio.Button>
            ))}
          </Space>
        </Radio.Group>
      </Form.Item>

      <Divider>波动率策略参数</Divider>

      <Row gutter={16}>
        <Col span={8}>
          <Form.Item
            name="default_interval_display"
            label={
              <span>
                正常刷新间隔 (秒)
                <Tooltip title="正常市场情况下的刷新间隔">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入正常刷新间隔' }]}
            initialValue={refreshConfig ? refreshConfig.default_interval_ms / 1000 : 60}
          >
            <InputNumber min={10} max={600} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item
            name="high_volatility_interval_display"
            label={
              <span>
                高波动刷新间隔 (秒)
                <Tooltip title="市场波动剧烈时的刷新间隔">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入高波动刷新间隔' }]}
            initialValue={refreshConfig ? refreshConfig.high_volatility_interval_ms / 1000 : 30}
          >
            <InputNumber min={5} max={600} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item
            name="low_volatility_interval_display"
            label={
              <span>
                平稳刷新间隔 (秒)
                <Tooltip title="市场平稳时的刷新间隔">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入平稳刷新间隔' }]}
            initialValue={refreshConfig ? refreshConfig.low_volatility_interval_ms / 1000 : 120}
          >
            <InputNumber min={10} max={1200} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={8}>
          <Form.Item
            name="volatility_threshold"
            label={
              <span>
                波动率阈值 (%)
                <Tooltip title="涨跌幅超过此值视为高波动">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入波动率阈值' }]}
          >
            <InputNumber min={0.1} max={10} step={0.1} precision={1} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item
            name="high_volatility_ratio"
            label={
              <span>
                高波动股票比例阈值
                <Tooltip title="高波动股票占比超过此值时切换到高波动模式">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入高波动股票比例阈值' }]}
          >
            <InputNumber min={0.1} max={1} step={0.05} precision={2} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
        <Col span={8}>
          <Form.Item
            name="stable_threshold"
            label={
              <span>
                平稳模式阈值 (%)
                <Tooltip title="所有股票涨跌幅低于此值时切换到平稳模式">
                  <QuestionCircleOutlined style={{ marginLeft: 4, fontSize: 12 }} />
                </Tooltip>
              </span>
            }
            rules={[{ required: true, message: '请输入平稳模式阈值' }]}
          >
            <InputNumber min={0.1} max={5} step={0.1} precision={1} style={{ width: '100%' }} />
          </Form.Item>
        </Col>
      </Row>

      <Form.Item>
        <Space>
          <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={saving}>
            保存刷新策略配置
          </Button>
          <Button icon={<ReloadOutlined />} onClick={loadConfigs}>
            刷新
          </Button>
        </Space>
      </Form.Item>
    </Form>
  );

  return (
    <div>
      <div className="flex-between mb-24">
        <Title level={4}>
          <SettingOutlined style={{ marginRight: 8 }} />
          系统设置
        </Title>
      </div>

      <Card loading={loading}>
        <Tabs defaultActiveKey="dataSource">
          <TabPane
            tab={
              <span>
                <DatabaseOutlined />
                数据源配置
              </span>
            }
            key="dataSource"
          >
            {renderDataSourceConfig()}
          </TabPane>
          <TabPane
            tab={
              <span>
                <MoneyCollectOutlined />
                佣金费率配置
              </span>
            }
            key="commission"
          >
            {renderCommissionConfig()}
          </TabPane>
          <TabPane
            tab={
              <span>
                <ReloadOutlined />
                刷新策略配置
              </span>
            }
            key="refresh"
          >
            {renderRefreshConfig()}
          </TabPane>
        </Tabs>
      </Card>
    </div>
  );
};

export default SettingsPage;
