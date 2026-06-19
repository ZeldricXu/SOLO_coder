import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Button,
  Space,
  Typography,
  message,
  Tooltip,
  Popconfirm,
  Switch,
} from 'antd';
import {
  ArrowLeftOutlined,
  EditOutlined,
  PlusOutlined,
  ExportOutlined,
  FilterOutlined,
  SaveOutlined,
  DeleteOutlined,
} from '@ant-design/icons';
import { Responsive, WidthProvider } from 'react-grid-layout';
import 'react-grid-layout/css/styles.css';
import 'react-resizable/css/styles.css';
import { useDashboardStore } from '@/store/dashboard';
import { dashboardService } from '@/services/dashboard';
import ChartWidget from '@/components/ChartWidget';
import AddWidgetModal from '@/components/AddWidgetModal';
import WidgetConfigPanel from '@/components/WidgetConfigPanel';
import GlobalFilterBar from '@/components/GlobalFilterBar';
import type { Widget, WidgetType } from '@/types';

const { Title } = Typography;
const ResponsiveGridLayout = WidthProvider(Responsive);

interface LayoutItem {
  i: string;
  x: number;
  y: number;
  w: number;
  h: number;
  minW?: number;
  minH?: number;
}

const DashboardDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const {
    currentDashboard,
    widgets,
    globalFilters,
    loading,
    loadDashboard,
    addWidget,
    updateWidget,
    removeWidget,
    batchUpdateLayout,
    setGlobalFilters,
    linkWidget,
    unlinkWidget,
  } = useDashboardStore();

  const [isEditMode, setIsEditMode] = useState(false);
  const [addModalVisible, setAddModalVisible] = useState(false);
  const [configPanelVisible, setConfigPanelVisible] = useState(false);
  const [selectedWidget, setSelectedWidget] = useState<Widget | null>(null);
  const [showFilterBar, setShowFilterBar] = useState(true);

  useEffect(() => {
    if (id) {
      loadDashboard(id);
    }
  }, [id, loadDashboard]);

  const layout = useMemo<LayoutItem[]>(() => {
    return widgets.map((widget) => {
      const widgetLayout = widget.layout as Record<string, unknown> | undefined;
      return {
        i: widget.id,
        x: (widgetLayout?.x as number) || 0,
        y: (widgetLayout?.y as number) || 0,
        w: (widgetLayout?.w as number) || 6,
        h: (widgetLayout?.h as number) || 4,
        minW: 2,
        minH: 2,
      };
    });
  }, [widgets]);

  const handleLayoutChange = useCallback(
    (currentLayout: LayoutItem[]) => {
      if (!isEditMode) return;

      const items = currentLayout.map((item) => ({
        widgetId: item.i,
        layout: {
          x: item.x,
          y: item.y,
          w: item.w,
          h: item.h,
        },
      }));

      batchUpdateLayout(items);
    },
    [isEditMode, batchUpdateLayout],
  );

  const handleAddWidget = async (data: {
    type: WidgetType;
    title: string;
    metricId: string | null;
    config: Record<string, unknown>;
  }) => {
    const widgetData = {
      type: data.type,
      title: data.title,
      metricId: data.metricId,
      config: data.config,
      layout: {
        x: 0,
        y: Infinity,
        w: 6,
        h: 4,
      },
      filters: null,
      linkedWidgetIds: [],
    };

    try {
      await addWidget(widgetData);
      message.success('组件添加成功');
      setAddModalVisible(false);
    } catch (err) {
      message.error('添加组件失败');
    }
  };

  const handleWidgetClick = (widget: Widget) => {
    if (isEditMode) {
      setSelectedWidget(widget);
      setConfigPanelVisible(true);
    }
  };

  const handleSaveWidget = (widgetId: string, data: Partial<Widget>) => {
    updateWidget(widgetId, data);
  };

  const handleDeleteWidget = (widgetId: string) => {
    removeWidget(widgetId);
    message.success('删除成功');
  };

  const handleExport = async () => {
    if (!id) return;
    try {
      const res = await dashboardService.export(id);
      const dataStr = JSON.stringify(res.data.data, null, 2);
      const blob = new Blob([dataStr], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `dashboard-${id}.json`;
      a.click();
      URL.revokeObjectURL(url);
      message.success('导出成功');
    } catch (err) {
      message.error('导出失败');
    }
  };

  const handleFilterApply = (filters: Record<string, unknown>) => {
    setGlobalFilters(filters);
  };

  const handleFilterReset = () => {
    setGlobalFilters({});
  };

  const widgetHeightMap: Record<number, number> = {
    2: 120,
    3: 180,
    4: 240,
    5: 300,
    6: 360,
    7: 420,
    8: 480,
  };

  const getWidgetHeight = (h: number) => {
    return widgetHeightMap[h] || h * 60;
  };

  if (loading && !currentDashboard) {
    return (
      <div style={{ textAlign: 'center', padding: '100px 0' }}>
        <div style={{ fontSize: 16, color: '#8c8c8c' }}>加载中...</div>
      </div>
    );
  }

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 16,
          paddingBottom: 12,
          borderBottom: '1px solid #f0f0f0',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/dashboards')}>
            返回
          </Button>
          <Title level={4} style={{ margin: 0 }}>
            {currentDashboard?.name || '看板详情'}
          </Title>
        </div>

        <Space>
          <Tooltip title={showFilterBar ? '隐藏筛选栏' : '显示筛选栏'}>
            <Button
              icon={<FilterOutlined />}
              onClick={() => setShowFilterBar(!showFilterBar)}
              type={showFilterBar ? 'primary' : 'default'}
            />
          </Tooltip>

          <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            编辑模式
            <Switch
              checked={isEditMode}
              onChange={setIsEditMode}
              checkedChildren={<EditOutlined />}
            />
          </span>

          {isEditMode && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setAddModalVisible(true)}>
              添加组件
            </Button>
          )}

          <Button icon={<ExportOutlined />} onClick={handleExport}>
            导出
          </Button>
        </Space>
      </div>

      {showFilterBar && (
        <GlobalFilterBar
          onApply={handleFilterApply}
          onReset={handleFilterReset}
          dimensions={[
            {
              label: '业务线',
              value: 'businessLine',
              options: [
                { label: '全部', value: 'all' },
                { label: '电商业务', value: 'ecommerce' },
                { label: '广告业务', value: 'ads' },
                { label: '游戏业务', value: 'game' },
              ],
            },
            {
              label: '渠道',
              value: 'channel',
              options: [
                { label: '全部', value: 'all' },
                { label: 'APP', value: 'app' },
                { label: 'H5', value: 'h5' },
                { label: '小程序', value: 'miniapp' },
              ],
            },
          ]}
        />
      )}

      <div style={{ flex: 1, minHeight: 0 }}>
        {widgets.length === 0 ? (
          <div
            style={{
              textAlign: 'center',
              padding: '80px 0',
              color: '#8c8c8c',
              border: '2px dashed #d9d9d9',
              borderRadius: 8,
            }}
          >
            <div style={{ fontSize: 48, marginBottom: 16 }}>📊</div>
            <div style={{ fontSize: 16, marginBottom: 8 }}>暂无组件</div>
            <div style={{ fontSize: 13, marginBottom: 24 }}>
              {isEditMode ? '点击右上角"添加组件"按钮开始添加' : '请先进入编辑模式添加组件'}
            </div>
            {!isEditMode && (
              <Button type="primary" icon={<EditOutlined />} onClick={() => setIsEditMode(true)}>
                进入编辑模式
              </Button>
            )}
          </div>
        ) : (
          <ResponsiveGridLayout
            className="layout"
            layouts={{ lg: layout }}
            breakpoints={{ lg: 1200, md: 996, sm: 768, xs: 480, xxs: 0 }}
            cols={{ lg: 24, md: 20, sm: 12, xs: 8, xxs: 4 }}
            rowHeight={60}
            margin={[12, 12]}
            containerPadding={[0, 0]}
            isDraggable={isEditMode}
            isResizable={isEditMode}
            onLayoutChange={(_, allLayouts) => {
              handleLayoutChange(allLayouts.lg as LayoutItem[]);
            }}
          >
            {widgets.map((widget) => {
              const layoutItem = layout.find((l) => l.i === widget.id);
              const height = getWidgetHeight(layoutItem?.h || 4);

              return (
                <div
                  key={widget.id}
                  style={{
                    cursor: isEditMode ? 'move' : 'default',
                  }}
                >
                  <div
                    onClick={() => handleWidgetClick(widget)}
                    style={{
                      height: '100%',
                      border: isEditMode ? '2px dashed #1890ff' : 'none',
                      borderRadius: 4,
                      transition: 'all 0.2s',
                    }}
                  >
                    <ChartWidget
                      type={widget.type}
                      title={widget.title}
                      metricId={widget.metricId}
                      config={widget.config}
                      filters={widget.filters || globalFilters}
                      height={height - 20}
                    />
                  </div>
                  {isEditMode && (
                    <div
                      style={{
                        position: 'absolute',
                        top: 4,
                        right: 4,
                        zIndex: 10,
                        display: 'flex',
                        gap: 4,
                      }}
                    >
                      <Tooltip title="配置">
                        <Button
                          size="small"
                          type="primary"
                          icon={<EditOutlined />}
                          onClick={(e) => {
                            e.stopPropagation();
                            setSelectedWidget(widget);
                            setConfigPanelVisible(true);
                          }}
                        />
                      </Tooltip>
                      <Popconfirm
                        title="确定删除这个组件吗？"
                        onConfirm={(e) => {
                          e?.stopPropagation();
                          handleDeleteWidget(widget.id);
                        }}
                        onClick={(e) => e.stopPropagation()}
                      >
                        <Button
                          size="small"
                          danger
                          icon={<DeleteOutlined />}
                          onClick={(e) => e.stopPropagation()}
                        />
                      </Popconfirm>
                    </div>
                  )}
                </div>
              );
            })}
          </ResponsiveGridLayout>
        )}
      </div>

      <AddWidgetModal
        open={addModalVisible}
        onCancel={() => setAddModalVisible(false)}
        onOk={handleAddWidget}
      />

      <WidgetConfigPanel
        open={configPanelVisible}
        widget={selectedWidget}
        allWidgets={widgets}
        onClose={() => {
          setConfigPanelVisible(false);
          setSelectedWidget(null);
        }}
        onSave={handleSaveWidget}
        onDelete={handleDeleteWidget}
        onLink={linkWidget}
        onUnlink={unlinkWidget}
      />
    </div>
  );
};

export default DashboardDetail;
