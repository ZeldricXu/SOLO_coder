import React from 'react';
import { Rate, Form } from 'antd';

const RatingComponent = ({ component, value, onChange, error, disabled }) => {
  const {
    label,
    required,
    max_value = 5,
    allow_half = false,
  } = component;

  return (
    <Form.Item
      label={label}
      required={required}
      validateStatus={error ? 'error' : ''}
      help={error}
    >
      <Rate
        count={max_value}
        value={value}
        onChange={onChange}
        disabled={disabled}
        allowHalf={allow_half}
      />
    </Form.Item>
  );
};

export default RatingComponent;
