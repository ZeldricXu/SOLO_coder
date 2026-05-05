import React from 'react';
import { useSelector, useDispatch } from 'react-redux';
import { Button, Card, Modal, message } from 'antd';
import { ArrowLeftOutlined, SaveOutlined, EyeOutlined, ShareAltOutlined } from '@ant-design/icons';
import DragEditor from '../features/dragEditor/DragEditor';
import DynamicFormRenderer from '../features/renderEngine';
import {
  selectFormConfig,
  selectIsPreviewMode,
  setPreviewMode,
  saveStart,
  saveSuccess,
  saveFailure,
} from '../features/formEditor/formEditorSlice';
import { addSubmission } from '../features/dataCollector/dataCollectorSlice';

const FormEditorPage = ({ onBack }) => {
  const dispatch = useDispatch();
  const formConfig = useSelector(selectFormConfig);
  const isPreviewMode = useSelector(selectIsPreviewMode);

  const [publishModalVisible, setPublishModalVisible] = React.useState(false);
  const [publishUrl, setPublishUrl] = React.useState('');

  const handleBack = () => {
    if (onBack) {
      onBack();
    }
  };

  const handleSave = async () => {
    dispatch(saveStart());

    try {
      await new Promise(resolve => setTimeout(resolve, 500));

      dispatch(saveSuccess({
        form_id: formConfig.form_id || `form_${Date.now()}`,
      }));

      message.success('保存成功');
    } catch (error) {
      dispatch(saveFailure(error.message));
      message.error('保存失败');
    }
  };

  const handlePreview = () => {
    dispatch(setPreviewMode(!isPreviewMode));
  };

  const handlePublish = () => {
    const formId = formConfig.form_id || `form_${Date.now()}`;
    const url = `${window.location.origin}/form/${formId}`;
    setPublishUrl(url);
    setPublishModalVisible(true);
  };

  const handleCopyUrl = () => {
    navigator.clipboard.writeText(publishUrl)
      .then(() => message.success('链接已复制到剪贴板'))
      .catch(() => message.error('复制失败'));
  };

  const handlePreviewSubmit = async (formData) => {
    const submission = {
      submission_id: `sub_${Date.now()}`,
      form_id: formConfig.form_id || 'preview_form',
      submitted_at: new Date().toISOString(),
      data: formData,
    };

    dispatch(addSubmission(submission));

    return new Promise(resolve => setTimeout(resolve, 500));
  };

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
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={handleBack}
        >
          返回
        </Button>

        <div style={{ flex: 1, textAlign: 'center', fontWeight: 'bold' }}>
          {isPreviewMode ? '表单预览' : '表单编辑器'}
        </div>

        <div style={{ display: 'flex', gap: 8 }}>
          {!isPreviewMode && (
            <>
              <Button
                icon={<SaveOutlined />}
                onClick={handleSave}
              >
                保存
              </Button>
              <Button
                icon={<ShareAltOutlined />}
                onClick={handlePublish}
              >
                发布
              </Button>
            </>
          )}
          <Button
            type={isPreviewMode ? 'primary' : 'default'}
            icon={<EyeOutlined />}
            onClick={handlePreview}
          >
            {isPreviewMode ? '退出预览' : '预览'}
          </Button>
        </div>
      </div>

      <div style={{ flex: 1, overflow: 'auto' }}>
        {isPreviewMode ? (
          <div style={{ maxWidth: 800, margin: '24px auto', padding: '0 24px' }}>
            <Card>
              <h2 style={{ marginBottom: 8 }}>{formConfig.form_name}</h2>
              {formConfig.description && (
                <p style={{ color: '#666', marginBottom: 24 }}>
                  {formConfig.description}
                </p>
              )}
              <DynamicFormRenderer
                formConfig={formConfig}
                onSubmit={handlePreviewSubmit}
              />
            </Card>
          </div>
        ) : (
          <DragEditor />
        )}
      </div>

      <Modal
        title="发布表单"
        open={publishModalVisible}
        onOk={handleCopyUrl}
        onCancel={() => setPublishModalVisible(false)}
        okText="复制链接"
        cancelText="关闭"
      >
        <p style={{ marginBottom: 16 }}>表单发布地址：</p>
        <div style={{
          padding: 12,
          backgroundColor: '#f5f5f5',
          borderRadius: 4,
          wordBreak: 'break-all',
          fontFamily: 'monospace',
        }}>
          {publishUrl}
        </div>
        <p style={{ marginTop: 16, color: '#666', fontSize: 12 }}>
          点击"复制链接"将地址复制到剪贴板，用户可通过该链接访问表单
        </p>
      </Modal>
    </div>
  );
};

export default FormEditorPage;
