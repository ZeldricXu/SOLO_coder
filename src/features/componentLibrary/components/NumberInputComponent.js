import React from 'react';
import { InputNumber, Form } from 'antd';

const NumberInputComponent = ({ component, value, onChange, error, disabled }) => {
  const {
    label,
    placeholder = '请输入数字',
    required,
    validation = {},
    prefix,
    suffix,
  } = component;

  return (
    <Form.Item
      label={label}
      required={required}
      validateStatus={error ? 'error' : ''}
      help={error}
    >
      <InputNumber
        placeholder={placeholder}
        value={value}
        onChange={onChange}
        disabled={disabled}
        min={validation.min}
        max={validation.max}
        step={validation.step || 1}
        prefix={prefix}
        suffix={suffix}
        style={{ width: '100%' }}
      />
    </Form.Item>
  );
};

export default NumberInputComponent;
