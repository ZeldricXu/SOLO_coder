import React from 'react';
import { useDrag, useDrop } from 'react-dnd';
import { Card, Button, Space } from 'antd';
import { DeleteOutlined, DragOutlined, SettingOutlined } from '@ant-design/icons';
import { COMPONENT_MAP } from '../componentLibrary';

const CanvasItem = ({
  component,
  index,
  isSelected,
  onSelect,
  onDelete,
  onReorder,
  onEdit,
}) => {
  const [{ isDragging }, drag] = useDrag(() => ({
    type: 'EXISTING_COMPONENT',
    item: {
      componentId: component.component_id,
      component,
      index,
    },
    collect: (monitor) => ({
      isDragging: monitor.isDragging(),
    }),
  }));

  const [{ isOver }, drop] = useDrop(() => ({
    accept: 'EXISTING_COMPONENT',
    hover: (draggedItem) => {
      if (draggedItem.index !== index) {
        onReorder(draggedItem.index, index);
        draggedItem.index = index;
      }
    },
    collect: (monitor) => ({
      isOver: monitor.isOver(),
    }),
  }), [index, onReorder]);

  const Component = COMPONENT_MAP[component.component_type];

  return (
    <div
      ref={(node) => drag(drop(node))}
      style={{
        opacity: isDragging ? 0.5 : 1,
        marginBottom: 12,
      }}
    >
      <Card
        size="small"
        onClick={() => onSelect(component.component_id)}
        style={{
          border: isSelected ? '2px solid #1890ff' : '1px solid #d9d9d9',
          cursor: 'pointer',
        }}
        styles={{
          header: {
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '8px 12px',
            minHeight: 40,
          },
          body: {
            padding: 12,
          },
        }}
        title={
          <Space>
            <DragOutlined style={{ cursor: 'grab', color: '#999' }} />
            <span style={{ fontSize: 12, color: '#666' }}>
              {component.label}
            </span>
          </Space>
        }
        extra={
          <Space size="small">
            <Button
              type="text"
              size="small"
              icon={<SettingOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                onEdit(component.component_id);
              }}
            />
            <Button
              type="text"
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                onDelete(component.component_id);
              }}
            />
          </Space>
        }
      >
        {Component ? (
          <Component
            component={component}
            value={undefined}
            onChange={() => {}}
            error={null}
            disabled={true}
          />
        ) : (
          <div>未知组件类型: {component.component_type}</div>
        )}
      </Card>
    </div>
  );
};

export default CanvasItem;
