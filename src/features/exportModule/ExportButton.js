import React from 'react';
import { Dropdown, Button, Space, Menu, message } from 'antd';
import { DownloadOutlined, FileTextOutlined, FileOutlined } from '@ant-design/icons';
import { useSelector } from 'react-redux';
import { exportModule, EXPORT_FORMAT } from './index';
import { selectSubmissions, selectFormId } from '../dataCollector/dataCollectorSlice';

const ExportButton = ({ formConfig, filename = 'form_data' }) => {
  const submissions = useSelector(selectSubmissions);
  const formId = useSelector(selectFormId);

  const handleExport = (format) => {
    if (submissions.length === 0) {
      message.warning('暂无数据可导出');
      return;
    }

    try {
      exportModule.export(submissions, format, {
        formConfig,
        filename: filename || formId || 'form_data',
        prettyPrint: true,
      });
      message.success(`已导出 ${submissions.length} 条数据`);
    } catch (error) {
      message.error(`导出失败: ${error.message}`);
    }
  };

  const menuItems = [
    {
      key: 'csv',
      icon: <FileTextOutlined />,
      label: '导出 CSV',
      onClick: () => handleExport(EXPORT_FORMAT.CSV),
    },
    {
      key: 'json',
      icon: <FileOutlined />,
      label: '导出 JSON',
      onClick: () => handleExport(EXPORT_FORMAT.JSON),
    },
  ];

  return (
    <Dropdown menu={{ items }}>
      <Button icon={<DownloadOutlined />}>
        导出数据
      </Button>
    </Dropdown>
  );
};

export default ExportButton;
