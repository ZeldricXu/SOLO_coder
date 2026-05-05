import React from 'react';
import { useDrag } from 'react-dnd';
import { Card } from 'antd';

const DraggableComponent = ({ componentType, componentConfig, icon: Icon }) => {
  const [{ isDragging }, drag] = useDrag(() => ({
    type: 'FORM_COMPONENT',
    item: {
      componentType,
      componentConfig,
    },
    collect: (monitor) => ({
      isDragging: monitor.isDragging(),
    }),
  }));

  return (
    <div
      ref={drag}
      style={{
        opacity: isDragging ? 0.5 : 1,
        cursor: 'move',
        marginBottom: 8,
      }}
    >
      <Card
        size="small"
        hoverable
        styles={{
          body: {
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '8px 12px',
          },
        }}
      >
        {Icon && <Icon />}
        <span>{componentConfig.label}</span>
      </Card>
    </div>
  );
};

export default DraggableComponent;
