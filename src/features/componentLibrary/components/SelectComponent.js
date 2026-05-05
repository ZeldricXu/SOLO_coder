import React from 'react';
import { Select, Form } from 'antd';

const SelectComponent = ({ component, value, onChange, error, disabled }) => {
  const {
    label,
    placeholder = '请选择',
    required,
    options = [],
    multiple = false,
  } = component;

  const selectOptions = options.map((opt) => ({
    value: opt.value,
    label: opt.label,
  }));

  return (
    <Form.Item
      label={label}
      required={required}
      validateStatus={error ? 'error' : ''}
      help={error}
    >
      <Select
        placeholder={placeholder}
        value={value}
        onChange={onChange}
        disabled={disabled}
        options={selectOptions}
        mode={multiple ? 'multiple' : undefined}
        allowClear
      />
    </Form.Item>
  );
};

export default SelectComponent;
