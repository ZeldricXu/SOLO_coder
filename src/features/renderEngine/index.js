import React from 'react';
import FormRenderer from './FormRenderer';
import SingleStepFormRenderer from './SingleStepFormRenderer';
import MultiStepFormRenderer from './MultiStepFormRenderer';
import VirtualList from './VirtualList';

const DynamicFormRenderer = ({
  formConfig,
  initialData = {},
  onSubmit,
  onCancel,
  submitButtonText,
  successMessage,
  enableVirtualization = true,
  virtualizationThreshold = 30,
}) => {
  if (!formConfig) {
    return null;
  }

  const isMultiStep = formConfig.form_type === 'multi_step' &&
    Array.isArray(formConfig.steps) &&
    formConfig.steps.length > 0;

  const finalSubmitButtonText = submitButtonText ||
    formConfig.submit_config?.submit_button_text ||
    '提交';

  const finalSuccessMessage = successMessage ||
    formConfig.submit_config?.success_message ||
    '提交成功';

  if (isMultiStep) {
    return (
      <MultiStepFormRenderer
        formConfig={formConfig}
        initialData={initialData}
        onSubmit={onSubmit}
        onCancel={onCancel}
        submitButtonText={finalSubmitButtonText}
        successMessage={finalSuccessMessage}
        enableVirtualization={enableVirtualization}
        virtualizationThreshold={virtualizationThreshold}
      />
    );
  }

  return (
    <SingleStepFormRenderer
      formConfig={formConfig}
      initialData={initialData}
      onSubmit={onSubmit}
      onCancel={onCancel}
      submitButtonText={finalSubmitButtonText}
      successMessage={finalSuccessMessage}
      enableVirtualization={enableVirtualization}
      virtualizationThreshold={virtualizationThreshold}
    />
  );
};

export {
  FormRenderer,
  SingleStepFormRenderer,
  MultiStepFormRenderer,
  DynamicFormRenderer,
  VirtualList,
};

export default DynamicFormRenderer;
