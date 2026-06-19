import React, { useMemo, useState, useEffect } from 'react';
import ReactECharts from 'echarts-for-react';
import { Card, Table, Statistic, Spin, Alert, Empty } from 'antd';
import type { WidgetType } from '@/types';
import { WidgetType as WidgetTypeEnum } from '@/types';
import { formatNumber, formatChangeRate } from '@/utils/format';
import { metricService } from '@/services/metric';

interface ChartWidgetProps {
  type: WidgetType;
  title: string;
  metricId?: string | null;
  config?: Record<string, unknown>;
  filters?: Record<string, unknown> | null;
  loading?: boolean;
  height?: number;
}

const ChartWidget: React.FC<ChartWidgetProps> = ({
  type,
  title,
  metricId,
  config = {},
  filters = null,
  loading: externalLoading = false,
  height = 300,
}) => {
  const [data, setData] = useState<Record<string, unknown>[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!metricId || type === WidgetTypeEnum.NUMBER_CARD) return;

    const fetchData = async () => {
      setLoading(true);
      setError(null);
      try {
        const res = await metricService.execute(metricId, filters || undefined);
        setData(res.data.data);
      } catch (err) {
        setError('数据加载失败');
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [metricId, filters, type]);

  const getOption = useMemo(() => {
    if (!data || data.length === 0) return {};

    const xField = (config.xField as string) || 'date';
    const yField = (config.yField as string) || 'value';
    const seriesField = (config.seriesField as string) | undefined;

    switch (type) {
      case WidgetTypeEnum.LINE_CHART: {
        const categories = Array.from(new Set(data.map((d) => d[xField] as string)));
        const series = seriesField
          ? Array.from(new Set(data.map((d) => d[seriesField] as string)))
          : [yField];

        return {
          tooltip: { trigger: 'axis' },
          legend: { data: series },
          grid: { left: 40, right: 20, top: 40, bottom: 30 },
          xAxis: {
            type: 'category',
            data: categories,
            boundaryGap: false,
          },
          yAxis: { type: 'value' },
          series: series.map((s) => ({
            name: s,
            type: 'line',
            smooth: true,
            data: seriesField
              ? categories.map((c) => {
                  const item = data.find((d) => d[xField] === c && d[seriesField] === s);
                  return item ? (item[yField] as number) : 0;
                })
              : data.map((d) => d[yField] as number),
          })),
        };
      }

      case WidgetTypeEnum.BAR_CHART: {
        const categories = Array.from(new Set(data.map((d) => d[xField] as string)));
        const series = seriesField
          ? Array.from(new Set(data.map((d) => d[seriesField] as string)))
          : [yField];

        return {
          tooltip: { trigger: 'axis' },
          legend: { data: series },
          grid: { left: 40, right: 20, top: 40, bottom: 30 },
          xAxis: {
            type: 'category',
            data: categories,
          },
          yAxis: { type: 'value' },
          series: series.map((s) => ({
            name: s,
            type: 'bar',
            data: seriesField
              ? categories.map((c) => {
                  const item = data.find((d) => d[xField] === c && d[seriesField] === s);
                  return item ? (item[yField] as number) : 0;
                })
              : data.map((d) => d[yField] as number),
          })),
        };
      }

      case WidgetTypeEnum.PIE_CHART: {
        const nameField = (config.nameField as string) || 'name';
        const valueField = (config.valueField as string) || 'value';

        return {
          tooltip: { trigger: 'item' },
          legend: { bottom: 0 },
          series: [
            {
              type: 'pie',
              radius: ['40%', '70%'],
              avoidLabelOverlap: false,
              label: { show: false },
              data: data.map((d) => ({
                name: d[nameField] as string,
                value: d[valueField] as number,
              })),
            },
          ],
        };
      }

      case WidgetTypeEnum.FUNNEL: {
        const nameField = (config.nameField as string) || 'name';
        const valueField = (config.valueField as string) || 'value';

        return {
          tooltip: { trigger: 'item' },
          series: [
            {
              type: 'funnel',
              left: '10%',
              top: 30,
              bottom: 30,
              width: '80%',
              min: 0,
              max: Math.max(...data.map((d) => d[valueField] as number)),
              sort: 'descending',
              label: { show: true, position: 'inside' },
              data: data.map((d) => ({
                name: d[nameField] as string,
                value: d[valueField] as number,
              })),
            },
          ],
        };
      }

      default:
        return {};
    }
  }, [data, type, config]);

  const renderContent = () => {
    if (loading || externalLoading) {
      return (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            height,
          }}
        >
          <Spin />
        </div>
      );
    }

    if (error) {
      return (
        <div style={{ padding: 20 }}>
          <Alert type="error" message={error} showIcon />
        </div>
      );
    }

    if (!data || data.length === 0) {
      return (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            height,
          }}
        >
          <Empty description="暂无数据" />
        </div>
      );
    }

    switch (type) {
      case WidgetTypeEnum.LINE_CHART:
      case WidgetTypeEnum.BAR_CHART:
      case WidgetTypeEnum.PIE_CHART:
      case WidgetTypeEnum.FUNNEL:
        return <ReactECharts option={getOption} style={{ height }} />;

      case WidgetTypeEnum.TABLE: {
        const columns =
          data[0]
            ? Object.keys(data[0]).map((key) => ({
                title: key,
                dataIndex: key,
                key,
              }))
            : [];
        return (
          <Table
            dataSource={data}
            columns={columns}
            pagination={false}
            size="small"
            rowKey={(_, index) => String(index)}
            scroll={{ y: height - 50 }}
          />
        );
      }

      case WidgetTypeEnum.NUMBER_CARD: {
        const value = (config.value as number) || 0;
        const changeRate = (config.changeRate as number) || 0;
        const { text, color } = formatChangeRate(changeRate);

        return (
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'center',
              height: '100%',
              padding: '16px 0',
            }}
          >
            <Statistic
              title=""
              value={value}
              formatter={(v) => formatNumber(Number(v))}
              valueStyle={{ fontSize: 32, fontWeight: 600 }}
            />
            {changeRate !== 0 && (
              <div style={{ marginTop: 8, color }}>
                同比 {text}
              </div>
            )}
          </div>
        );
      }

      default:
        return null;
    }
  };

  return (
    <Card
      size="small"
      title={title}
      style={{ height: '100%', display: 'flex', flexDirection: 'column' }}
      bodyStyle={{ flex: 1, padding: 12, overflow: 'hidden' }}
    >
      {renderContent()}
    </Card>
  );
};

export default ChartWidget;
