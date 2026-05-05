const { ReportConfigParseError } = require('../../utils/errors');

const AVAILABLE_DIMENSIONS = {
  date: {
    key: 'date',
    label: '日期',
    type: 'time',
    supportedIntervals: ['hour', 'day', 'week', 'month'],
    defaultInterval: 'day',
    dateFormats: {
      hour: '%Y-%m-%d %H:00',
      day: '%Y-%m-%d',
      week: '%Y-%u',
      month: '%Y-%m'
    }
  },
  status: {
    key: 'status',
    label: '报名状态',
    type: 'category',
    validValues: ['pending_review', 'approved', 'rejected', 'cancelled']
  },
  ticket_name: {
    key: 'ticket_name',
    label: '票务类型',
    type: 'category'
  },
  check_in_status: {
    key: 'check_in_status',
    label: '签到状态',
    type: 'category'
  }
};

const AVAILABLE_METRICS = {
  count: {
    key: 'count',
    label: '报名人数',
    type: 'count',
    aggregation: 'count',
    sqlTemplate: 'COUNT(*)'
  },
  registrations: {
    key: 'registrations',
    label: '报名数',
    type: 'count',
    aggregation: 'count',
    sqlTemplate: 'COUNT(*)'
  },
  approved: {
    key: 'approved',
    label: '通过数',
    type: 'count',
    aggregation: 'sum',
    sqlTemplate: "SUM(CASE WHEN status = 'approved' THEN 1 ELSE 0 END)"
  },
  rejected: {
    key: 'rejected',
    label: '拒绝数',
    type: 'count',
    aggregation: 'sum',
    sqlTemplate: "SUM(CASE WHEN status = 'rejected' THEN 1 ELSE 0 END)"
  },
  pending: {
    key: 'pending',
    label: '待审核数',
    type: 'count',
    aggregation: 'sum',
    sqlTemplate: "SUM(CASE WHEN status = 'pending_review' THEN 1 ELSE 0 END)"
  },
  revenue: {
    key: 'revenue',
    label: '收入',
    type: 'sum',
    aggregation: 'sum',
    sqlTemplate: 'COALESCE(SUM(total_amount), 0)',
    field: 'total_amount'
  },
  checked_in: {
    key: 'checked_in',
    label: '签到数',
    type: 'count',
    aggregation: 'sum',
    sqlTemplate: 'SUM(CASE WHEN check_in_status = TRUE THEN 1 ELSE 0 END)'
  },
  avg_ticket_price: {
    key: 'avg_ticket_price',
    label: '平均票价',
    type: 'avg',
    aggregation: 'avg',
    sqlTemplate: 'AVG(total_amount)',
    field: 'total_amount'
  }
};

const CHART_TYPE_CONFIGS = {
  bar: {
    key: 'bar',
    label: '柱状图',
    supportedDimensions: ['date', 'status', 'ticket_name', 'check_in_status'],
    supportedMetrics: ['count', 'registrations', 'approved', 'rejected', 'pending', 'revenue', 'checked_in', 'avg_ticket_price'],
    minDimensions: 1,
    maxDimensions: 2,
    minMetrics: 1,
    maxMetrics: 10
  },
  line: {
    key: 'line',
    label: '折线图',
    supportedDimensions: ['date'],
    supportedMetrics: ['count', 'registrations', 'approved', 'rejected', 'pending', 'revenue', 'checked_in'],
    minDimensions: 1,
    maxDimensions: 1,
    minMetrics: 1,
    maxMetrics: 10
  },
  area: {
    key: 'area',
    label: '面积图',
    supportedDimensions: ['date'],
    supportedMetrics: ['count', 'registrations', 'approved', 'rejected', 'pending', 'revenue', 'checked_in'],
    minDimensions: 1,
    maxDimensions: 1,
    minMetrics: 1,
    maxMetrics: 10
  },
  pie: {
    key: 'pie',
    label: '饼图',
    supportedDimensions: ['status', 'ticket_name', 'check_in_status'],
    supportedMetrics: ['count', 'registrations', 'revenue'],
    minDimensions: 1,
    maxDimensions: 1,
    minMetrics: 1,
    maxMetrics: 1
  },
  table: {
    key: 'table',
    label: '数据表格',
    supportedDimensions: ['date', 'status', 'ticket_name', 'check_in_status'],
    supportedMetrics: ['count', 'registrations', 'approved', 'rejected', 'pending', 'revenue', 'checked_in', 'avg_ticket_price'],
    minDimensions: 0,
    maxDimensions: 5,
    minMetrics: 1,
    maxMetrics: 20
  },
  number: {
    key: 'number',
    label: '数字卡片',
    supportedDimensions: [],
    supportedMetrics: ['count', 'registrations', 'approved', 'rejected', 'pending', 'revenue', 'checked_in', 'avg_ticket_price'],
    minDimensions: 0,
    maxDimensions: 0,
    minMetrics: 1,
    maxMetrics: 20
  }
};

class ReportConfigParser {
  constructor() {
    this.dimensions = AVAILABLE_DIMENSIONS;
    this.metrics = AVAILABLE_METRICS;
    this.chartTypes = CHART_TYPE_CONFIGS;
  }

  parse(config) {
    if (!config) {
      throw new ReportConfigParseError('配置不能为空');
    }

    const { chart_type: chartType, dimensions, metrics, filters, time_range: timeRange } = config;

    const chartConfig = this.validateChartType(chartType);
    const parsedDimensions = this.parseDimensions(dimensions, chartConfig);
    const parsedMetrics = this.parseMetrics(metrics, chartConfig);
    const parsedFilters = this.parseFilters(filters);
    const parsedTimeRange = this.parseTimeRange(timeRange);

    const queryParams = this.buildQueryParams(
      chartType,
      parsedDimensions,
      parsedMetrics,
      parsedFilters,
      parsedTimeRange
    );

    return {
      chartType,
      chartConfig,
      dimensions: parsedDimensions,
      metrics: parsedMetrics,
      filters: parsedFilters,
      timeRange: parsedTimeRange,
      queryParams
    };
  }

  validateChartType(chartType) {
    if (!chartType) {
      throw new ReportConfigParseError('图表类型不能为空', 'chart_type');
    }

    const chartConfig = this.chartTypes[chartType];
    if (!chartConfig) {
      throw new ReportConfigParseError(
        `不支持的图表类型: ${chartType}`,
        'chart_type',
        chartType
      );
    }

    return chartConfig;
  }

  parseDimensions(dimensions, chartConfig) {
    const parsedDimensions = [];

    if (!dimensions || !Array.isArray(dimensions)) {
      if (chartConfig.minDimensions > 0) {
        throw new ReportConfigParseError(
          `此图表类型至少需要 ${chartConfig.minDimensions} 个维度`,
          'dimensions'
        );
      }
      return parsedDimensions;
    }

    if (dimensions.length < chartConfig.minDimensions) {
      throw new ReportConfigParseError(
        `此图表类型至少需要 ${chartConfig.minDimensions} 个维度，当前有 ${dimensions.length} 个`,
        'dimensions',
        dimensions
      );
    }

    if (dimensions.length > chartConfig.maxDimensions) {
      throw new ReportConfigParseError(
        `此图表类型最多支持 ${chartConfig.maxDimensions} 个维度，当前有 ${dimensions.length} 个`,
        'dimensions',
        dimensions
      );
    }

    for (const dim of dimensions) {
      const dimKey = typeof dim === 'string' ? dim : dim.key;
      
      if (!dimKey) {
        throw new ReportConfigParseError('维度 key 不能为空', 'dimensions', dim);
      }

      const dimConfig = this.dimensions[dimKey];
      if (!dimConfig) {
        throw new ReportConfigParseError(
          `不支持的维度: ${dimKey}`,
          'dimensions',
          dim
        );
      }

      if (!chartConfig.supportedDimensions.includes(dimKey)) {
        throw new ReportConfigParseError(
          `图表类型 ${chartConfig.key} 不支持维度 ${dimKey}`,
          'dimensions',
          dim
        );
      }

      parsedDimensions.push({
        ...dimConfig,
        interval: typeof dim === 'object' ? dim.interval : undefined
      });
    }

    return parsedDimensions;
  }

  parseMetrics(metrics, chartConfig) {
    const parsedMetrics = [];

    if (!metrics || !Array.isArray(metrics)) {
      if (chartConfig.minMetrics > 0) {
        throw new ReportConfigParseError(
          `此图表类型至少需要 ${chartConfig.minMetrics} 个指标`,
          'metrics'
        );
      }
      return parsedMetrics;
    }

    if (metrics.length < chartConfig.minMetrics) {
      throw new ReportConfigParseError(
        `此图表类型至少需要 ${chartConfig.minMetrics} 个指标，当前有 ${metrics.length} 个`,
        'metrics',
        metrics
      );
    }

    if (metrics.length > chartConfig.maxMetrics) {
      throw new ReportConfigParseError(
        `此图表类型最多支持 ${chartConfig.maxMetrics} 个指标，当前有 ${metrics.length} 个`,
        'metrics',
        metrics
      );
    }

    for (const metric of metrics) {
      const metricKey = typeof metric === 'string' ? metric : metric.key;

      if (!metricKey) {
        throw new ReportConfigParseError('指标 key 不能为空', 'metrics', metric);
      }

      const metricConfig = this.metrics[metricKey];
      if (!metricConfig) {
        throw new ReportConfigParseError(
          `不支持的指标: ${metricKey}`,
          'metrics',
          metric
        );
      }

      if (!chartConfig.supportedMetrics.includes(metricKey)) {
        throw new ReportConfigParseError(
          `图表类型 ${chartConfig.key} 不支持指标 ${metricKey}`,
          'metrics',
          metric
        );
      }

      parsedMetrics.push({
        ...metricConfig,
        alias: typeof metric === 'object' ? metric.alias : undefined
      });
    }

    return parsedMetrics;
  }

  parseFilters(filters) {
    if (!filters) {
      return null;
    }

    if (typeof filters !== 'object' || Array.isArray(filters)) {
      throw new ReportConfigParseError('过滤条件必须是对象', 'filters', filters);
    }

    const validFilterKeys = ['status', 'check_in_status', 'ticket_id'];
    const parsedFilters = {};

    for (const key in filters) {
      if (!validFilterKeys.includes(key)) {
        throw new ReportConfigParseError(
          `不支持的过滤条件: ${key}`,
          'filters',
          filters
        );
      }
      parsedFilters[key] = filters[key];
    }

    return Object.keys(parsedFilters).length > 0 ? parsedFilters : null;
  }

  parseTimeRange(timeRange) {
    if (!timeRange) {
      return null;
    }

    if (typeof timeRange !== 'object') {
      throw new ReportConfigParseError('时间范围必须是对象', 'time_range', timeRange);
    }

    const { start_date: startDate, end_date: endDate } = timeRange;

    if (startDate) {
      const parsedStartDate = new Date(startDate);
      if (isNaN(parsedStartDate.getTime())) {
        throw new ReportConfigParseError(
          '开始日期格式不正确',
          'time_range.start_date',
          startDate
        );
      }
    }

    if (endDate) {
      const parsedEndDate = new Date(endDate);
      if (isNaN(parsedEndDate.getTime())) {
        throw new ReportConfigParseError(
          '结束日期格式不正确',
          'time_range.end_date',
          endDate
        );
      }
    }

    if (startDate && endDate && new Date(startDate) > new Date(endDate)) {
      throw new ReportConfigParseError(
        '开始日期不能晚于结束日期',
        'time_range',
        timeRange
      );
    }

    return {
      startDate: startDate ? new Date(startDate) : null,
      endDate: endDate ? new Date(endDate) : null
    };
  }

  buildQueryParams(chartType, dimensions, metrics, filters, timeRange) {
    const selectFields = [];
    const groupByFields = [];
    const whereConditions = [];
    const params = [];

    for (const dim of dimensions) {
      if (dim.type === 'time') {
        const interval = dim.interval || dim.defaultInterval;
        const dateFormat = dim.dateFormats[interval];
        selectFields.push(`DATE_FORMAT(r.created_at, '${dateFormat}') as ${dim.key}`);
        groupByFields.push(`DATE_FORMAT(r.created_at, '${dateFormat}')`);
      } else {
        selectFields.push(`r.${dim.key} as ${dim.key}`);
        groupByFields.push(`r.${dim.key}`);
      }
    }

    for (const metric of metrics) {
      const alias = metric.alias || metric.key;
      selectFields.push(`${metric.sqlTemplate} as ${alias}`);
    }

    if (filters) {
      if (filters.status) {
        whereConditions.push('r.status = ?');
        params.push(filters.status);
      }
      if (filters.check_in_status !== undefined) {
        whereConditions.push('r.check_in_status = ?');
        params.push(filters.check_in_status);
      }
      if (filters.ticket_id) {
        whereConditions.push('r.ticket_id = ?');
        params.push(filters.ticket_id);
      }
    }

    if (timeRange) {
      if (timeRange.startDate) {
        whereConditions.push('r.created_at >= ?');
        params.push(timeRange.startDate);
      }
      if (timeRange.endDate) {
        whereConditions.push('r.created_at <= ?');
        params.push(timeRange.endDate);
      }
    }

    const orderBy = dimensions.some(d => d.type === 'time') ? ' ORDER BY 1 ASC' : '';

    return {
      selectFields,
      groupByFields,
      whereConditions,
      params,
      orderBy,
      needsGroupBy: groupByFields.length > 0
    };
  }

  getAvailableDimensions() {
    return Object.values(this.dimensions).map(d => ({
      key: d.key,
      label: d.label,
      type: d.type,
      supportedIntervals: d.supportedIntervals || undefined
    }));
  }

  getAvailableMetrics() {
    return Object.values(this.metrics).map(m => ({
      key: m.key,
      label: m.label,
      type: m.type,
      aggregation: m.aggregation
    }));
  }

  getAvailableChartTypes() {
    return Object.values(this.chartTypes).map(c => ({
      key: c.key,
      label: c.label,
      supportedDimensions: c.supportedDimensions,
      supportedMetrics: c.supportedMetrics,
      minDimensions: c.minDimensions,
      maxDimensions: c.maxDimensions
    }));
  }

  validateConfig(config) {
    try {
      this.parse(config);
      return { valid: true };
    } catch (error) {
      return {
        valid: false,
        error: {
          message: error.message,
          parsePath: error.parsePath,
          originalValue: error.originalValue
        }
      };
    }
  }
}

const reportConfigParser = new ReportConfigParser();

module.exports = reportConfigParser;
module.exports.ReportConfigParser = ReportConfigParser;
module.exports.AVAILABLE_DIMENSIONS = AVAILABLE_DIMENSIONS;
module.exports.AVAILABLE_METRICS = AVAILABLE_METRICS;
module.exports.CHART_TYPE_CONFIGS = CHART_TYPE_CONFIGS;
