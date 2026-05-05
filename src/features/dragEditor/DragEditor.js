import React, { useCallback, useMemo } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Card, Typography, Steps, Button, Space, Modal, Input, message, Empty, Form } from 'antd';
import { PlusOutlined, DeleteOutlined, EditOutlined, SaveOutlined, EyeOutlined } from '@ant-design/icons';
import ComponentLibrary from './ComponentLibrary';
import CanvasItem from './CanvasItem';
import DroppableCanvas from './DroppableCanvas';
import PropertyPanel from './PropertyPanel';
import {
  selectFormConfig,
  selectSelectedComponentId,
  selectCurrentStepId,
  selectCurrentStepComponents,
  selectSelectedComponent,
  setCurrentStep,
  addStep,
  updateStep,
  deleteStep,
  moveStep,
  addComponent,
  updateComponent,
  deleteComponent,
  moveComponent,
  selectComponent,
  clearSelection,
  setPreviewMode,
  saveStart,
  saveSuccess,
  saveFailure,
  updateFormConfig,
} from '../formEditor/formEditorSlice';
import DynamicFormRenderer from '../renderEngine';

const { Title, Text } = Typography;

const DragEditor = () => {
  const dispatch = useDispatch();
  const formConfig = useSelector(selectFormConfig);
  const selectedComponentId = useSelector(selectSelectedComponentId);
  const currentStepId = useSelector(selectCurrentStepId);
  const currentStepComponents = useSelector(selectCurrentStepComponents);
  const selectedComponent = useSelector(selectSelectedComponent);

  const [stepEditModal, setStepEditModal] = React.useState({
    visible: false,
    stepId: null,
    title: '',
    description: '',
  });

  const [formNameEdit, setFormNameEdit] = React.useState({
    visible: false,
    value: formConfig.form_name,
  });

  const isMultiStep = formConfig.form_type === 'multi_step';
  const steps = formConfig.steps || [];
  const currentStepIndex = steps.findIndex(s => s.step_id === currentStepId);

  const handleDrop = useCallback((item, monitor) => {
    if (item.componentType) {
      dispatch(addComponent({
        componentType: item.componentType,
        stepId: currentStepId,
      }));
    }
  }, [dispatch, currentStepId]);

  const handleSelectComponent = useCallback((componentId) => {
    dispatch(selectComponent(componentId));
  }, [dispatch]);

  const handleDeleteComponent = useCallback((componentId) => {
    dispatch(deleteComponent(componentId));
  }, [dispatch]);

  const handleEditComponent = useCallback((componentId) => {
    dispatch(selectComponent(componentId));
  }, [dispatch]);

  const handleUpdateComponent = useCallback((componentId, updates) => {
    dispatch(updateComponent({ componentId, updates }));
  }, [dispatch]);

  const handleMoveComponent = useCallback((fromIndex, toIndex) => {
    dispatch(moveComponent({ fromIndex, toIndex, stepId: currentStepId }));
  }, [dispatch, currentStepId]);

  const handleCanvasClick = useCallback((e) => {
    if (e.target === e.currentTarget) {
      dispatch(clearSelection());
    }
  }, [dispatch]);

  const handleStepClick = useCallback((stepId) => {
    dispatch(setCurrentStep(stepId));
  }, [dispatch]);

  const handleAddStep = useCallback(() => {
    dispatch(addStep());
  }, [dispatch]);

  const handleEditStep = useCallback((step) => {
    setStepEditModal({
      visible: true,
      stepId: step.step_id,
      title: step.step_title,
      description: step.step_description || '',
    });
  }, []);

  const handleSaveStep = useCallback(() => {
    if (stepEditModal.stepId) {
      dispatch(updateStep({
        stepId: stepEditModal.stepId,
        updates: {
          step_title: stepEditModal.title,
          step_description: stepEditModal.description,
        },
      }));
    }
    setStepEditModal({
      visible: false,
      stepId: null,
      title: '',
      description: '',
    });
  }, [stepEditModal, dispatch]);

  const handleDeleteStep = useCallback((stepId) => {
    if (steps.length <= 1) {
      message.warning('至少需要保留一个步骤');
      return;
    }
    dispatch(deleteStep(stepId));
  }, [steps.length, dispatch]);

  const handleFormNameClick = useCallback(() => {
    setFormNameEdit({
      visible: true,
      value: formConfig.form_name,
    });
  }, [formConfig.form_name]);

  const handleFormNameSave = useCallback(() => {
    dispatch(updateFormConfig({ form_name: formNameEdit.value }));
    setFormNameEdit({
      visible: false,
      value: formConfig.form_name,
    });
  }, [formNameEdit, formConfig.form_name, dispatch]);

  const handlePreview = useCallback(() => {
    dispatch(setPreviewMode(true));
  }, [dispatch]);

  const stepItems = steps.map((step, index) => ({
    title: (
      <Space>
        <span>{step.step_title}</span>
        <Button
          type="text"
          size="small"
          icon={<EditOutlined />}
          onClick={(e) => {
            e.stopPropagation();
            handleEditStep(step);
          }}
        />
        {steps.length > 1 && (
          <Button
            type="text"
            size="small"
            danger
            icon={<DeleteOutlined />}
            onClick={(e) => {
              e.stopPropagation();
              handleDeleteStep(step.step_id);
            }}
          />
        )}
      </Space>
    ),
  }));

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div style={{
        padding: '12px 24px',
        borderBottom: '1px solid #f0f0f0',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
      }}>
        <Space>
          {formNameEdit.visible ? (
            <Input
              value={formNameEdit.value}
              onChange={(e) => setFormNameEdit(prev => ({ ...prev, value: e.target.value }))}
              onBlur={handleFormNameSave}
              onPressEnter={handleFormNameSave}
              autoFocus
              style={{ width: 300 }}
            />
          ) : (
            <Title
              level={4}
              style={{ margin: 0, cursor: 'pointer' }}
              onClick={handleFormNameClick}
            >
              {formConfig.form_name}
              <EditOutlined style={{ fontSize: 14, marginLeft: 8, color: '#999' }} />
            </Title>
          )}
        </Space>

        <Space>
          <Button
            icon={<EyeOutlined />}
            onClick={handlePreview}
          >
            预览
          </Button>
          <Button
            type="primary"
            icon={<SaveOutlined />}
          >
            保存
          </Button>
        </Space>
      </div>

      {isMultiStep && (
        <div style={{
          padding: '12px 24px',
          borderBottom: '1px solid #f0f0f0',
          display: 'flex',
          alignItems: 'center',
          gap: 16,
        }}>
          <Steps
            current={currentStepIndex}
            items={stepItems}
            size="small"
            onChange={(index) => handleStepClick(steps[index].step_id)}
            style={{ flex: 1 }}
          />
          <Button
            type="dashed"
            icon={<PlusOutlined />}
            onClick={handleAddStep}
          >
            添加步骤
          </Button>
        </div>
      )}

      <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
        <div style={{
          width: 240,
          borderRight: '1px solid #f0f0f0',
          padding: 16,
          overflowY: 'auto',
        }}>
          <Title level={5} style={{ marginBottom: 16 }}>组件库</Title>
          <ComponentLibrary />
        </div>

        <div
          style={{
            flex: 1,
            padding: 24,
            overflowY: 'auto',
            backgroundColor: '#f5f5f5',
          }}
          onClick={handleCanvasClick}
        >
          <Card
            style={{
              maxWidth: 800,
              margin: '0 auto',
              minHeight: 400,
            }}
          >
            {currentStepComponents.length > 0 ? (
              <DroppableCanvas
                onDrop={handleDrop}
                placeholder="拖拽组件到此处或点击下方组件添加"
              >
                <div style={{ display: 'none' }}>
                  {currentStepComponents.map((component, index) => (
                    <div key={component.component_id} />
                  ))}
                </div>
              </DroppableCanvas>
            ) : (
              <DroppableCanvas onDrop={handleDrop} />
            )}

            {currentStepComponents.map((component, index) => (
              <CanvasItem
                key={component.component_id}
                component={component}
                index={index}
                isSelected={selectedComponentId === component.component_id}
                onSelect={handleSelectComponent}
                onDelete={handleDeleteComponent}
                onEdit={handleEditComponent}
                onReorder={handleMoveComponent}
              />
            ))}
          </Card>
        </div>

        <div style={{
          width: 320,
          borderLeft: '1px solid #f0f0f0',
          padding: 16,
          overflowY: 'auto',
        }}>
          <Title level={5} style={{ marginBottom: 16 }}>属性配置</Title>
          <PropertyPanel
            selectedComponent={selectedComponent}
            onUpdateComponent={handleUpdateComponent}
            onDeleteComponent={handleDeleteComponent}
          />
        </div>
      </div>

      <Modal
        title="编辑步骤"
        open={stepEditModal.visible}
        onOk={handleSaveStep}
        onCancel={() => setStepEditModal({ visible: false, stepId: null, title: '', description: '' })}
      >
        <Form layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item label="步骤名称" required>
            <Input
              value={stepEditModal.title}
              onChange={(e) => setStepEditModal(prev => ({ ...prev, title: e.target.value }))}
              placeholder="请输入步骤名称"
            />
          </Form.Item>
          <Form.Item label="步骤描述">
            <Input.TextArea
              value={stepEditModal.description}
              onChange={(e) => setStepEditModal(prev => ({ ...prev, description: e.target.value }))}
              placeholder="请输入步骤描述（可选）"
              rows={3}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default DragEditor;
