import React, { useCallback } from 'react';
import { Card, Form, Input, Switch, InputNumber, Select, Button, Space, Divider, Typography, message } from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { COMPONENT_CONFIGS } from '../componentLibrary';

const { TextArea } = Input;
const { Title, Text } = Typography;

const PropertyPanel = ({
  selectedComponent,
  onUpdateComponent,
  onDeleteComponent,
}) => {
  const [form] = Form.useForm();

  React.useEffect(() => {
    if (selectedComponent) {
      form.setFieldsValue(selectedComponent);
    }
  }, [selectedComponent, form]);

  const handleValuesChange = useCallback((changedValues, allValues) => {
    if (selectedComponent) {
      onUpdateComponent(selectedComponent.component_id, allValues);
    }
  }, [selectedComponent, onUpdateComponent]);

  const handleDelete = useCallback(() => {
    if (selectedComponent) {
      onDeleteComponent(selectedComponent.component_id);
    }
  }, [selectedComponent, onDeleteComponent]);

  if (!selectedComponent) {
    return (
      <Card size="small">
        <Text type="secondary">请选择一个组件以编辑属性</Text>
      </Card>
    );
  }

  const componentConfig = COMPONENT_CONFIGS[selectedComponent.component_type];
  const componentLabel = componentConfig?.label || '组件';

  const renderValidationFields = () => {
    const componentType = selectedComponent.component_type;

    if (componentType === 'number_input') {
      return (
        <>
          <Form.Item label="最小值" name={['validation', 'min']}>
            <InputNumber style={{ width: '100%' }} placeholder="最小值" />
          </Form.Item>
          <Form.Item label="最大值" name={['validation', 'max']}>
            <InputNumber style={{ width: '100%' }} placeholder="最大值" />
          </Form.Item>
          <Form.Item label="步长" name={['validation', 'step']}>
            <InputNumber style={{ width: '100%' }} placeholder="步长" />
          </Form.Item>
        </>
      );
    }

    return (
      <>
        <Form.Item label="最小长度" name={['validation', 'min_length']}>
          <InputNumber style={{ width: '100%' }} placeholder="最小长度" />
        </Form.Item>
        <Form.Item label="最大长度" name={['validation', 'max_length']}>
          <InputNumber style={{ width: '100%' }} placeholder="最大长度" />
        </Form.Item>
        <Form.Item label="正则校验" name={['validation', 'pattern']}>
          <Input placeholder="正则表达式" />
        </Form.Item>
      </>
    );
  };

  return (
    <Card
      size="small"
      title={
        <Space>
          <span>属性设置</span>
          <Text type="secondary">({componentLabel})</Text>
        </Space>
      }
      extra={
        <Button
          type="text"
          danger
          icon={<MinusCircleOutlined />}
          onClick={handleDelete}
        >
          删除
        </Button>
      }
    >
      <Form
        form={form}
        layout="vertical"
        onValuesChange={handleValuesChange}
      >
        <Form.Item label="字段标签" name="label" rules={[{ required: true }]}>
          <Input placeholder="字段标签" />
        </Form.Item>

        <Form.Item label="占位符" name="placeholder">
          <Input placeholder="占位符" />
        </Form.Item>

        <Form.Item label="是否必填" name="required" valuePropName="checked">
          <Switch checkedChildren="是" unCheckedChildren="否" />
        </Form.Item>

        {selectedComponent.component_type === 'select' && (
          <>
            <Divider />
            <Title level={5}>选项配置</Title>
            <Form.List name="options">
              {(fields, { add, remove }) => (
                <>
                  {fields.map(({ key, name, ...restField }) => (
                    <Space key={key} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
                      <Form.Item
                        {...restField}
                        name={[name, 'value']}
                        rules={[{ required: true, message: '必填' }]}
                        style={{ marginBottom: 0, width: '40%' }}
                      >
                        <Input placeholder="值" />
                      </Form.Item>
                      <Form.Item
                        {...restField}
                        name={[name, 'label']}
                        rules={[{ required: true, message: '必填' }]}
                        style={{ marginBottom: 0, width: '40%' }}
                      >
                        <Input placeholder="显示文本" />
                      </Form.Item>
                      <MinusCircleOutlined onClick={() => remove(name)} />
                    </Space>
                  ))}
                  <Form.Item>
                    <Button type="dashed" onClick={() => add({ value: '', label: '' })} block icon={<PlusOutlined />}>
                      添加选项
                    </Button>
                  </Form.Item>
                </>
              )}
            </Form.List>

            <Form.Item label="允许多选" name="multiple" valuePropName="checked">
              <Switch checkedChildren="是" unCheckedChildren="否" />
            </Form.Item>
          </>
        )}

        {selectedComponent.component_type === 'radio' && (
          <>
            <Divider />
            <Title level={5}>选项配置</Title>
            <Form.List name="options">
              {(fields, { add, remove }) => (
                <>
                  {fields.map(({ key, name, ...restField }) => (
                    <Space key={key} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
                      <Form.Item
                        {...restField}
                        name={[name, 'value']}
                        rules={[{ required: true, message: '必填' }]}
                        style={{ marginBottom: 0, width: '40%' }}
                      >
                        <Input placeholder="值" />
                      </Form.Item>
                      <Form.Item
                        {...restField}
                        name={[name, 'label']}
                        rules={[{ required: true, message: '必填' }]}
                        style={{ marginBottom: 0, width: '40%' }}
                      >
                        <Input placeholder="显示文本" />
                      </Form.Item>
                      <MinusCircleOutlined onClick={() => remove(name)} />
                    </Space>
                  ))}
                  <Form.Item>
                    <Button type="dashed" onClick={() => add({ value: '', label: '' })} block icon={<PlusOutlined />}>
                      添加选项
                    </Button>
                  </Form.Item>
                </>
              )}
            </Form.List>

            <Form.Item label="按钮样式" name="button_style" valuePropName="checked">
              <Switch checkedChildren="是" unCheckedChildren="否" />
            </Form.Item>
          </>
        )}

        {selectedComponent.component_type === 'checkbox' && (
          <>
            <Divider />
            <Title level={5}>选项配置</Title>
            <Form.List name="options">
              {(fields, { add, remove }) => (
                <>
                  {fields.map(({ key, name, ...restField }) => (
                    <Space key={key} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
                      <Form.Item
                        {...restField}
                        name={[name, 'value']}
                        rules={[{ required: true, message: '必填' }]}
                        style={{ marginBottom: 0, width: '40%' }}
                      >
                        <Input placeholder="值" />
                      </Form.Item>
                      <Form.Item
                        {...restField}
                        name={[name, 'label']}
                        rules={[{ required: true, message: '必填' }]}
                        style={{ marginBottom: 0, width: '40%' }}
                      >
                        <Input placeholder="显示文本" />
                      </Form.Item>
                      <MinusCircleOutlined onClick={() => remove(name)} />
                    </Space>
                  ))}
                  <Form.Item>
                    <Button type="dashed" onClick={() => add({ value: '', label: '' })} block icon={<PlusOutlined />}>
                      添加选项
                    </Button>
                  </Form.Item>
                </>
              )}
            </Form.List>
          </>
        )}

        {selectedComponent.component_type === 'date_picker' && (
          <>
            <Divider />
            <Title level={5}>日期类型</Title>
            <Form.Item label="日期类型" name="date_type">
              <Select options={[
                { value: 'date', label: '日期' },
                { value: 'month', label: '月份' },
                { value: 'range', label: '日期范围' },
              ]} />
            </Form.Item>
          </>
        )}

        {selectedComponent.component_type === 'file_upload' && (
          <>
            <Divider />
            <Title level={5}>上传配置</Title>
            <Form.Item label="允许多文件" name="multiple" valuePropName="checked">
              <Switch checkedChildren="是" unCheckedChildren="否" />
            </Form.Item>
            <Form.Item label="最大大小(MB)" name="max_size">
              <InputNumber style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item label="允许的文件类型" name="file_types">
              <Select
                mode="tags"
                style={{ width: '100%' }}
                placeholder="输入文件类型，如: .jpg, .pdf"
                tokenSeparators={[',']}
              />
            </Form.Item>
          </>
        )}

        {selectedComponent.component_type === 'rating' && (
          <>
            <Divider />
            <Title level={5}>评分配置</Title>
            <Form.Item label="最高分" name="max_value">
              <InputNumber min={1} max={10} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item label="允许半星" name="allow_half" valuePropName="checked">
              <Switch checkedChildren="是" unCheckedChildren="否" />
            </Form.Item>
          </>
        )}

        {selectedComponent.component_type === 'switch' && (
          <>
            <Divider />
            <Title level={5}>开关配置</Title>
            <Form.Item label="开启显示文字" name="checked_text">
              <Input placeholder="开启时显示" />
            </Form.Item>
            <Form.Item label="关闭显示文字" name="unchecked_text">
              <Input placeholder="关闭时显示" />
            </Form.Item>
          </>
        )}

        {selectedComponent.component_type === 'number_input' && (
          <>
            <Divider />
            <Title level={5}>数字输入配置</Title>
            <Form.Item label="前缀" name="prefix">
              <Input placeholder="如: ¥, $" />
            </Form.Item>
            <Form.Item label="后缀" name="suffix">
              <Input placeholder="如: 元, %" />
            </Form.Item>
          </>
        )}

        <Divider />
        <Title level={5}>校验规则</Title>
        {renderValidationFields()}
      </Form>
    </Card>
  );
};

export default PropertyPanel;
