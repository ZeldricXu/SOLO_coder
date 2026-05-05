import React, { useState, useEffect, useRef } from 'react';
import { Card, Spin, message, Button, DatePicker, Space } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import Gantt from 'frappe-gantt';
import 'frappe-gantt/dist/frappe-gantt.css';
import { taskAPI } from '../services/api';
import dayjs from 'dayjs';

const GanttView = () => {
  const [loading, setLoading] = useState(false);
  const [dateRange, setDateRange] = useState([
    dayjs().subtract(1, 'month'),
    dayjs().add(3, 'month')
  ]);
  const ganttRef = useRef(null);
  const ganttInstanceRef = useRef(null);

  const fetchGanttData = async () => {
    setLoading(true);
    try {
      const params = {};
      if (dateRange && dateRange[0]) {
        params.start_date = dateRange[0].format('YYYY-MM-DD');
      }
      if (dateRange && dateRange[1]) {
        params.end_date = dateRange[1].format('YYYY-MM-DD');
      }

      const response = await taskAPI.getGanttData(params);
      const tasks = response.data.data.tasks || [];
      
      renderGantt(tasks);
    } catch (error) {
      message.error('获取甘特图数据失败');
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  const renderGantt = (tasks) => {
    if (!ganttRef.current) return;

    const ganttTasks = tasks.map(task => ({
      id: task.id,
      name: task.name,
      start: task.start || dayjs().format('YYYY-MM-DD'),
      end: task.end || dayjs().add(7, 'day').format('YYYY-MM-DD'),
      progress: task.progress || 0,
      dependencies: task.dependencies || [],
      custom_class: getTaskClass(task.status),
    }));

    if (ganttInstanceRef.current) {
      ganttInstanceRef.current = null;
      ganttRef.current.innerHTML = '';
    }

    ganttInstanceRef.current = new Gantt(ganttRef.current, ganttTasks, {
      on_click: (task) => {
        console.log('点击任务:', task);
      },
      on_date_change: async (task, start, end) => {
        console.log('日期变更:', task, start, end);
      },
      on_progress_change: async (task, progress) => {
        console.log('进度变更:', task, progress);
      },
      on_view_change: (mode) => {
        console.log('视图模式变更:', mode);
      },
      view_mode: 'Day',
      language: 'zh',
    });
  };

  const getTaskClass = (status) => {
    const classMap = {
      'todo': 'task-todo',
      'in_progress': 'task-in-progress',
      'completed': 'task-completed',
      'cancelled': 'task-cancelled',
    };
    return classMap[status] || '';
  };

  useEffect(() => {
    fetchGanttData();
  }, [dateRange]);

  return (
    <div style={{ height: '100%' }}>
      <Card style={{ marginBottom: 16 }}>
        <Space>
          <DatePicker.RangePicker
            value={dateRange}
            onChange={(dates) => setDateRange(dates)}
            style={{ width: 300 }}
          />
          <Button 
            icon={<ReloadOutlined />} 
            onClick={fetchGanttData}
            loading={loading}
          >
            刷新
          </Button>
        </Space>
      </Card>

      <Spin spinning={loading}>
        <Card>
          <div 
            ref={ganttRef} 
            style={{ 
              height: '600px', 
              overflow: 'auto' 
            }}
          />
        </Card>
      </Spin>

      <style>{`
        .task-todo .bar-wrapper .bar {
          background-color: #d9d9d9;
        }
        .task-in-progress .bar-wrapper .bar {
          background-color: #1890ff;
        }
        .task-completed .bar-wrapper .bar {
          background-color: #52c41a;
        }
        .task-cancelled .bar-wrapper .bar {
          background-color: #ff4d4f;
          opacity: 0.6;
        }
        
        .gantt-container .task-list-wrapper .bar-progress {
          background-color: rgba(0, 0, 0, 0.2);
        }
      `}</style>
    </div>
  );
};

export default GanttView;
