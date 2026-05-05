import React from 'react';
import { Tabs, Card, Button, Space, Empty, Tag, Modal, message } from 'antd';
import { ArrowLeftOutlined, FileTextOutlined, BarChartOutlined } from '@ant-design/icons';
import { useSelector, useDispatch } from 'react-redux';
import SubmissionList from '../features/dataCollector/SubmissionList';
import StatisticsDashboard from '../features/dataCollector/StatisticsDashboard';
import ExportButton from '../features/exportModule/ExportButton';
import {
  selectFormConfig,
} from '../features/formEditor/formEditorSlice';
import {
  selectSubmissions,
  selectTotalSubmissions,
  clearSubmissions,
} from '../features/dataCollector/dataCollectorSlice';

const DataCollectionPage = ({ onBack }) => {
  const dispatch = useDispatch();
  const formConfig = useSelector(selectFormConfig);
  const submissions = useSelector(selectSubmissions);
  const totalSubmissions = useSelector(selectTotalSubmissions);

  const handleClearData = () => {
    Modal.confirm({
      title: '确认清空',
      content: '确定要清空所有提交数据吗？此操作不可恢复。',
      okText: '清空',
      cancelText: '取消',
      okType: 'danger',
      onOk: () => {
        dispatch(clearSubmissions());
        message.success('数据已清空');
      },
    });
  };

  const tabItems = [
    {
      key: 'list',
      label: (
        <span>
          <FileTextOutlined /> 数据列表
        </span>
      ),
      children: (
        <SubmissionList formConfig={formConfig} />
      ),
    },
    {
      key: 'statistics',
      label: (
        <span>
          <BarChartOutlined /> 统计分析
        </span>
      ),
      children: (
        <StatisticsDashboard formConfig={formConfig} />
      ),
    },
  ];

  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column' }}>
      <div style={{
        padding: '12px 24px',
        borderBottom: '1px solid #f0f0f0',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        backgroundColor: '#fff',
      }}>
        <Space>
          <Button
            icon={<ArrowLeftOutlined />}
            onClick={onBack}
          >
            返回
          </Button>
          <h2 style={{ margin: 0 }}>{formConfig.form_name}</h2>
        </Space>

        <Space>
          <Tag color="blue">
            共 {totalSubmissions} 条提交
          </Tag>
          <ExportButton
            formConfig={formConfig}
            filename={formConfig.form_name}
          />
          {submissions.length > 0 && (
            <Button danger onClick={handleClearData}>
              清空数据
            </Button>
          )}
        </Space>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
        {submissions.length === 0 ? (
          <Card>
            <Empty
              description="暂无提交数据"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            >
              <p style={{ color: '#999' }}>
                当用户提交表单后，数据将在此处显示
              </p>
            </Empty>
          </Card>
        ) : (
          <Tabs
            defaultActiveKey="list"
            items={tabItems}
          />
        )}
      </div>
    </div>
  );
};

export default DataCollectionPage;
