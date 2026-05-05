import React from 'react';
import { Upload, Button, Form, message } from 'antd';
import { UploadOutlined } from '@ant-design/icons';

const FileUploadComponent = ({ component, value, onChange, error, disabled }) => {
  const {
    label,
    placeholder = '请上传文件',
    required,
    file_types = [],
    max_size = 10,
    multiple = false,
  } = component;

  const fileTypeText = file_types.length > 0
    ? `支持格式：${file_types.join('、')}`
    : '';

  const beforeUpload = (file) => {
    const isLtSize = file.size / 1024 / 1024 < max_size;
    if (!isLtSize) {
      message.error(`文件大小不能超过 ${max_size}MB！`);
      return false;
    }

    if (file_types.length > 0) {
      const fileExtension = file.name.split('.').pop().toLowerCase();
      const isAllowed = file_types.some(type =>
        type.toLowerCase() === fileExtension ||
        type.toLowerCase() === `.${fileExtension}`
      );
      if (!isAllowed) {
        message.error(`仅支持上传 ${file_types.join('、')} 格式文件！`);
        return false;
      }
    }

    return false;
  };

  const handleChange = (info) => {
    const { fileList } = info;
    const files = fileList.map(file => ({
      name: file.name,
      size: file.size,
      type: file.type,
      uid: file.uid,
    }));
    onChange(multiple ? files : files[0] || null);
  };

  const fileList = value
    ? (Array.isArray(value) ? value : [value]).map(file => ({
        uid: file.uid || file.name,
        name: file.name,
        size: file.size,
        type: file.type,
        status: 'done',
      }))
    : [];

  return (
    <Form.Item
      label={label}
      required={required}
      validateStatus={error ? 'error' : ''}
      help={error || fileTypeText}
    >
      <Upload
        beforeUpload={beforeUpload}
        onChange={handleChange}
        fileList={fileList}
        disabled={disabled}
        multiple={multiple}
        maxCount={multiple ? undefined : 1}
      >
        <Button icon={<UploadOutlined />} disabled={disabled}>
          {placeholder}
        </Button>
      </Upload>
    </Form.Item>
  );
};

export default FileUploadComponent;
