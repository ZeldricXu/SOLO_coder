import React, { useState, useEffect } from 'react';
import { Drawer, Form, Input, Select, Tabs, Button, Space, Switch, message, Divider } from 'antd';
import { DeleteOutlined, SaveOutlined } from '@ant-design/icons';
import type { Widget, WidgetType } from '@/types';
import { WidgetType as WidgetTypeEnum } from '@/types';
import type { Metric } from '@/types';
import { metricService } from '@/services/metric';

const { Option } = Select;
const { TabPane } = Tabs;
const { TextArea } = Input;

interface WidgetConfigPanelProps {
  open: boolean;
  widget: Widget | null;
  allWidgets: Widget[];
  onClose: () => void;
  onSave: (widgetId: string, data: Partial<Widget>) => void;
  onDelete: (widgetId: string) => void;
  onLink?: (widgetId: string, targetWidgetId: string) => void;
  onUnlink?: (widgetId: string, targetWidgetId: string) => void;
}

const WidgetConfigPanel: React.FC<WidgetConfigPanelProps> = ({
  open,
  widget,
  allWidgets,
  onClose,
  onSave,
  onDelete,
  onLink,
  onUnlink,
}) => {
  const [form] = Form.useForm();
  const [metrics, setMetrics] = useState<Metric[]>([]);
  const [saving, setSaving] = useState(false);

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

  useEffect(() => {
    if (widget) {
      form.setFieldsValue({
        title: widget.title,
        metricId: widget.metricId,
        ...widget.config,
      });
    }
  }, [widget, form]);

  const handleSave = async () => {
    if (!widget) return;
    try {
      const values = await form.validateFields();
      const { title, metricId, ...config } = values;
      setSaving(true);
      onSave(widget.id, {
        title,
        metricId: metricId || null,
        config,
      });
      message.success('保存成功');
      onClose();
    } catch (err) {
      // validation error
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = () => {
    if (!widget) return;
    onDelete(widget.id);
    onClose();
  };

  const handleLinkToggle = (targetWidgetId: string, checked: boolean) => {
    if (!widget) return;
    if (checked) {
      onLink?.(widget.id, targetWidgetId);
    } else {
      onUnlink?.(widget.id, targetWidgetId);
    }
  };

  const renderConfigFields = (type: WidgetType) => {
    switch (type) {
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
            <Form.Item name="smooth" label="平滑曲线" valuePropName="checked">
              <Switch />
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
            <Form.Item name="showLabel" label="显示标签" valuePropName="checked">
              <Switch defaultChecked />
            </Form.Item>
          </>
        );

      case WidgetTypeEnum.NUMBER_CARD:
        return (
          <>
            <Form.Item name="value" label="数值">
              <Input placeholder="静态数值" />
            </Form.Item>
            <Form.Item name="changeRate" label="同比变化率">
              <Input placeholder="0.1 表示 10%" />
            </Form.Item>
            <Form.Item name="prefix" label="前缀">
              <Input placeholder="¥" />
            </Form.Item>
            <Form.Item name="suffix" label="后缀">
              <Input placeholder="元" />
            </Form.Item>
          </>
        );

      case WidgetTypeEnum.TABLE:
        return (
          <>
            <Form.Item name="pageSize" label="每页条数">
              <Input placeholder="默认 10 条" />
            </Form.Item>
            <Form.Item name="showPagination" label="显示分页" valuePropName="checked">
              <Switch defaultChecked />
            </Form.Item>
          </>
        );

      default:
        return null;
    }
  };

  if (!widget) return null;

  const linkableWidgets = allWidgets.filter((w) => w.id !== widget.id);

  return (
    <Drawer
      title="组件配置"
      placement="right"
      width={400}
      onClose={onClose}
      open={open}
      extra={
        <Space>
          <Button danger icon={<DeleteOutlined />} onClick={handleDelete}>
            删除
          </Button>
          <Button type="primary" icon={<SaveOutlined />} onClick={handleSave} loading={saving}>
            保存
          </Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical">
        <Tabs defaultActiveKey="basic">
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

            <Divider orientation="left">字段映射</Divider>
            {renderConfigFields(widget.type)}
          </TabPane>

          <TabPane tab="筛选条件" key="filters">
            <Form.Item label="自定义筛选条件">
              <TextArea rows={4} placeholder='{"status": "active"}' />
            </Form.Item>
            <p style={{ color: '#8c8c8c', fontSize: 12 }}>
              以 JSON 格式配置筛选条件，将作为查询参数传递给指标
            </p>
          </TabPane>

          <TabPane tab="样式配置" key="style">
            <Form.Item name="textColor" label="文字颜色">
              <Input placeholder="#333333" />
            </Form.Item>
            <Form.Item name="backgroundColor" label="背景色">
              <Input placeholder="#ffffff" />
            </Form.Item>
            <Form.Item name="showTitle" label="显示标题" valuePropName="checked">
              <Switch defaultChecked />
            </Form.Item>
            <Form.Item name="showBorder" label="显示边框" valuePropName="checked">
              <Switch defaultChecked />
            </Form.Item>
          </TabPane>

          <TabPane tab="组件联动" key="link">
            <p style={{ color: '#8c8c8c', marginBottom: 16 }}>
              选择与此组件联动的其他组件。当此组件数据变化时，联动组件会同步更新。
            </p>
            {linkableWidgets.length > 0 ? (
              linkableWidgets.map((w) => (
                <div
                  key={w.id}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    padding: '8px 0',
                    borderBottom: '1px solid #f0f0f0',
                  }}
                >
                  <span>{w.title}</span>
                  <Switch
                    checked={widget.linkedWidgetIds.includes(w.id)}
                    onChange={(checked) => handleLinkToggle(w.id, checked)}
                  />
                </div>
              ))
            ) : (
              <div style={{ textAlign: 'center', color: '#8c8c8c', padding: '20px 0' }}>
                暂无可联动的组件
              </div>
            )}
          </TabPane>
        </Tabs>
      </Form>
    </Drawer>
  );
};

export default WidgetConfigPanel;
