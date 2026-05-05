import React, { useMemo, useCallback } from 'react';
import { COMPONENT_MAP, COMPONENT_TYPE } from '../componentLibrary';
import VirtualList from './VirtualList';

const VIRTUALIZATION_THRESHOLD = 30;
const ESTIMATED_ITEM_HEIGHT = 90;
const OVERSCAN_COUNT = 3;

const ComponentWrapper = React.memo(({
  component,
  formData,
  onFieldChange,
  errors,
  disabled,
}) => {
  const Component = COMPONENT_MAP[component.component_type];
  if (!Component) {
    console.warn(`Unknown component type: ${component.component_type}`);
    return null;
  }

  const value = formData[component.component_id];
  const fieldErrors = errors[component.component_id];
  const errorMessage = fieldErrors?.errors?.[0] || null;

  return (
    <Component
      component={component}
      value={value}
      onChange={(newValue) => onFieldChange(component.component_id, newValue)}
      error={errorMessage}
      disabled={disabled}
    />
  );
});

ComponentWrapper.displayName = 'ComponentWrapper';

const FormRenderer = ({
  formConfig,
  formData = {},
  onFieldChange,
  errors = {},
  disabled = false,
  currentStepId = null,
  enableVirtualization = true,
  virtualizationThreshold = VIRTUALIZATION_THRESHOLD,
  containerStyle,
  maxHeight,
}) => {
  const componentsToRender = useMemo(() => {
    if (formConfig.form_type === 'multi_step') {
      const steps = formConfig.steps || [];
      if (currentStepId) {
        const step = steps.find((s) => s.step_id === currentStepId);
        return step?.components || [];
      }
      return steps[0]?.components || [];
    }
    return formConfig.components || [];
  }, [formConfig, currentStepId]);

  const componentCount = componentsToRender.length;
  const shouldUseVirtualization = enableVirtualization && componentCount > virtualizationThreshold;

  const renderComponent = useCallback(
    (component) => (
      <ComponentWrapper
        key={component.component_id}
        component={component}
        formData={formData}
        onFieldChange={onFieldChange}
        errors={errors}
        disabled={disabled}
      />
    ),
    [formData, onFieldChange, errors, disabled]
  );

  const renderVirtualItem = useCallback(
    (component, index) => (
      <ComponentWrapper
        component={component}
        formData={formData}
        onFieldChange={onFieldChange}
        errors={errors}
        disabled={disabled}
      />
    ),
    [formData, onFieldChange, errors, disabled]
  );

  if (componentCount === 0) {
    return (
      <div className="form-renderer" style={containerStyle}>
        <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
          暂无表单组件
        </div>
      </div>
    );
  }

  if (!shouldUseVirtualization) {
    return (
      <div className="form-renderer" style={containerStyle}>
        {componentsToRender.map((component) => renderComponent(component))}
      </div>
    );
  }

  const virtualListStyle = {
    ...containerStyle,
    maxHeight: maxHeight || '70vh',
    overflow: 'auto',
  };

  return (
    <div className="form-renderer">
      <VirtualList
        items={componentsToRender}
        renderItem={renderVirtualItem}
        itemKey="component_id"
        itemHeight={ESTIMATED_ITEM_HEIGHT}
        overscan={OVERSCAN_COUNT}
        threshold={virtualizationThreshold}
        style={virtualListStyle}
      />
    </div>
  );
};

export default FormRenderer;
