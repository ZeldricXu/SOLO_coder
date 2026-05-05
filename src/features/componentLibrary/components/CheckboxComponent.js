import React from 'react';
import { Checkbox, Form } from 'antd';

const CheckboxComponent = ({ component, value, onChange, error, disabled }) => {
  const {
    label,
    required,
    options = [],
  } = component;

  const handleChange = (checkedValues) => {
    onChange(checkedValues);
  };

  return (
    <Form.Item
      label={label}
      required={required}
      validateStatus={error ? 'error' : ''}
      help={error}
    >
      <Checkbox.Group
        options={options.map(opt => ({
          value: opt.value,
          label: opt.label,
        }))}
        value={value || []}
        onChange={handleChange}
        disabled={disabled}
      />
    </Form.Item>
  );
};

export default CheckboxComponent;
