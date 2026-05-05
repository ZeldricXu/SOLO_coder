import React from 'react';
import { Input, Form } from 'antd';

const TextAreaComponent = ({ component, value, onChange, error, disabled }) => {
  const {
    label,
    placeholder = '请输入',
    required,
    validation = {},
  } = component;

  return (
    <Form.Item
      label={label}
      required={required}
      validateStatus={error ? 'error' : ''}
      help={error}
    >
      <Input.TextArea
        placeholder={placeholder}
        value={value || ''}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        maxLength={validation.max_length}
        showCount={validation.max_length > 0}
        rows={4}
      />
    </Form.Item>
  );
};

export default TextAreaComponent;
