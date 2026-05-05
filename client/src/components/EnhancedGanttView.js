import React, { useState, useEffect, useRef, useCallback } from 'react';
import { 
  Card, 
  Spin, 
  message, 
  Button, 
  DatePicker, 
  Space,
  Modal,
  Form,
  Select,
  Alert,
  Tag,
  Tooltip,
  Row,
  Col,
  Typography,
  List,
  Popconfirm
} from 'antd';
import { 
  ReloadOutlined, 
  WarningOutlined, 
  LinkOutlined,
  InfoCircleOutlined
} from '@ant-design/icons';
import Gantt from 'frappe-gantt';
import 'frappe-gantt/dist/frappe-gantt.css';
import { taskAPI } from '../services/api';
import dayjs from 'dayjs';

const { Text, Paragraph } = Typography;

class SmartDependencyLayoutEngine {
  constructor() {
    this.VERTICAL_SPACING = 35;
    this.HORIZONTAL_PADDING = 20;
    this.CURVATURE = 25;
    this.TASK_PADDING = 5;
  }

  calculateOptimalPaths(tasks, taskPositions) {
    const allDependencies = this.collectDependencies(tasks);
    const dependencyGroups = this.groupDependenciesByRow(allDependencies, taskPositions);
    const paths = [];

    dependencyGroups.forEach((group, groupIndex) => {
      const assignedLevels = this.assignLevelsToAvoidOverlap(group, taskPositions);
      
      group.forEach((dep, idx) => {
        const level = assignedLevels.get(`${dep.from}->${dep.to}`) || 0;
        const path = this.calculateBezierPath(
          dep,
          taskPositions,
          level,
          groupIndex
        );
        paths.push({
          ...dep,
          pathData: path.pathData,
          color: path.color,
          fromPos: path.fromPos,
          toPos: path.toPos
        });
      });
    });

    return paths;
  }

  collectDependencies(tasks) {
    const dependencies = [];
    tasks.forEach(task => {
      if (task.dependencies && task.dependencies.length > 0) {
        task.dependencies.forEach(dep => {
          dependencies.push({
            from: dep.prerequisite_task_id,
            to: task.id,
            type: dep.dependency_type || 'finish_to_start',
            lagDays: dep.lag_days || 0
          });
        });
      }
    });
    return dependencies;
  }

  groupDependenciesByRow(dependencies, taskPositions) {
    const rows = new Map();

    dependencies.forEach(dep => {
      const fromPos = taskPositions.get(dep.from);
      const toPos = taskPositions.get(dep.to);
      
      if (!fromPos || !toPos) return;

      const rowKey = Math.min(fromPos.top, toPos.top);
      
      if (!rows.has(rowKey)) {
        rows.set(rowKey, []);
      }
      rows.get(rowKey).push(dep);
    });

    return Array.from(rows.values());
  }

  assignLevelsToAvoidOverlap(dependencies, taskPositions) {
    const assignedLevels = new Map();
    const usedIntervals = [];

    const sortedDeps = [...dependencies].sort((a, b) => {
      const posA = taskPositions.get(a.from);
      const posB = taskPositions.get(b.from);
      if (!posA || !posB) return 0;
      return posA.left - posB.left;
    });

    sortedDeps.forEach(dep => {
      const fromPos = taskPositions.get(dep.from);
      const toPos = taskPositions.get(dep.to);
      
      if (!fromPos || !toPos) {
        assignedLevels.set(`${dep.from}->${dep.to}`, 0);
        return;
      }

      const depInterval = {
        left: Math.min(fromPos.right, toPos.left),
        right: Math.max(fromPos.right, toPos.left)
      };

      let level = 0;
      while (this.intervalOverlaps(depInterval, usedIntervals, level)) {
        level++;
      }

      usedIntervals.push({ ...depInterval, level });
      assignedLevels.set(`${dep.from}->${dep.to}`, level);
    });

    return assignedLevels;
  }

  intervalOverlaps(newInterval, usedIntervals, level) {
    return usedIntervals.some(used => {
      if (used.level !== level) return false;
      return !(newInterval.right < used.left || newInterval.left > used.right);
    });
  }

  calculateBezierPath(dep, taskPositions, level, groupIndex) {
    const fromPos = taskPositions.get(dep.from);
    const toPos = taskPositions.get(dep.to);

    if (!fromPos || !toPos) {
      return null;
    }

    const { startX, startY, endX, endY } = this.getConnectionPoints(
      fromPos,
      toPos,
      dep.type
    );

    const verticalOffset = level * this.VERTICAL_SPACING;
    
    const colors = [
      '#1890ff',
      '#52c41a',
      '#faad14',
      '#722ed1',
      '#eb2f96',
      '#13c2c2',
      '#fa8c16'
    ];
    const color = colors[(groupIndex + level) % colors.length];

    const pathData = this.createSmartBezierPath(
      startX, startY,
      endX, endY,
      verticalOffset,
      dep.type,
      fromPos,
      toPos
    );

    return {
      pathData,
      color,
      fromPos: { x: startX, y: startY },
      toPos: { x: endX, y: endY }
    };
  }

  getConnectionPoints(fromPos, toPos, type) {
    let startX, startY, endX, endY;

    switch (type) {
      case 'finish_to_start':
        startX = fromPos.right;
        startY = fromPos.top + fromPos.height / 2;
        endX = toPos.left;
        endY = toPos.top + toPos.height / 2;
        break;
      case 'start_to_start':
        startX = fromPos.left;
        startY = fromPos.top + fromPos.height / 2;
        endX = toPos.left;
        endY = toPos.top + toPos.height / 2;
        break;
      case 'finish_to_finish':
        startX = fromPos.right;
        startY = fromPos.top + fromPos.height / 2;
        endX = toPos.right;
        endY = toPos.top + toPos.height / 2;
        break;
      case 'start_to_finish':
        startX = fromPos.left;
        startY = fromPos.top + fromPos.height / 2;
        endX = toPos.right;
        endY = toPos.top + toPos.height / 2;
        break;
      default:
        startX = fromPos.right;
        startY = fromPos.top + fromPos.height / 2;
        endX = toPos.left;
        endY = toPos.top + toPos.height / 2;
    }

    return { startX, startY, endX, endY };
  }

  createSmartBezierPath(startX, startY, endX, endY, verticalOffset, type, fromPos, toPos) {
    const isForwardDirection = startX < endX;
    const horizontalDistance = Math.abs(endX - startX);
    
    if (horizontalDistance < this.HORIZONTAL_PADDING * 2 && Math.abs(endY - startY) < this.VERTICAL_SPACING) {
      return this.createCrossOverPath(startX, startY, endX, endY, verticalOffset, type);
    }

    const baseY = Math.max(startY, endY) + this.TASK_PADDING;
    const midY = baseY + verticalOffset;

    let controlPoint1X, controlPoint1Y;
    let controlPoint2X, controlPoint2Y;
    let midX;

    switch (type) {
      case 'finish_to_start':
        midX = (startX + endX) / 2;
        controlPoint1X = startX + this.CURVATURE;
        controlPoint1Y = startY;
        controlPoint2X = endX - this.CURVATURE;
        controlPoint2Y = endY;
        
        if (verticalOffset > 0 || !isForwardDirection) {
          return `M ${startX} ${startY} 
                  C ${startX + this.CURVATURE} ${startY}, 
                    ${startX + this.CURVATURE} ${midY - 10}, 
                    ${midX} ${midY}
                  C ${endX - this.CURVATURE} ${midY - 10}, 
                    ${endX - this.CURVATURE} ${endY}, 
                    ${endX - 5} ${endY}`;
        }
        break;

      case 'start_to_start':
        const leftmostX = Math.min(startX, endX);
        const startOutX = leftmostX - this.HORIZONTAL_PADDING;
        midX = startOutX;
        const topY = Math.min(startY, endY) - verticalOffset - this.VERTICAL_SPACING;
        
        return `M ${startX} ${startY}
                C ${startX - this.CURVATURE} ${startY},
                  ${startOutX} ${startY - this.CURVATURE},
                  ${startOutX} ${topY}
                L ${startOutX} ${topY}
                C ${startOutX} ${endY - this.CURVATURE},
                  ${endX - this.CURVATURE} ${endY},
                  ${endX + 5} ${endY}`;

      case 'finish_to_finish':
        const rightmostX = Math.max(startX, endX);
        const endOutX = rightmostX + this.HORIZONTAL_PADDING;
        const bottomY = Math.max(startY, endY) + verticalOffset + this.VERTICAL_SPACING;
        
        return `M ${startX} ${startY}
                C ${startX + this.CURVATURE} ${startY},
                  ${endOutX} ${startY + this.CURVATURE},
                  ${endOutX} ${bottomY}
                L ${endOutX} ${bottomY}
                C ${endOutX} ${endY + this.CURVATURE},
                  ${endX + this.CURVATURE} ${endY},
                  ${endX - 5} ${endY}`;

      case 'start_to_finish':
        midX = (startX + endX) / 2;
        const middleY = Math.min(startY, endY) - verticalOffset - this.VERTICAL_SPACING;
        
        return `M ${startX} ${startY}
                C ${startX - this.CURVATURE} ${startY},
                  ${midX - this.CURVATURE} ${middleY},
                  ${midX} ${middleY}
                C ${midX + this.CURVATURE} ${middleY},
                  ${endX + this.CURVATURE} ${endY},
                  ${endX - 5} ${endY}`;

      default:
        midX = (startX + endX) / 2;
    }

    return `M ${startX} ${startY} 
            C ${startX + this.CURVATURE} ${startY}, 
              ${startX + this.CURVATURE} ${midY - 10}, 
              ${midX} ${midY}
            C ${endX - this.CURVATURE} ${midY - 10}, 
              ${endX - this.CURVATURE} ${endY}, 
              ${endX - 5} ${endY}`;
  }

  createCrossOverPath(startX, startY, endX, endY, verticalOffset, type) {
    const offset = this.VERTICAL_SPACING + verticalOffset;
    const loopRadius = this.CURVATURE;
    
    if (startY < endY) {
      const loopY = startY - offset;
      return `M ${startX} ${startY}
              C ${startX} ${startY - loopRadius},
                ${startX + loopRadius} ${loopY},
                ${startX + this.HORIZONTAL_PADDING} ${loopY}
              L ${endX + this.HORIZONTAL_PADDING} ${loopY}
              C ${endX + loopRadius + this.HORIZONTAL_PADDING} ${loopY},
                ${endX + this.HORIZONTAL_PADDING * 2} ${endY - loopRadius},
                ${endX - 5} ${endY}`;
    } else {
      const loopY = startY + offset;
      return `M ${startX} ${startY}
              C ${startX} ${startY + loopRadius},
                ${startX + loopRadius} ${loopY},
                ${startX + this.HORIZONTAL_PADDING} ${loopY}
              L ${endX + this.HORIZONTAL_PADDING} ${loopY}
              C ${endX + loopRadius + this.HORIZONTAL_PADDING} ${loopY},
                ${endX + this.HORIZONTAL_PADDING * 2} ${endY + loopRadius},
                ${endX - 5} ${endY}`;
    }
  }
}

const layoutEngine = new SmartDependencyLayoutEngine();

const EnhancedGanttView = () => {
  const [loading, setLoading] = useState(false);
  const [tasks, setTasks] = useState([]);
  const [dependencyWarnings, setDependencyWarnings] = useState([]);
  const [ganttData, setGanttData] = useState(null);
  const [dateRange, setDateRange] = useState([
    dayjs().subtract(1, 'month'),
    dayjs().add(3, 'month')
  ]);
  const [dependencyModalVisible, setDependencyModalVisible] = useState(false);
  const [selectedTask, setSelectedTask] = useState(null);
  const [dependencyForm] = Form.useForm();
  
  const ganttRef = useRef(null);
  const ganttInstanceRef = useRef(null);
  const svgOverlayRef = useRef(null);
  const containerRef = useRef(null);

  const fetchGanttData = useCallback(async () => {
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
      const data = response.data.data;
      
      setGanttData(data);
      setTasks(data.tasks || []);
      setDependencyWarnings(data.dependency_warnings || []);
      
      renderGantt(data.tasks || []);
    } catch (error) {
      message.error('获取甘特图数据失败');
      console.error(error);
    } finally {
      setLoading(false);
    }
  }, [dateRange]);

  const renderGantt = (tasks) => {
    if (!ganttRef.current) return;

    const ganttTasks = tasks.map(task => ({
      id: task.id,
      name: task.name,
      start: task.start || dayjs().format('YYYY-MM-DD'),
      end: task.end || dayjs().add(7, 'day').format('YYYY-MM-DD'),
      progress: task.progress || 0,
      dependencies: task.dependency_ids || [],
      custom_class: getTaskClass(task.status),
    }));

    if (ganttInstanceRef.current) {
      ganttInstanceRef.current = null;
      ganttRef.current.innerHTML = '';
    }

    ganttInstanceRef.current = new Gantt(ganttRef.current, ganttTasks, {
      on_click: (task) => {
        const taskData = tasks.find(t => t.id === task.id);
        setSelectedTask(taskData || task);
      },
      on_date_change: async (task, start, end) => {
        console.log('日期变更:', task, start, end);
        checkDependencyViolations(task.id, start, end);
      },
      on_progress_change: async (task, progress) => {
        console.log('进度变更:', task, progress);
      },
      on_view_change: (mode) => {
        console.log('视图模式变更:', mode);
        setTimeout(() => renderDependencyLines(tasks), 100);
      },
      view_mode: 'Day',
      language: 'zh',
    });

    setTimeout(() => renderDependencyLines(tasks), 200);
  };

  const checkDependencyViolations = (taskId, newStart, newEnd) => {
    if (!ganttData || !ganttData.tasks) return;

    const task = ganttData.tasks.find(t => t.id === taskId);
    if (!task || !task.dependencies || task.dependencies.length === 0) return;

    const violations = [];

    for (const dep of task.dependencies) {
      const prereqTask = ganttData.tasks.find(t => t.id === dep.prerequisite_task_id);
      if (!prereqTask) continue;

      if (prereqTask.status !== 'completed') {
        const prereqEnd = new Date(prereqTask.end);
        const taskStart = new Date(newStart);

        if (taskStart < prereqEnd) {
          violations.push({
            task_id: taskId,
            task_name: task.name,
            prerequisite_task_id: prereqTask.id,
            prerequisite_task_name: prereqTask.name,
            message: `任务"${task.name}"的计划开始时间(${newStart})早于前置任务"${prereqTask.name}"的计划完成时间(${prereqTask.end})，但前置任务尚未完成。建议调整时间或先完成前置任务。`
          });
        }
      }
    }

    if (violations.length > 0) {
      message.warning({
        content: (
          <div>
            <p><strong>检测到潜在的依赖关系冲突：</strong></p>
            {violations.map((v, i) => (
              <p key={i} style={{ margin: '8px 0', padding: '8px', background: '#fffbe6', borderRadius: '4px' }}>
                {v.message}
              </p>
            ))}
          </div>
        ),
        duration: 10,
      });
    }
  };

  const renderDependencyLines = (tasks) => {
    if (!ganttRef.current || !containerRef.current) return;

    if (svgOverlayRef.current) {
      svgOverlayRef.current.remove();
    }

    const ganttContainer = ganttRef.current.querySelector('.gantt-container');
    if (!ganttContainer) return;

    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svgOverlayRef.current = svg;
    svg.style.position = 'absolute';
    svg.style.top = '0';
    svg.style.left = '0';
    svg.style.width = '100%';
    svg.style.height = '100%';
    svg.style.pointerEvents = 'none';
    svg.style.zIndex = '10';

    ganttContainer.style.position = 'relative';
    ganttContainer.appendChild(svg);

    const defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
    svg.appendChild(defs);

    const createArrowMarker = (markerId, color) => {
      const marker = document.createElementNS('http://www.w3.org/2000/svg', 'marker');
      marker.setAttribute('id', markerId);
      marker.setAttribute('viewBox', '0 0 10 10');
      marker.setAttribute('refX', '9');
      marker.setAttribute('refY', '5');
      marker.setAttribute('markerWidth', '6');
      marker.setAttribute('markerHeight', '6');
      marker.setAttribute('orient', 'auto');

      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', 'M 0 0 L 10 5 L 0 10 z');
      path.setAttribute('fill', color);
      marker.appendChild(path);
      defs.appendChild(marker);
      
      return markerId;
    };

    const taskElements = ganttRef.current.querySelectorAll('.bar-wrapper');
    const taskPositions = new Map();

    taskElements.forEach((el, index) => {
      const bar = el.querySelector('.bar');
      if (!bar) return;

      const rect = bar.getBoundingClientRect();
      const containerRect = ganttContainer.getBoundingClientRect();
      
      const id = tasks[index]?.id || `task_${index}`;
      taskPositions.set(id, {
        left: rect.left - containerRect.left,
        top: rect.top - containerRect.top,
        width: rect.width,
        height: rect.height,
        right: rect.right - containerRect.left,
        bottom: rect.bottom - containerRect.top,
      });
    });

    const paths = layoutEngine.calculateOptimalPaths(tasks, taskPositions);

    paths.forEach((pathInfo, index) => {
      if (!pathInfo || !pathInfo.pathData) return;

      const markerId = `arrow-smart-${index}-${Date.now()}`;
      createArrowMarker(markerId, pathInfo.color);

      const pathEl = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      pathEl.setAttribute('d', pathInfo.pathData);
      pathEl.setAttribute('stroke', pathInfo.color);
      pathEl.setAttribute('stroke-width', '2');
      pathEl.setAttribute('fill', 'none');
      pathEl.setAttribute('marker-end', `url(#${markerId})`);
      pathEl.style.opacity = '0.85';
      pathEl.style.transition = 'stroke-opacity 0.3s ease';
      
      pathEl.dataset.from = pathInfo.from;
      pathEl.dataset.to = pathInfo.to;
      pathEl.dataset.type = pathInfo.type;

      svg.appendChild(pathEl);
    });

    const hasDependencies = tasks.some(t => t.dependencies && t.dependencies.length > 0);
    if (hasDependencies) {
      addDependencyLegend(svg, ganttContainer);
    }
  };

  const addDependencyLegend = (svg, container) => {
    const legendGroup = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    legendGroup.setAttribute('transform', 'translate(10, 10)');
    legendGroup.style.opacity = '0.7';

    svg.appendChild(legendGroup);
  };

  const createDependencyArrow = (fromPos, toPos, dependencyType, svg) => {
    const defs = svg.querySelector('defs') || document.createElementNS('http://www.w3.org/2000/svg', 'defs');
    if (!svg.querySelector('defs')) {
      svg.appendChild(defs);
    }

    const markerId = `arrow-${Date.now()}`;
    const marker = document.createElementNS('http://www.w3.org/2000/svg', 'marker');
    marker.setAttribute('id', markerId);
    marker.setAttribute('viewBox', '0 0 10 10');
    marker.setAttribute('refX', '9');
    marker.setAttribute('refY', '5');
    marker.setAttribute('markerWidth', '6');
    marker.setAttribute('markerHeight', '6');
    marker.setAttribute('orient', 'auto');

    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', 'M 0 0 L 10 5 L 0 10 z');
    path.setAttribute('fill', '#1890ff');
    marker.appendChild(path);
    defs.appendChild(marker);

    let startX, startY, endX, endY;

    switch (dependencyType) {
      case 'finish_to_start':
        startX = fromPos.right;
        startY = fromPos.top + fromPos.height / 2;
        endX = toPos.left;
        endY = toPos.top + toPos.height / 2;
        break;
      case 'start_to_start':
        startX = fromPos.left;
        startY = fromPos.top + fromPos.height / 2;
        endX = toPos.left;
        endY = toPos.top + toPos.height / 2;
        break;
      case 'finish_to_finish':
        startX = fromPos.right;
        startY = fromPos.top + fromPos.height / 2;
        endX = toPos.right;
        endY = toPos.top + toPos.height / 2;
        break;
      case 'start_to_finish':
        startX = fromPos.left;
        startY = fromPos.top + fromPos.height / 2;
        endX = toPos.right;
        endY = toPos.top + toPos.height / 2;
        break;
      default:
        startX = fromPos.right;
        startY = fromPos.top + fromPos.height / 2;
        endX = toPos.left;
        endY = toPos.top + toPos.height / 2;
    }

    const midX = (startX + endX) / 2;
    const midY = Math.max(startY, endY) + 30;

    const pathD = `M ${startX} ${startY} 
                   C ${startX + 20} ${startY}, ${startX + 20} ${midY - 10}, ${midX} ${midY}
                   C ${endX - 20} ${midY - 10}, ${endX - 20} ${endY}, ${endX - 5} ${endY}`;

    const pathEl = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    pathEl.setAttribute('d', pathD);
    pathEl.setAttribute('stroke', '#1890ff');
    pathEl.setAttribute('stroke-width', '2');
    pathEl.setAttribute('fill', 'none');
    pathEl.setAttribute('marker-end', `url(#${markerId})`);
    pathEl.style.opacity = '0.7';

    svg.appendChild(pathEl);

    return pathEl;
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

  const handleAddDependency = async (values) => {
    try {
      await taskAPI.addDependency(
        selectedTask.id,
        values.prerequisite_task_id,
        values.dependency_type,
        values.lag_days || 0
      );
      message.success('依赖关系创建成功');
      setDependencyModalVisible(false);
      dependencyForm.resetFields();
      fetchGanttData();
    } catch (error) {
      message.error(error.response?.data?.message || '创建依赖关系失败');
    }
  };

  const handleRemoveDependency = async (prerequisiteTaskId) => {
    try {
      await taskAPI.removeDependency(selectedTask.id, prerequisiteTaskId);
      message.success('依赖关系已删除');
      fetchGanttData();
    } catch (error) {
      message.error(error.response?.data?.message || '删除依赖关系失败');
    }
  };

  useEffect(() => {
    fetchGanttData();
  }, [fetchGanttData]);

  const availableTasksForDependency = tasks.filter(t => 
    t.id !== selectedTask?.id && 
    !selectedTask?.dependencies?.some(d => d.prerequisite_task_id === t.id)
  );

  const dependencyTypeLabels = {
    'finish_to_start': '完成到开始 (FS)',
    'start_to_start': '开始到开始 (SS)',
    'finish_to_finish': '完成到完成 (FF)',
    'start_to_finish': '开始到完成 (SF)'
  };

  return (
    <div ref={containerRef} style={{ height: '100%' }}>
      {dependencyWarnings.length > 0 && (
        <Card style={{ marginBottom: 16, borderLeft: '4px solid #faad14' }}>
          <Alert
            message="依赖关系警告"
            description={
              <div>
                {dependencyWarnings.map((warning, index) => (
                  <div key={index} style={{ marginBottom: 8 }}>
                    <Tag color="warning">{warning.warning_type}</Tag>
                    <Text>{warning.message}</Text>
                  </div>
                ))}
              </div>
            }
            type="warning"
            showIcon
            icon={<WarningOutlined />}
          />
        </Card>
      )}

      <Card style={{ marginBottom: 16 }}>
        <Row justify="space-between" align="middle">
          <Col>
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
          </Col>
          <Col>
            <Space>
              <Tag color="blue">
                <LinkOutlined /> 智能连接线（自动避障）
              </Tag>
              <Tooltip title="智能布局算法自动计算连接线路径，避免交叉重叠。不同颜色表示不同层级的连接线。">
                <Button type="text" icon={<InfoCircleOutlined />} />
              </Tooltip>
            </Space>
          </Col>
        </Row>
      </Card>

      {selectedTask && (
        <Card style={{ marginBottom: 16 }}>
          <Row justify="space-between" align="middle">
            <Col>
              <Space>
                <Text strong>选中任务: </Text>
                <Tag color={selectedTask.status === 'completed' ? 'success' : selectedTask.status === 'in_progress' ? 'processing' : 'default'}>
                  {selectedTask.name}
                </Tag>
                {selectedTask.dependencies && selectedTask.dependencies.length > 0 && (
                  <Tag color="blue">
                    <LinkOutlined /> {selectedTask.dependencies.length} 个依赖
                  </Tag>
                )}
              </Space>
            </Col>
            <Col>
              <Space>
                <Button 
                  icon={<LinkOutlined />}
                  onClick={() => setDependencyModalVisible(true)}
                >
                  添加依赖
                </Button>
              </Space>
            </Col>
          </Row>

          {selectedTask.dependencies && selectedTask.dependencies.length > 0 && (
            <div style={{ marginTop: 16, paddingTop: 16, borderTop: '1px solid #f0f0f0' }}>
              <Text strong style={{ marginBottom: 8, display: 'block' }}>前置任务依赖：</Text>
              <List
                size="small"
                dataSource={selectedTask.dependencies}
                renderItem={(dep) => {
                  const prereqTask = tasks.find(t => t.id === dep.prerequisite_task_id);
                  return (
                    <List.Item
                      actions={[
                        <Popconfirm
                          title="确定要删除此依赖关系吗？"
                          onConfirm={() => handleRemoveDependency(dep.prerequisite_task_id)}
                          okText="确定"
                          cancelText="取消"
                        >
                          <Button type="text" danger size="small">删除</Button>
                        </Popconfirm>
                      ]}
                    >
                      <List.Item.Meta
                        title={
                          <Space>
                            <LinkOutlined style={{ color: '#1890ff' }} />
                            <Text>{prereqTask?.name || dep.prerequisite_task_id}</Text>
                            <Tag size="small">{dependencyTypeLabels[dep.dependency_type]}</Tag>
                            {prereqTask?.status === 'completed' ? (
                              <Tag color="success" size="small">已完成</Tag>
                            ) : (
                              <Tag color="warning" size="small">未完成</Tag>
                            )}
                          </Space>
                        }
                        description={
                          dep.lag_days > 0 ? `延迟天数: ${dep.lag_days} 天` : null
                        }
                      />
                    </List.Item>
                  );
                }}
              />
            </div>
          )}
        </Card>
      )}

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

      <Modal
        title="添加任务依赖关系"
        open={dependencyModalVisible}
        onCancel={() => {
          setDependencyModalVisible(false);
          dependencyForm.resetFields();
        }}
        onOk={() => dependencyForm.submit()}
        okText="创建"
        cancelText="取消"
      >
        <Alert
          message="智能连接线布局"
          description="创建依赖关系后，系统将自动计算最优的连接线路径，避免与其他连接线和任务栏交叉重叠。"
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
        />

        <Form
          form={dependencyForm}
          layout="vertical"
          onFinish={handleAddDependency}
        >
          <Form.Item
            name="prerequisite_task_id"
            label="前置任务"
            rules={[{ required: true, message: '请选择前置任务' }]}
          >
            <Select
              placeholder="选择前置任务"
              showSearch
              optionFilterProp="children"
              filterOption={(input, option) =>
                option.children.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }
            >
              {availableTasksForDependency.map(task => (
                <Select.Option key={task.id} value={task.id}>
                  {task.name}
                  <Tag 
                    color={task.status === 'completed' ? 'success' : task.status === 'in_progress' ? 'processing' : 'default'}
                    style={{ marginLeft: 8 }}
                  >
                    {task.status === 'completed' ? '已完成' : task.status === 'in_progress' ? '进行中' : '待办'}
                  </Tag>
                </Select.Option>
              ))}
            </Select>
          </Form.Item>

          <Form.Item
            name="dependency_type"
            label="依赖类型"
            initialValue="finish_to_start"
          >
            <Select>
              <Select.Option value="finish_to_start">
                <div>
                  <Text strong>完成到开始 (FS)</Text>
                  <br />
                  <Text type="secondary">前置任务完成后，当前任务才能开始（最常用）</Text>
                </div>
              </Select.Option>
              <Select.Option value="start_to_start">
                <div>
                  <Text strong>开始到开始 (SS)</Text>
                  <br />
                  <Text type="secondary">前置任务开始后，当前任务才能开始</Text>
                </div>
              </Select.Option>
              <Select.Option value="finish_to_finish">
                <div>
                  <Text strong>完成到完成 (FF)</Text>
                  <br />
                  <Text type="secondary">前置任务完成后，当前任务才能完成</Text>
                </div>
              </Select.Option>
              <Select.Option value="start_to_finish">
                <div>
                  <Text strong>开始到完成 (SF)</Text>
                  <br />
                  <Text type="secondary">前置任务开始后，当前任务才能完成</Text>
                </div>
              </Select.Option>
            </Select>
          </Form.Item>

          <Form.Item
            name="lag_days"
            label="延迟天数"
            initialValue={0}
          >
            <Select>
              <Select.Option value={0}>无延迟</Select.Option>
              <Select.Option value={1}>1 天</Select.Option>
              <Select.Option value={2}>2 天</Select.Option>
              <Select.Option value={3}>3 天</Select.Option>
              <Select.Option value={5}>5 天</Select.Option>
              <Select.Option value={7}>7 天</Select.Option>
            </Select>
          </Form.Item>
        </Form>
      </Modal>

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
        
        .gantt-container .bar-wrapper {
          cursor: pointer;
        }
        
        .gantt-container .bar-wrapper:hover .bar {
          filter: brightness(1.1);
        }

        .gantt-container {
          position: relative;
        }

        .gantt-container svg path {
          pointer-events: stroke;
          cursor: pointer;
        }

        .gantt-container svg path:hover {
          stroke-width: 3 !important;
          opacity: 1 !important;
        }
      `}</style>
    </div>
  );
};

export default EnhancedGanttView;
