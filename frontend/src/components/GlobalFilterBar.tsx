import React, { useState, useEffect } from 'react';
import { DatePicker, Select, Button, Space, Card, Row, Col, Tag, Tooltip } from 'antd';
import { FilterOutlined, ReloadOutlined, SettingOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';

const { RangePicker } = DatePicker;
const { Option } = Select;

interface DimensionOption {
  label: string;
  value: string;
  options?: { label: string; value: string }[];
}

interface GlobalFilterBarProps {
  defaultTimeRange?: [Dayjs, Dayjs];
  dimensions?: DimensionOption[];
  onApply?: (filters: Record<string, unknown>) => void;
  onReset?: () => void;
  loading?: boolean;
  compact?: boolean;
}

const presetRanges = [
  { label: '今天', value: 'today' },
  { label: '昨天', value: 'yesterday' },
  { label: '近7天', value: 'last7days' },
  { label: '近30天', value: 'last30days' },
  { label: '本月', value: 'thisMonth' },
  { label: '上月', value: 'lastMonth' },
];

const GlobalFilterBar: React.FC<GlobalFilterBarProps> = ({
  defaultTimeRange,
  dimensions = [],
  onApply,
  onReset,
  loading = false,
  compact = false,
}) => {
  const [timeRange, setTimeRange] = useState<[Dayjs, Dayjs] | null>(
    defaultTimeRange || [dayjs().subtract(7, 'day'), dayjs()],
  );
  const [dimensionValues, setDimensionValues] = useState<Record<string, string[]>>({});
  const [activePreset, setActivePreset] = useState<string>('last7days');

  useEffect(() => {
    if (defaultTimeRange) {
      setTimeRange(defaultTimeRange);
    }
  }, [defaultTimeRange]);

  const handlePresetClick = (preset: string) => {
    setActivePreset(preset);
    const today = dayjs().startOf('day');

    switch (preset) {
      case 'today':
        setTimeRange([today, today]);
        break;
      case 'yesterday':
        setTimeRange([today.subtract(1, 'day'), today.subtract(1, 'day')]);
        break;
      case 'last7days':
        setTimeRange([today.subtract(6, 'day'), today]);
        break;
      case 'last30days':
        setTimeRange([today.subtract(29, 'day'), today]);
        break;
      case 'thisMonth':
        setTimeRange([today.startOf('month'), today]);
        break;
      case 'lastMonth':
        setTimeRange([
          today.subtract(1, 'month').startOf('month'),
          today.subtract(1, 'month').endOf('month'),
        ]);
        break;
      default:
        break;
    }
  };

  const handleDimensionChange = (dimKey: string, values: string[]) => {
    setDimensionValues((prev) => ({
      ...prev,
      [dimKey]: values,
    }));
  };

  const handleApply = () => {
    const filters: Record<string, unknown> = {
      startTime: timeRange?.[0]?.toISOString(),
      endTime: timeRange?.[1]?.toISOString(),
      dimensions: dimensionValues,
    };
    onApply?.(filters);
  };

  const handleReset = () => {
    setTimeRange([dayjs().subtract(7, 'day'), dayjs()]);
    setDimensionValues({});
    setActivePreset('last7days');
    onReset?.();
  };

  const activeFilterCount = Object.values(dimensionValues).filter((v) => v.length > 0).length;

  return (
    <Card
      size="small"
      style={{ marginBottom: 16 }}
      bodyStyle={{ padding: compact ? '8px 12px' : '12px 16px' }}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          {presetRanges.map((preset) => (
            <Tag
              key={preset.value}
              color={activePreset === preset.value ? 'blue' : 'default'}
              onClick={() => handlePresetClick(preset.value)}
              style={{
                cursor: 'pointer',
                padding: '4px 12px',
                fontSize: 13,
              }}
            >
              {preset.label}
            </Tag>
          ))}

          <RangePicker
            value={timeRange}
            onChange={(dates) => {
              setTimeRange(dates as [Dayjs, Dayjs] | null);
              setActivePreset('');
            }}
            allowClear
            style={{ marginLeft: 8 }}
          />
        </div>

        {dimensions.length > 0 && (
          <Row gutter={[16, 8]} align="middle">
            {dimensions.map((dim) => (
              <Col key={dim.value} flex="180px">
                <Select
                  mode="multiple"
                  placeholder={dim.label}
                  value={dimensionValues[dim.value] || []}
                  onChange={(values) => handleDimensionChange(dim.value, values)}
                  style={{ width: '100%' }}
                  maxTagCount={2}
                  allowClear
                >
                  {dim.options?.map((opt) => (
                    <Option key={opt.value} value={opt.value}>
                      {opt.label}
                    </Option>
                  ))}
                </Select>
              </Col>
            ))}

            <Col>
              <Space>
                <Button type="primary" icon={<FilterOutlined />} onClick={handleApply} loading={loading}>
                  应用筛选
                </Button>
                <Button icon={<ReloadOutlined />} onClick={handleReset}>
                  重置
                </Button>
                {activeFilterCount > 0 && (
                  <Tooltip title={`已选择 ${activeFilterCount} 个维度筛选`}>
                    <Tag color="blue" style={{ marginLeft: 8 }}>
                      {activeFilterCount} 个筛选
                    </Tag>
                  </Tooltip>
                )}
              </Space>
            </Col>
          </Row>
        )}

        {dimensions.length === 0 && (
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Space>
              <Button type="primary" icon={<FilterOutlined />} onClick={handleApply} loading={loading}>
                应用
              </Button>
              <Button icon={<ReloadOutlined />} onClick={handleReset}>
                重置
              </Button>
            </Space>
          </div>
        )}
      </Space>
    </Card>
  );
};

export default GlobalFilterBar;
