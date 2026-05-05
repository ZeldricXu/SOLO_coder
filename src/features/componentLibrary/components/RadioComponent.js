import React from 'react';
import { Radio, Form } from 'antd';

const RadioComponent = ({ component, value, onChange, error, disabled }) => {
  const {
    label,
    required,
    options = [],
    button_style = false,
  } = component;

  return (
    <Form.Item
      label={label}
      required={required}
      validateStatus={error ? 'error' : ''}
      help={error}
    >
      <Radio.Group
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        buttonStyle={button_style ? 'solid' : undefined}
      >
        {options.map((opt) => (
          button_style ? (
            <Radio.Button key={opt.value} value={opt.value}>
              {opt.label}
            </Radio.Button>
          ) : (
            <Radio key={opt.value} value={opt.value}>
              {opt.label}
            </Radio>
          )
        ))}
      </Radio.Group>
    </Form.Item>
  );
};

export default RadioComponent;
