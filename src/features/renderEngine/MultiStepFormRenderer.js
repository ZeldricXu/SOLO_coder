import React, { useState, useCallback, useEffect } from 'react';
import { Steps, Button, Space, Form, Card, message } from 'antd';
import { ArrowLeftOutlined, ArrowRightOutlined, CheckOutlined } from '@ant-design/icons';
import FormRenderer from './FormRenderer';
import { FlowProvider, useFlowController } from '../flowController';
import { validationEngine } from '../validationEngine';

const MultiStepFormContent = ({
  formConfig,
  initialData = {},
  onSubmit,
  onCancel,
  submitButtonText = '提交',
  successMessage = '提交成功',
  enableVirtualization = true,
  virtualizationThreshold = 30,
}) => {
  const {
    steps,
    currentStep,
    currentStepIndex,
    canGoNext,
    canGoPrevious,
    isFirstStep,
    isLastStep,
    nextStep,
    previousStep,
    setStepData,
    getStepData,
    getAllData,
  } = useFlowController();

  const [formData, setFormData] = useState(initialData);
  const [errors, setErrors] = useState({});
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (currentStep) {
      const stepData = getStepData(currentStep.step_id);
      setFormData((prev) => ({
        ...prev,
        ...stepData,
      }));
    }
  }, [currentStep, getStepData]);

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

  const validateCurrentStep = useCallback(() => {
    if (!currentStep?.components) return { isValid: true, fieldResults: {} };

    const result = validationEngine.validateForm(formData, currentStep.components);
    setErrors(result.fieldResults);
    return result;
  }, [currentStep, formData]);

  const handleNext = useCallback(() => {
    const validationResult = validateCurrentStep();
    if (!validationResult.isValid) {
      message.warning('请完善当前步骤的必填项');
      return;
    }

    if (currentStep) {
      setStepData(currentStep.step_id, formData);
    }

    nextStep();
  }, [validateCurrentStep, currentStep, formData, setStepData, nextStep]);

  const handlePrevious = useCallback(() => {
    if (currentStep) {
      setStepData(currentStep.step_id, formData);
    }
    previousStep();
  }, [currentStep, formData, setStepData, previousStep]);

  const handleSubmit = useCallback(async () => {
    const validationResult = validateCurrentStep();
    if (!validationResult.isValid) {
      message.warning('请完善当前步骤的必填项');
      return;
    }

    if (currentStep) {
      setStepData(currentStep.step_id, formData);
    }

    const allData = getAllData();
    const fullValidation = validationEngine.validateFormWithSteps(allData, formConfig);

    if (!fullValidation.isValid) {
      message.error('表单校验失败，请检查所有步骤');
      setErrors(fullValidation.fieldResults);
      return;
    }

    setLoading(true);
    try {
      if (onSubmit) {
        await onSubmit(allData);
      }
      message.success(successMessage);
    } catch (error) {
      message.error(error.message || '提交失败');
    } finally {
      setLoading(false);
    }
  }, [
    validateCurrentStep,
    currentStep,
    formData,
    setStepData,
    getAllData,
    formConfig,
    onSubmit,
    successMessage,
  ]);

  const stepItems = steps.map((step, index) => ({
    title: step.step_title,
    description: step.step_description,
  }));

  return (
    <Card>
      <Steps
        current={currentStepIndex}
        items={stepItems}
        size="small"
        style={{ marginBottom: 32 }}
      />

      {currentStep && (
        <>
          <h3 style={{ marginBottom: 16 }}>{currentStep.step_title}</h3>
          {currentStep.step_description && (
            <p style={{ color: '#666', marginBottom: 16 }}>
              {currentStep.step_description}
            </p>
          )}

          <Form layout="vertical">
            <FormRenderer
              formConfig={formConfig}
              formData={formData}
              onFieldChange={handleFieldChange}
              errors={errors}
              currentStepId={currentStep.step_id}
              enableVirtualization={enableVirtualization}
              virtualizationThreshold={virtualizationThreshold}
            />
          </Form>
        </>
      )}

      <div style={{ marginTop: 32, display: 'flex', justifyContent: 'space-between' }}>
        <Space>
          {!isFirstStep && (
            <Button
              icon={<ArrowLeftOutlined />}
              onClick={handlePrevious}
            >
              上一步
            </Button>
          )}
        </Space>

        <Space>
          {onCancel && (
            <Button onClick={onCancel}>
              取消
            </Button>
          )}

          {!isLastStep && (
            <Button
              type="primary"
              icon={<ArrowRightOutlined />}
              onClick={handleNext}
            >
              下一步
            </Button>
          )}

          {isLastStep && (
            <Button
              type="primary"
              icon={<CheckOutlined />}
              onClick={handleSubmit}
              loading={loading}
            >
              {submitButtonText}
            </Button>
          )}
        </Space>
      </div>
    </Card>
  );
};

const MultiStepFormRenderer = ({
  formConfig,
  initialData = {},
  onSubmit,
  onCancel,
  submitButtonText,
  successMessage,
  enableVirtualization = true,
  virtualizationThreshold = 30,
}) => {
  const steps = formConfig.steps || [];

  return (
    <FlowProvider initialSteps={steps}>
      <MultiStepFormContent
        formConfig={formConfig}
        initialData={initialData}
        onSubmit={onSubmit}
        onCancel={onCancel}
        submitButtonText={submitButtonText || formConfig.submit_config?.submit_button_text}
        successMessage={successMessage || formConfig.submit_config?.success_message}
        enableVirtualization={enableVirtualization}
        virtualizationThreshold={virtualizationThreshold}
      />
    </FlowProvider>
  );
};

export default MultiStepFormRenderer;
