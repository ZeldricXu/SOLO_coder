import React from 'react';
import { Popconfirm, Button } from 'antd';
import type { PopconfirmProps } from 'antd';

interface ConfirmDeleteProps extends Omit<PopconfirmProps, 'onConfirm' | 'title'> {
  title?: string;
  description?: string;
  onConfirm: () => Promise<void> | void;
  buttonText?: string;
  buttonType?: 'link' | 'text' | 'primary' | 'default' | 'dashed' | 'ghost';
  danger?: boolean;
  icon?: React.ReactNode;
}

const ConfirmDelete: React.FC<ConfirmDeleteProps> = ({
  title = '确认删除',
  description = '删除后数据将无法恢复，确定要删除吗？',
  onConfirm,
  buttonText = '删除',
  buttonType = 'link',
  danger = true,
  icon,
  children,
  ...rest
}) => {
  const handleConfirm = async () => {
    await onConfirm();
  };

  return (
    <Popconfirm
      title={title}
      description={description}
      onConfirm={handleConfirm}
      okText="确认"
      cancelText="取消"
      okButtonProps={{ danger }}
      {...rest}
    >
      {children || (
        <Button type={buttonType} danger={danger} icon={icon}>
          {buttonText}
        </Button>
      )}
    </Popconfirm>
  );
};

export default ConfirmDelete;
