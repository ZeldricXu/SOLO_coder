import React from 'react';
import { useDrop } from 'react-dnd';
import { Empty, Card } from 'antd';

const DroppableCanvas = ({
  children,
  onDrop,
  placeholder = '拖拽组件到此处',
}) => {
  const [{ isOver, canDrop }, drop] = useDrop(() => ({
    accept: ['FORM_COMPONENT', 'EXISTING_COMPONENT'],
    drop: (item, monitor) => {
      onDrop(item, monitor);
    },
    collect: (monitor) => ({
      isOver: monitor.isOver(),
      canDrop: monitor.canDrop(),
    }),
  }), [onDrop]);

  const isActive = isOver && canDrop;

  return (
    <div
      ref={drop}
      style={{
        minHeight: 200,
        padding: 16,
        backgroundColor: isActive ? '#e6f7ff' : '#fafafa',
        border: `2px dashed ${isActive ? '#1890ff' : '#d9d9d9'}`,
        borderRadius: 4,
        transition: 'all 0.3s ease',
      }}
    >
      {children || (
        <Empty
          description={placeholder}
          image={Empty.PRESENTED_IMAGE_SIMPLE}
        />
      )}
    </div>
  );
};

export default DroppableCanvas;
