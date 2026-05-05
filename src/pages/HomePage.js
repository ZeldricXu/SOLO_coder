import React from 'react';
import { Card, Button, List, Empty, Space, Tag, Typography, Modal, Input, message, Form } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, BarChartOutlined, CopyOutlined } from '@ant-design/icons';
import { useDispatch } from 'react-redux';
import { resetForm, loadForm } from '../features/formEditor/formEditorSlice';

const { Title, Text } = Typography;

const mockForms = [
  {
    form_id: 'form_demo_001',
    form_name: '产品满意度调查',
    form_type: 'multi_step',
    description: '用于收集用户对产品的使用体验反馈',
    created_at: '2026-05-01T10:00:00Z',
    updated_at: '2026-05-04T15:30:00Z',
    is_published: true,
    publish_url: 'https://app.com/form/form_demo_001',
    submission_count: 127,
  },
  {
    form_id: 'form_demo_002',
    form_name: '用户注册信息收集',
    form_type: 'single_step',
    description: '新用户注册时需要填写的基本信息',
    created_at: '2026-05-02T09:00:00Z',
    updated_at: '2026-05-03T11:20:00Z',
    is_published: false,
    publish_url: '',
    submission_count: 0,
  },
];

const HomePage = ({ onEditForm, onViewData }) => {
  const dispatch = useDispatch();
  const [forms, setForms] = React.useState(mockForms);
  const [createModalVisible, setCreateModalVisible] = React.useState(false);
  const [newFormName, setNewFormName] = React.useState('');

  const handleCreateForm = () => {
    if (!newFormName.trim()) {
      message.warning('请输入表单名称');
      return;
    }

    dispatch(resetForm());
    dispatch(loadForm({
      form_id: `form_${Date.now()}`,
      form_name: newFormName,
      form_type: 'single_step',
      description: '',
      components: [],
      steps: [
        {
          step_id: `step_${Date.now()}`,
          step_title: '第一步',
          step_description: '',
          components: [],
        },
      ],
      submit_config: {
        submit_button_text: '提交',
        success_message: '感谢您的参与',
      },
      publish_config: {
        is_published: false,
        publish_url: '',
      },
    }));

    onEditForm();
  };

  const handleEditForm = (form) => {
    dispatch(loadForm(form));
    onEditForm();
  };

  const handleDeleteForm = (formId) => {
    Modal.confirm({
      title: '确认删除',
      content: '确定要删除这个表单吗？此操作不可恢复。',
      okText: '删除',
      cancelText: '取消',
      okType: 'danger',
      onOk: () => {
        setForms(prev => prev.filter(f => f.form_id !== formId));
        message.success('删除成功');
      },
    });
  };

  const handleCopyPublishUrl = (form) => {
    if (!form.publish_url) {
      message.warning('该表单尚未发布');
      return;
    }
    navigator.clipboard.writeText(form.publish_url)
      .then(() => message.success('链接已复制'))
      .catch(() => message.error('复制失败'));
  };

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto', padding: 24 }}>
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 24,
      }}>
        <div>
          <Title level={2} style={{ margin: 0 }}>FormCraft</Title>
          <Text type="secondary">动态表单构建与数据收集平台</Text>
        </div>
        <Button
          type="primary"
          size="large"
          icon={<PlusOutlined />}
          onClick={() => {
            setNewFormName('');
            setCreateModalVisible(true);
          }}
        >
          新建表单
        </Button>
      </div>

      <Card>
        {forms.length > 0 ? (
          <List
            dataSource={forms}
            renderItem={(form) => (
              <List.Item
                actions={[
                  <Button
                    key="edit"
                    type="link"
                    size="small"
                    icon={<EditOutlined />}
                    onClick={() => handleEditForm(form)}
                  >
                    编辑
                  </Button>,
                  <Button
                    key="data"
                    type="link"
                    size="small"
                    icon={<BarChartOutlined />}
                    onClick={() => {
                      dispatch(loadForm(form));
                      onViewData();
                    }}
                    disabled={form.submission_count === 0}
                  >
                    数据 ({form.submission_count})
                  </Button>,
                  form.is_published && (
                    <Button
                      key="copy"
                      type="link"
                      size="small"
                      icon={<CopyOutlined />}
                      onClick={() => handleCopyPublishUrl(form)}
                    >
                      复制链接
                    </Button>
                  ),
                  <Button
                    key="delete"
                    type="link"
                    size="small"
                    danger
                    icon={<DeleteOutlined />}
                    onClick={() => handleDeleteForm(form.form_id)}
                  >
                    删除
                  </Button>,
                ].filter(Boolean)}
              >
                <List.Item.Meta
                  title={
                    <Space>
                      <span>{form.form_name}</span>
                      {form.is_published ? (
                        <Tag color="green">已发布</Tag>
                      ) : (
                        <Tag>未发布</Tag>
                      )}
                      <Tag>
                        {form.form_type === 'multi_step' ? '多步骤' : '单步骤'}
                      </Tag>
                    </Space>
                  }
                  description={
                    <div>
                      <Text type="secondary">{form.description}</Text>
                      <div style={{ marginTop: 4 }}>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          更新时间: {new Date(form.updated_at).toLocaleString('zh-CN')}
                        </Text>
                      </div>
                    </div>
                  }
                />
              </List.Item>
            )}
          />
        ) : (
          <Empty
            description="暂无表单，点击上方按钮创建新表单"
            image={Empty.PRESENTED_IMAGE_SIMPLE}
          />
        )}
      </Card>

      <Modal
        title="新建表单"
        open={createModalVisible}
        onOk={handleCreateForm}
        onCancel={() => setCreateModalVisible(false)}
        okText="创建"
        cancelText="取消"
      >
        <Form layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item label="表单名称" required>
            <Input
              value={newFormName}
              onChange={(e) => setNewFormName(e.target.value)}
              placeholder="请输入表单名称"
              onPressEnter={handleCreateForm}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default HomePage;
