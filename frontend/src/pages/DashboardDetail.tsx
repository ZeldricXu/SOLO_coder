import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import {
  Card,
  Button,
  Space,
  Modal,
  Form,
  Input,
  Select,
  DatePicker,
  message,
  Dropdown,
  MenuProps,
  Popconfirm,
  Tag,
  Row,
  Col,
  Statistic,
} from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  SettingOutlined,
  LinkOutlined,
  DownloadOutlined,
  UploadOutlined,
  ReloadOutlined,
  ArrowLeftOutlined,
  BarChartOutlined,
  LineChartOutlined,
  PieChartOutlined,
  TableOutlined,
  FundOutlined,
  FilterOutlined,
} from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';
import { Responsive, WidthProvider, Layout } from 'react-grid-layout';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import dayjs, { Dayjs } from 'dayjs';
import 'react-grid-layout/css/styles.css';
import 'react-grid-layout/css/styles.css';

import { useDashboardStore } from '@/store/dashboard';
import { useAuthStore } from '@/store/auth';
import { realtimeService } from '@/services/realtime';
import { dashboardService } from '@/services/dashboard';
import { metricService } from '@/services/metric';
import { tenantService } from '@/services/tenant';
import type { Widget, WidgetType, Metric, BusinessLine } from '@/types';

const ResponsiveGridLayout = WidthProvider(Responsive);
const { RangePicker } = DatePicker;
const { TextArea } = Input;
const { Option } = Select;

interface WidgetData {
  [widgetId: string]: Record<string, unknown>[];
}

interface FilterState {
  timeRange: [Dayjs, Dayjs];
  businessLineId: string;
  [key: string]: unknown;
}

const WIDGET_TYPE_CONFIG: Record<WidgetType, { label: string; icon: React.ReactNode }> = {
  LINE_CHART: { label: '折线图', icon: <LineChartOutlined /> },
  BAR_CHART: { label: '柱状图', icon: <BarChartOutlined /> },
  PIE_CHART: { label: '饼图', icon: <PieChartOutlined /> },
  TABLE: { label: '表格', icon: <TableOutlined /> },
  NUMBER_CARD: { label: '数字卡片', icon: <FundOutlined /> },
  FUNNEL: { label: '漏斗图', icon: <FilterOutlined /> },
};

const DashboardDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { currentDashboard, widgets, globalFilters, loading, loadDashboard, setGlobalFilters, addWidget, updateWidget, removeWidget, batchUpdateLayout, linkWidget, unlinkWidget } = useDashboardStore();
  const { token } = useAuthStore();

  const [widgetData, setWidgetData] = useState<WidgetData>({});
  const [metrics, setMetrics] = useState<Metric[]>([]);
  const [businessLines, setBusinessLines] = useState<BusinessLine[]>([]);
  const [addWidgetVisible, setAddWidgetVisible] = useState(false);
  const [editWidgetVisible, setEditWidgetVisible] = useState(false);
  const [editingWidget, setEditingWidget] = useState<Widget | null>(null);
  const [filterState, setFilterState] = useState<FilterState>({
    timeRange: [dayjs().subtract(7, 'day'), dayjs()],
    businessLineId: '',
  });
  const [linkedFilters, setLinkedFilters] = useState<Record<string, Record<string, unknown>>>({});
  const [form] = Form.useForm();
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (id) {
      loadDashboard(id);
    }
    loadMetrics();
    loadBusinessLines();
  }, [id]);

  useEffect(() => {
    if (token) {
      realtimeService.connect(token);
    }
    return () => {
      if (id) {
        realtimeService.unsubscribeDashboard(id);
      }
    };
  }, [token, id]);

  useEffect(() => {
    if (!id) return;

    realtimeService.subscribeDashboard(id);

    const unsubDashboard = realtimeService.onDashboardUpdate((data) => {
      console.log('[Realtime] dashboard update:', data);
      loadDashboard(id);
    });

    const unsubMetric = realtimeService.onMetricUpdate((data: unknown) => {
      const payload = data as { widgetId: string; data: Record<string, unknown>[] };
      console.log('[Realtime] metric update:', payload);
      if (payload.widgetId) {
        setWidgetData((prev) => ({
          ...prev,
          [payload.widgetId]: payload.data,
        }));
      }
    });

    const unsubFilter = realtimeService.onFilterUpdate((data: unknown) => {
      const payload = data as { widgetId: string; filters: Record<string, unknown> };
      console.log('[Realtime] filter update:', payload);
      setLinkedFilters((prev) => ({
        ...prev,
        [payload.widgetId]: payload.filters,
      }));
      const widget = widgets.find((w) => w.id === payload.widgetId);
      if (widget?.linkedWidgetIds?.length) {
        widget.linkedWidgetIds.forEach((linkedId) => {
          fetchWidgetData(linkedId, { ...filterState, ...payload.filters });
        });
      }
    });

    return () => {
      unsubDashboard();
      unsubMetric();
      unsubFilter();
    };
  }, [id, widgets, filterState]);

  useEffect(() => {
    widgets.forEach((widget) => {
      fetchWidgetData(widget.id, filterState);
    });
  }, [widgets.length]);

  const loadMetrics = async () => {
    try {
      const res = await metricService.list();
      setMetrics(res.data.data);
    } catch (err) {
      console.error('加载指标失败', err);
    }
  };

  const loadBusinessLines = async () => {
    try {
      const res = await tenantService.listBusinessLines('default');
      setBusinessLines(res.data.data);
    } catch (err) {
      console.error('加载业务线失败', err);
    }
  };

  const fetchWidgetData = async (widgetId: string, filters?: Partial<FilterState>) => {
    const widget = widgets.find((w) => w.id === widgetId);
    if (!widget?.metricId) return;

    try {
      const mergedFilters = {
        ...filterState,
        ...filters,
        ...linkedFilters[widgetId],
      };
      const params = {
        startTime: mergedFilters.timeRange?.[0]?.toISOString(),
        endTime: mergedFilters.timeRange?.[1]?.toISOString(),
        businessLineId: mergedFilters.businessLineId,
        ...(widget.filters as Record<string, unknown>),
      };
      const res = await metricService.execute(widget.metricId, params);
      setWidgetData((prev) => ({
        ...prev,
        [widgetId]: res.data.data,
      }));
    } catch (err) {
      console.error(`加载组件 ${widgetId} 数据失败`, err);
    }
  };

  const handleLayoutChange = (layouts: { [key: string]: Layout[] }) => {
    const items = layouts.lg.map((layout) => ({
      widgetId: layout.i,
      layout: {
        x: layout.x,
        y: layout.y,
        w: layout.w,
        h: layout.h,
        minW: layout.minW,
        maxW: layout.maxW,
        minH: layout.minH,
        maxH: layout.maxH,
      },
    }));
    batchUpdateLayout(items);
  };

  const handleAddWidget = () => {
    setEditingWidget(null);
    form.resetFields();
    setAddWidgetVisible(true);
  };

  const handleEditWidget = (widget: Widget) => {
    setEditingWidget(widget);
    form.setFieldsValue({
      ...widget,
      config: JSON.stringify(widget.config, null, 2),
    });
    setEditWidgetVisible(true);
  };

  const handleSaveWidget = async () => {
    try {
      const values = await form.validateFields();
      const config = values.config ? JSON.parse(values.config) : {};

      if (editingWidget) {
        await updateWidget(editingWidget.id, { ...values, config });
        message.success('更新成功');
        setEditWidgetVisible(false);
      } else {
        const layout = { x: 0, y: 0, w: 6, h: 4 };
        await addWidget({ ...values, config, layout });
        message.success('添加成功');
        setAddWidgetVisible(false);
      }
    } catch (err) {
      message.error('保存失败');
    }
  };

  const handleDeleteWidget = async (widgetId: string) => {
    try {
      await removeWidget(widgetId);
      message.success('删除成功');
    } catch (err) {
      message.error('删除失败');
    }
  };

  const handleFilterChange = (newFilters: Partial<FilterState>) => {
    const updated = { ...filterState, ...newFilters };
    setFilterState(updated);
    setGlobalFilters(updated as unknown as Record<string, unknown>);
    widgets.forEach((widget) => fetchWidgetData(widget.id, updated));
  };

  const handleWidgetClick = (widget: Widget, data: Record<string, unknown>) => {
    if (widget.linkedWidgetIds?.length) {
      const filters = { dimension: data.dimension, value: data.value };
      realtimeService.emitFilterUpdate(id!, widget.id, filters, widget.linkedWidgetIds);
      setLinkedFilters((prev) => ({
        ...prev,
        [widget.id]: filters,
      }));
      widget.linkedWidgetIds.forEach((linkedId) => {
        fetchWidgetData(linkedId, { ...filterState, ...filters });
      });
    }
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
      a.download = `${currentDashboard?.name}-${dayjs().format('YYYYMMDD')}.json`;
      a.click();
      URL.revokeObjectURL(url);
      message.success('导出成功');
    } catch (err) {
      message.error('导出失败');
    }
  };

  const handleImportClick = () => {
    fileInputRef.current?.click();
  };

  const handleImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !id) return;

    const reader = new FileReader();
    reader.onload = async (event) => {
      try {
        const data = JSON.parse(event.target?.result as string);
        await dashboardService.import(data);
        message.success('导入成功');
        loadDashboard(id);
      } catch (err) {
        message.error('导入失败');
      }
    };
    reader.readAsText(file);
    e.target.value = '';
  };

  const handleLinkWidget = (widgetId: string, targetWidgetId: string) => {
    linkWidget(widgetId, targetWidgetId);
  };

  const handleUnlinkWidget = (widgetId: string, targetWidgetId: string) => {
    unlinkWidget(widgetId, targetWidgetId);
  };

  const gridLayout = useMemo(() => {
    return widgets.map((widget) => {
      const layout = widget.layout as Record<string, unknown>;
      return {
        i: widget.id,
        x: (layout.x as number) || 0,
        y: (layout.y as number) || 0,
        w: (layout.w as number) || 6,
        h: (layout.h as number) || 4,
        minW: 2,
        maxW: 24,
        minH: 2,
        maxH: 24,
      };
    });
  }, [widgets]);

  const getWidgetMenu = (widget: Widget): MenuProps => {
    const otherWidgets = widgets.filter((w) => w.id !== widget.id);
    const linkedIds = widget.linkedWidgetIds || [];

    return {
      items: [
        {
          key: 'edit',
          icon: <EditOutlined />,
          label: '编辑',
          onClick: () => handleEditWidget(widget),
        },
        {
          key: 'link',
          icon: <LinkOutlined />,
          label: '联动组件',
          children: otherWidgets.map((w) => ({
            key: w.id,
            label: (
              <Space>
                {linkedIds.includes(w.id) && <Tag color="blue">已联动</Tag>}
                {w.title}
              </Space>
            ),
            onClick: () => {
              if (linkedIds.includes(w.id)) {
                handleUnlinkWidget(widget.id, w.id);
              } else {
                handleLinkWidget(widget.id, w.id);
              }
            },
          })),
        },
        { type: 'divider' },
        {
          key: 'delete',
          danger: true,
          icon: <DeleteOutlined />,
          label: '删除',
          onClick: () => handleDeleteWidget(widget.id),
        },
      ],
    };
  };

  const renderChartOption = (widget: Widget, data: Record<string, unknown>[]): EChartsOption => {
    const config = widget.config as Record<string, unknown>;
    const xField = (config.xField as string) || 'date';
    const yField = (config.yField as string) || 'value';
    const categoryField = (config.categoryField as string) || 'category';

    switch (widget.type) {
      case 'LINE_CHART':
        return {
          tooltip: { trigger: 'axis' },
          legend: { data: Array.from(new Set(data.map((d) => d[categoryField] as string))) },
          xAxis: { type: 'category', data: Array.from(new Set(data.map((d) => d[xField] as string))) },
          yAxis: { type: 'value' },
          series: Array.from(new Set(data.map((d) => d[categoryField] as string))).map((category) => ({
            name: category,
            type: 'line',
            smooth: true,
            data: data
              .filter((d) => d[categoryField] === category)
              .map((d) => d[yField] as number),
          })),
        };
      case 'BAR_CHART':
        return {
          tooltip: { trigger: 'axis' },
          legend: { data: Array.from(new Set(data.map((d) => d[categoryField] as string))) },
          xAxis: { type: 'category', data: Array.from(new Set(data.map((d) => d[xField] as string))) },
          yAxis: { type: 'value' },
          series: Array.from(new Set(data.map((d) => d[categoryField] as string))).map((category) => ({
            name: category,
            type: 'bar',
            data: data
              .filter((d) => d[categoryField] === category)
              .map((d) => d[yField] as number),
          })),
        };
      case 'PIE_CHART':
        return {
          tooltip: { trigger: 'item' },
          legend: { bottom: 0 },
          series: [
            {
              type: 'pie',
              radius: ['40%', '70%'],
              data: data.map((d) => ({
                name: d[categoryField] as string,
                value: d[yField] as number,
              })),
            },
          ],
        };
      case 'FUNNEL':
        return {
          tooltip: { trigger: 'item' },
          series: [
            {
              type: 'funnel',
              data: data.map((d) => ({
                name: d[categoryField] as string,
                value: d[yField] as number,
              })),
            },
          ],
        };
      default:
        return {};
    }
  };

  const renderWidgetContent = (widget: Widget) => {
    const data = widgetData[widget.id] || [];
    const config = widget.config as Record<string, unknown>;
    const yField = (config.yField as string) || 'value';

    switch (widget.type) {
      case 'NUMBER_CARD':
        const value = data.length > 0 ? (data[0][yField] as number) : 0;
        return (
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              height: '100%',
              cursor: widget.linkedWidgetIds?.length ? 'pointer' : 'default',
            }}
            onClick={() => handleWidgetClick(widget, data[0] || {})}
          >
            <Statistic title={widget.title} value={value} />
            {widget.linkedWidgetIds?.length > 0 && (
              <Tag color="blue" style={{ marginTop: 8 }}>
                已联动 {widget.linkedWidgetIds.length} 个组件
              </Tag>
            )}
          </div>
        );
      case 'TABLE':
        const columns = data.length > 0 ? Object.keys(data[0]) : [];
        return (
          <div style={{ height: '100%', overflow: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {columns.map((col) => (
                    <th
                      key={col}
                      style={{
                        padding: '8px',
                        textAlign: 'left',
                        borderBottom: '1px solid #f0f0f0',
                        backgroundColor: '#fafafa',
                      }}
                    >
                      {col}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.map((row, idx) => (
                  <tr
                    key={idx}
                    style={{
                      cursor: widget.linkedWidgetIds?.length ? 'pointer' : 'default',
                    }}
                    onClick={() => handleWidgetClick(widget, row)}
                  >
                    {columns.map((col) => (
                      <td key={col} style={{ padding: '8px', borderBottom: '1px solid #f0f0f0' }}>
                        {String(row[col])}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
            {widget.linkedWidgetIds?.length > 0 && (
              <Tag color="blue" style={{ marginTop: 8 }}>
                点击行联动筛选
              </Tag>
            )}
          </div>
        );
      case 'LINE_CHART':
      case 'BAR_CHART':
      case 'PIE_CHART':
      case 'FUNNEL':
        return (
          <ReactECharts
            option={renderChartOption(widget, data)}
            style={{ height: '100%', width: '100%' }}
            onEvents={{
              click: (params) => {
                if (widget.linkedWidgetIds?.length) {
                  handleWidgetClick(widget, {
                    dimension: params.name,
                    value: params.value,
                  });
                }
              },
            }}
          />
        );
      default:
        return <div>未知组件类型</div>;
    }
  };

  const renderWidget = (widget: Widget) => {
    const widgetConfig = WIDGET_TYPE_CONFIG[widget.type];
    return (
      <div key={widget.id}>
        <Card
          size="small"
          title={
            <Space>
              {widgetConfig.icon}
              {widget.title}
            </Space>
          }
          extra={
            <Space>
              <Button
                type="text"
                icon={<ReloadOutlined />}
                size="small"
                onClick={() => fetchWidgetData(widget.id)}
              />
              <Dropdown menu={getWidgetMenu(widget)} trigger={['click']}>
                <Button type="text" icon={<SettingOutlined />} size="small" />
              </Dropdown>
            </Space>
          }
          style={{ height: '100%', display: 'flex', flexDirection: 'column' }}
          bodyStyle={{ flex: 1, padding: 8, minHeight: 0 }}
        >
          <div style={{ height: '100%' }}>{renderWidgetContent(widget)}</div>
        </Card>
      </div>
    );
  };

  if (loading) {
    return <div style={{ padding: 24 }}>加载中...</div>;
  }

  return (
    <div style={{ padding: 16, height: '100vh', display: 'flex', flexDirection: 'column' }}>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap style={{ width: '100%' }}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/dashboards')}>
            返回
          </Button>
          <span style={{ fontSize: 16, fontWeight: 'bold' }}>{currentDashboard?.name}</span>

          <Space style={{ marginLeft: 'auto' }} wrap>
            <RangePicker
              value={filterState.timeRange}
              onChange={(dates) => {
                if (dates) {
                  handleFilterChange({ timeRange: dates as [Dayjs, Dayjs] });
                }
              }}
            />
            <Select
              placeholder="选择业务线"
              style={{ width: 150 }}
              value={filterState.businessLineId}
              onChange={(value) => handleFilterChange({ businessLineId: value })}
              allowClear
            >
              {businessLines.map((bl) => (
                <Option key={bl.id} value={bl.id}>
                  {bl.name}
                </Option>
              ))}
            </Select>
            <Button icon={<UploadOutlined />} onClick={handleImportClick}>
              导入
            </Button>
            <Button icon={<DownloadOutlined />} onClick={handleExport}>
              导出
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={handleAddWidget}>
              添加组件
            </Button>
          </Space>
        </Space>
      </Card>

      <div style={{ flex: 1, minHeight: 0 }}>
        <ResponsiveGridLayout
          className="layout"
          layouts={{ lg: gridLayout }}
          breakpoints={{ lg: 1200, md: 996, sm: 768, xs: 480, xxs: 0 }}
          cols={{ lg: 24, md: 20, sm: 12, xs: 6, xxs: 2 }}
          rowHeight={30}
          isDraggable
          isResizable
          onLayoutChange={(_, allLayouts) => handleLayoutChange(allLayouts)}
          draggableHandle=".ant-card-head"
        >
          {widgets.map((widget) => (
            <div key={widget.id} style={{ padding: 8, height: '100%' }}>
              {renderWidget(widget)}
            </div>
          ))}
        </ResponsiveGridLayout>
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept=".json"
        style={{ display: 'none' }}
        onChange={handleImport}
      />

      <Modal
        title="添加组件"
        open={addWidgetVisible}
        onOk={handleSaveWidget}
        onCancel={() => setAddWidgetVisible(false)}
        destroyOnClose
        width={600}
      >
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="type"
                label="组件类型"
                rules={[{ required: true, message: '请选择组件类型' }]}
              >
                <Select placeholder="请选择组件类型">
                  {Object.entries(WIDGET_TYPE_CONFIG).map(([type, config]) => (
                    <Option key={type} value={type}>
                      <Space>
                        {config.icon}
                        {config.label}
                      </Space>
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="title"
                label="组件标题"
                rules={[{ required: true, message: '请输入组件标题' }]}
              >
                <Input placeholder="请输入组件标题" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            name="metricId"
            label="关联指标"
            rules={[{ required: true, message: '请选择关联指标' }]}
          >
            <Select placeholder="请选择关联指标" showSearch optionFilterProp="children">
              {metrics.map((m) => (
                <Option key={m.id} value={m.id}>
                  {m.name}
                </Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name="config" label="配置(JSON)">
            <TextArea rows={6} placeholder='{"xField": "date", "yField": "value"}' />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="编辑组件"
        open={editWidgetVisible}
        onOk={handleSaveWidget}
        onCancel={() => setEditWidgetVisible(false)}
        destroyOnClose
        width={600}
      >
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="type"
                label="组件类型"
                rules={[{ required: true, message: '请选择组件类型' }]}
              >
                <Select placeholder="请选择组件类型">
                  {Object.entries(WIDGET_TYPE_CONFIG).map(([type, config]) => (
                    <Option key={type} value={type}>
                      <Space>
                        {config.icon}
                        {config.label}
                      </Space>
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="title"
                label="组件标题"
                rules={[{ required: true, message: '请输入组件标题' }]}
              >
                <Input placeholder="请输入组件标题" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            name="metricId"
            label="关联指标"
            rules={[{ required: true, message: '请选择关联指标' }]}
          >
            <Select placeholder="请选择关联指标" showSearch optionFilterProp="children">
              {metrics.map((m) => (
                <Option key={m.id} value={m.id}>
                  {m.name}
                </Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name="config" label="配置(JSON)">
            <TextArea rows={6} placeholder='{"xField": "date", "yField": "value"}' />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default DashboardDetail;
