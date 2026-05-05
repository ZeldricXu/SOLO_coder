import React, { useState, useCallback } from 'react';
import { Button, Form, Card, message, Space } from 'antd';
import { CheckOutlined } from '@ant-design/icons';
import FormRenderer from './FormRenderer';
import { validationEngine } from '../validationEngine';

const SingleStepFormRenderer = ({
  formConfig,
  initialData = {},
  onSubmit,
  onCancel,
  submitButtonText = '提交',
  successMessage = '提交成功',
  enableVirtualization = true,
  virtualizationThreshold = 30,
}) => {
  const [formData, setFormData] = useState(initialData);
  const [errors, setErrors] = useState({});
  const [loading, setLoading] = useState(false);

  const handleFieldChange = useCallback((componentId, value) => {
    setFormData((prev) => ({
      ...prev,
      [componentId]: value,
    }));
    setErrors((prev) => ({
      ...prev,
      [componentId]: null,
    }));
  }, []);

  const validateForm = useCallback(() => {
    const components = formConfig.components || [];
    const result = validationEngine.validateForm(formData, components);
    setErrors(result.fieldResults);
    return result;
  }, [formConfig, formData]);

  const handleSubmit = useCallback(async () => {
    const validationResult = validateForm();
    if (!validationResult.isValid) {
      message.warning('请完善表单中的必填项');
      return;
    }

    setLoading(true);
    try {
      if (onSubmit) {
        await onSubmit(formData);
      }
      message.success(successMessage);
    } catch (error) {
      message.error(error.message || '提交失败');
    } finally {
      setLoading(false);
    }
  }, [validateForm, onSubmit, formData, successMessage]);

  return (
    <Card>
      <Form layout="vertical">
        <FormRenderer
          formConfig={formConfig}
          formData={formData}
          onFieldChange={handleFieldChange}
          errors={errors}
          enableVirtualization={enableVirtualization}
          virtualizationThreshold={virtualizationThreshold}
        />
      </Form>

      <div style={{ marginTop: 32, display: 'flex', justifyContent: 'flex-end' }}>
        <Space>
          {onCancel && (
            <Button onClick={onCancel}>
              取消
            </Button>
          )}
          <Button
            type="primary"
            icon={<CheckOutlined />}
            onClick={handleSubmit}
            loading={loading}
          >
            {submitButtonText}
          </Button>
        </Space>
      </div>
    </Card>
  );
};

export default SingleStepFormRenderer;
