import React, { useState, useEffect } from 'react';
import {
  Modal,
  Form,
  Input,
  Select,
  Tabs,
  Card,
  Typography,
  Row,
  Col,
  Radio,
  message,
} from 'antd';
import {
  LineChartOutlined,
  BarChartOutlined,
  PieChartOutlined,
  TableOutlined,
  FundOutlined,
  AppstoreOutlined,
} from '@ant-design/icons';
import type { WidgetType } from '@/types';
import { WidgetType as WidgetTypeEnum } from '@/types';
import type { Metric } from '@/types';
import { metricService } from '@/services/metric';

const { Title } = Typography;
const { Option } = Select;
const { TabPane } = Tabs;
const { TextArea } = Input;

interface AddWidgetModalProps {
  open: boolean;
  onCancel: () => void;
  onOk: (data: { type: WidgetType; title: string; metricId: string | null; config: Record<string, unknown> }) => void;
  loading?: boolean;
}

const widgetTypeList: { type: WidgetType; name: string; icon: React.ReactNode; desc: string }[] = [
  { type: WidgetTypeEnum.LINE_CHART, name: '折线图', icon: <LineChartOutlined />, desc: '展示数据趋势变化' },
  { type: WidgetTypeEnum.BAR_CHART, name: '柱状图', icon: <BarChartOutlined />, desc: '对比分类数据' },
  { type: WidgetTypeEnum.PIE_CHART, name: '饼图', icon: <PieChartOutlined />, desc: '展示占比分布' },
  { type: WidgetTypeEnum.TABLE, name: '表格', icon: <TableOutlined />, desc: '展示明细数据' },
  { type: WidgetTypeEnum.NUMBER_CARD, name: '数字卡', icon: <FundOutlined />, desc: '展示核心指标' },
  { type: WidgetTypeEnum.FUNNEL, name: '漏斗图', icon: <AppstoreOutlined />, desc: '展示转化流程' },
];

const AddWidgetModal: React.FC<AddWidgetModalProps> = ({ open, onCancel, onOk, loading }) => {
  const [form] = Form.useForm();
  const [selectedType, setSelectedType] = useState<WidgetType>(WidgetTypeEnum.LINE_CHART);
  const [metrics, setMetrics] = useState<Metric[]>([]);
  const [activeTab, setActiveTab] = useState('basic');

  useEffect(() => {
    const loadMetrics = async () => {
      try {
        const res = await metricService.list();
        setMetrics(res.data.data);
      } catch (err) {
        message.error('加载指标列表失败');
      }
    };
    if (open) {
      loadMetrics();
    }
  }, [open]);

  const handleTypeSelect = (type: WidgetType) => {
    setSelectedType(type);
  };

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      onOk({
        type: selectedType,
        title: values.title,
        metricId: values.metricId || null,
        config: {
          xField: values.xField,
          yField: values.yField,
          seriesField: values.seriesField,
          nameField: values.nameField,
          valueField: values.valueField,
          value: values.value,
          changeRate: values.changeRate,
        },
      });
      form.resetFields();
    } catch (err) {
      // validation error
    }
  };

  const renderConfigFields = () => {
    switch (selectedType) {
      case WidgetTypeEnum.LINE_CHART:
      case WidgetTypeEnum.BAR_CHART:
        return (
          <>
            <Form.Item name="xField" label="X轴字段">
              <Input placeholder="date" />
            </Form.Item>
            <Form.Item name="yField" label="Y轴字段">
              <Input placeholder="value" />
            </Form.Item>
            <Form.Item name="seriesField" label="系列字段">
              <Input placeholder="可选，用于多系列" />
            </Form.Item>
          </>
        );

      case WidgetTypeEnum.PIE_CHART:
      case WidgetTypeEnum.FUNNEL:
        return (
          <>
            <Form.Item name="nameField" label="名称字段">
              <Input placeholder="name" />
            </Form.Item>
            <Form.Item name="valueField" label="数值字段">
              <Input placeholder="value" />
            </Form.Item>
          </>
        );

      case WidgetTypeEnum.NUMBER_CARD:
        return (
          <>
            <Form.Item name="value" label="数值">
              <Input placeholder="静态数值，或通过指标获取" />
            </Form.Item>
            <Form.Item name="changeRate" label="同比变化率">
              <Input placeholder="0.1 表示 10%" />
            </Form.Item>
          </>
        );

      default:
        return null;
    }
  };

  return (
    <Modal
      title="添加组件"
      open={open}
      onOk={handleOk}
      onCancel={onCancel}
      okText="确定添加"
      cancelText="取消"
      width={720}
      destroyOnClose
      confirmLoading={loading}
    >
      <Form form={form} layout="vertical" initialValues={{ title: '' }}>
        <Tabs activeKey={activeTab} onChange={setActiveTab}>
          <TabPane tab="选择类型" key="type">
            <Title level={5} style={{ marginTop: 0 }}>
              选择组件类型
            </Title>
            <Row gutter={[12, 12]}>
              {widgetTypeList.map((item) => (
                <Col span={8} key={item.type}>
                  <Card
                    hoverable
                    onClick={() => handleTypeSelect(item.type)}
                    style={{
                      textAlign: 'center',
                      border: selectedType === item.type ? '2px solid #1890ff' : '1px solid #d9d9d9',
                      cursor: 'pointer',
                    }}
                    bodyStyle={{ padding: '20px 12px' }}
                  >
                    <div style={{ fontSize: 32, color: selectedType === item.type ? '#1890ff' : '#8c8c8c' }}>
                      {item.icon}
                    </div>
                    <div style={{ marginTop: 8, fontWeight: 500 }}>{item.name}</div>
                    <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 4 }}>{item.desc}</div>
                  </Card>
                </Col>
              ))}
            </Row>
          </TabPane>

          <TabPane tab="基础配置" key="basic">
            <Form.Item name="title" label="组件标题" rules={[{ required: true, message: '请输入标题' }]}>
              <Input placeholder="请输入组件标题" />
            </Form.Item>

            <Form.Item name="metricId" label="关联指标">
              <Select placeholder="选择一个指标（可选）" allowClear>
                {metrics.map((m) => (
                  <Option key={m.id} value={m.id}>
                    {m.name}
                  </Option>
                ))}
              </Select>
            </Form.Item>

            {renderConfigFields()}
          </TabPane>

          <TabPane tab="样式配置" key="style">
            <Form.Item name="textColor" label="文字颜色">
              <Input placeholder="#333333" />
            </Form.Item>
            <Form.Item name="fontSize" label="字体大小">
              <Radio.Group>
                <Radio value="small">小</Radio>
                <Radio value="default">中</Radio>
                <Radio value="large">大</Radio>
              </Radio.Group>
            </Form.Item>
            <Form.Item name="backgroundColor" label="背景色">
              <Input placeholder="#ffffff" />
            </Form.Item>
          </TabPane>
        </Tabs>
      </Form>
    </Modal>
  );
};

export default AddWidgetModal;
