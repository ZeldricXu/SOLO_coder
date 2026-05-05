import React from 'react';
import { Card, Tag, Empty, Badge } from 'antd';
import { useDroppable } from '@dnd-kit/core';
import { SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import TaskCard from './TaskCard';

const statusLabels = {
  todo: '待办',
  in_progress: '进行中',
  completed: '已完成',
  cancelled: '已取消'
};

const statusColors = {
  todo: 'default',
  in_progress: 'processing',
  completed: 'success',
  cancelled: 'error'
};

const SortableTaskCard = ({ task, onStatusChange, onEdit }) => {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: task.task_id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <div ref={setNodeRef} style={style} {...attributes} {...listeners}>
      <TaskCard task={task} onStatusChange={onStatusChange} onEdit={onEdit} />
    </div>
  );
};

const TaskColumn = ({ status, tasks, onStatusChange, onEdit }) => {
  const { setNodeRef, isOver } = useDroppable({
    id: `column-${status}`,
  });

  const taskIds = tasks.map(task => task.task_id);

  return (
    <Card
      size="small"
      style={{ 
        flex: 1, 
        margin: '0 8px',
        backgroundColor: isOver ? '#f0f5ff' : '#fafafa',
        transition: 'background-color 0.2s'
      }}
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Tag color={statusColors[status]}>
            {statusLabels[status]}
          </Tag>
          <Badge count={tasks.length} showZero style={{ backgroundColor: '#d9d9d9' }} />
        </div>
      }
    >
      <div ref={setNodeRef} style={{ minHeight: 100 }}>
        {tasks.length === 0 ? (
          <Empty description="暂无任务" style={{ margin: '20px 0' }} />
        ) : (
          <SortableContext items={taskIds} strategy={verticalListSortingStrategy}>
            {tasks.map(task => (
              <SortableTaskCard
                key={task.task_id}
                task={task}
                onStatusChange={onStatusChange}
                onEdit={onEdit}
              />
            ))}
          </SortableContext>
        )}
      </div>
    </Card>
  );
};

export default TaskColumn;
