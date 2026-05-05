import React, { useMemo } from 'react';
import { Table, Card, Tag, Space, Button, Modal, Descriptions, Empty } from 'antd';
import { EyeOutlined, DeleteOutlined, DownloadOutlined } from '@ant-design/icons';
import { useSelector, useDispatch } from 'react-redux';
import {
  selectSubmissions,
  selectTotalSubmissions,
  deleteSubmission,
} from './dataCollectorSlice';

const SubmissionList = ({ formConfig }) => {
  const dispatch = useDispatch();
  const submissions = useSelector(selectSubmissions);
  const totalSubmissions = useSelector(selectTotalSubmissions);

  const [selectedSubmission, setSelectedSubmission] = React.useState(null);
  const [detailModalVisible, setDetailModalVisible] = React.useState(false);

  const allComponentIds = useMemo(() => {
    if (!formConfig) return [];

    const components = [];
    if (formConfig.form_type === 'multi_step' && formConfig.steps) {
      formConfig.steps.forEach(step => {
        (step.components || []).forEach(comp => {
          components.push(comp);
        });
      });
    } else if (formConfig.components) {
      components.push(...formConfig.components);
    }

    return components.reduce((acc, comp) => {
      acc[comp.component_id] = comp.label || comp.component_id;
      return acc;
    }, {});
  }, [formConfig]);

  const columns = useMemo(() => {
    const baseColumns = [
      {
        title: '提交ID',
        dataIndex: 'submission_id',
        key: 'submission_id',
        width: 180,
        ellipsis: true,
      },
      {
        title: '提交时间',
        dataIndex: 'submitted_at',
        key: 'submitted_at',
        width: 180,
        render: (text) => {
          if (!text) return '-';
          return new Date(text).toLocaleString('zh-CN');
        },
      },
    ];

    const previewColumns = Object.entries(allComponentIds).slice(0, 3).map(([id, label]) => ({
      title: label,
      dataIndex: ['data', id],
      key: id,
      width: 120,
      ellipsis: true,
      render: (value) => {
        if (value === null || value === undefined) return '-';
        if (Array.isArray(value)) return value.join(', ');
        if (typeof value === 'object') return JSON.stringify(value);
        return String(value);
      },
    }));

    return [
      ...baseColumns,
      ...previewColumns,
      {
        title: '操作',
        key: 'action',
        width: 120,
        fixed: 'right',
        render: (_, record) => (
          <Space>
            <Button
              type="link"
              size="small"
              icon={<EyeOutlined />}
              onClick={() => {
                setSelectedSubmission(record);
                setDetailModalVisible(true);
              }}
            >
              详情
            </Button>
            <Button
              type="link"
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={() => {
                Modal.confirm({
                  title: '确认删除',
                  content: '确定要删除这条提交记录吗？此操作不可恢复。',
                  okText: '删除',
                  cancelText: '取消',
                  okType: 'danger',
                  onOk: () => {
                    dispatch(deleteSubmission(record.submission_id));
                  },
                });
              }}
            >
              删除
            </Button>
          </Space>
        ),
      },
    ];
  }, [allComponentIds, dispatch]);

  const tableData = useMemo(() => {
    return submissions.map((submission, index) => ({
      ...submission,
      key: submission.submission_id || index,
    }));
  }, [submissions]);

  return (
    <>
      <Card
        title="提交数据列表"
        extra={
          <Tag color="blue">
            共 {totalSubmissions} 条记录
          </Tag>
        }
      >
        {submissions.length > 0 ? (
          <Table
            columns={columns}
            dataSource={tableData}
            scroll={{ x: 800 }}
            pagination={{
              showSizeChanger: true,
              showQuickJumper: true,
              showTotal: (total) => `共 ${total} 条`,
              defaultPageSize: 10,
            }}
          />
        ) : (
          <Empty
            description="暂无提交数据"
            image={Empty.PRESENTED_IMAGE_SIMPLE}
          />
        )}
      </Card>

      <Modal
        title="提交详情"
        open={detailModalVisible}
        onCancel={() => setDetailModalVisible(false)}
        footer={[
          <Button key="close" onClick={() => setDetailModalVisible(false)}>
            关闭
          </Button>,
        ]}
        width={600}
      >
        {selectedSubmission && (
          <Descriptions
            bordered
            column={1}
            size="small"
          >
            <Descriptions.Item label="提交ID">
              {selectedSubmission.submission_id}
            </Descriptions.Item>
            <Descriptions.Item label="提交时间">
              {selectedSubmission.submitted_at
                ? new Date(selectedSubmission.submitted_at).toLocaleString('zh-CN')
                : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="表单数据">
              <Descriptions
                bordered
                column={1}
                size="small"
                style={{ marginTop: 8 }}
              >
                {Object.entries(selectedSubmission.data || {}).map(([key, value]) => (
                  <Descriptions.Item key={key} label={allComponentIds[key] || key}>
                    {value === null || value === undefined
                      ? '-'
                      : Array.isArray(value)
                        ? value.join(', ')
                        : typeof value === 'object'
                          ? JSON.stringify(value, null, 2)
                          : String(value)}
                  </Descriptions.Item>
                ))}
              </Descriptions>
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </>
  );
};

export default SubmissionList;
