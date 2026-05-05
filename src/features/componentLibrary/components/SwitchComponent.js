import React from 'react';
import { Switch, Form } from 'antd';

const SwitchComponent = ({ component, value, onChange, error, disabled }) => {
  const {
    label,
    required,
    checked_text = '开启',
    unchecked_text = '关闭',
  } = component;

  return (
    <Form.Item
      label={label}
      required={required}
      validateStatus={error ? 'error' : ''}
      help={error}
    >
      <Switch
        checked={value || false}
        onChange={onChange}
        disabled={disabled}
        checkedChildren={checked_text}
        unCheckedChildren={unchecked_text}
      />
    </Form.Item>
  );
};

export default SwitchComponent;
