import React from 'react';
import { DatePicker, Form } from 'antd';
import dayjs from 'dayjs';

const DatePickerComponent = ({ component, value, onChange, error, disabled }) => {
  const {
    label,
    placeholder = '请选择日期',
    required,
    date_type = 'date',
  } = component;

  const handleChange = (date) => {
    if (date) {
      onChange(date.format('YYYY-MM-DD'));
    } else {
      onChange(null);
    }
  };

  const handleRangeChange = (dates) => {
    if (dates && dates[0] && dates[1]) {
      onChange([dates[0].format('YYYY-MM-DD'), dates[1].format('YYYY-MM-DD')]);
    } else {
      onChange(null);
    }
  };

  const dayjsValue = value ? dayjs(value) : null;
  const rangeValue = value && Array.isArray(value)
    ? value.map(v => dayjs(v))
    : null;

  return (
    <Form.Item
      label={label}
      required={required}
      validateStatus={error ? 'error' : ''}
      help={error}
    >
      {date_type === 'range' ? (
        <DatePicker.RangePicker
          placeholder={['开始日期', '结束日期']}
          value={rangeValue}
          onChange={handleRangeChange}
          disabled={disabled}
          style={{ width: '100%' }}
        />
      ) : date_type === 'month' ? (
        <DatePicker.MonthPicker
          placeholder={placeholder}
          value={dayjsValue}
          onChange={handleChange}
          disabled={disabled}
          style={{ width: '100%' }}
        />
      ) : (
        <DatePicker
          placeholder={placeholder}
          value={dayjsValue}
          onChange={handleChange}
          disabled={disabled}
          style={{ width: '100%' }}
        />
      )}
    </Form.Item>
  );
};

export default DatePickerComponent;
