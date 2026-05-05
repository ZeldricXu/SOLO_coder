import React, { useState, useEffect } from 'react';
import {
  Card,
  Select,
  Button,
  Radio,
  Row,
  Col,
  Spin,
  message,
  Tabs,
  Divider,
  Tag,
  Empty,
  Alert,
  Space
} from 'antd';
import {
  BarChartOutlined,
  PieChartOutlined,
  LineChartOutlined,
  ReloadOutlined
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { analysisApi } from '../services/api';
import type {
  Field,
  StatisticsData,
  FrequencyResult,
  DescriptiveStats,
  CrossAnalysisResult,
  ChartConfig
} from '../types';

const { Option } = Select;
const { TabPane } = Tabs;

interface ChartData {
  type: string;
  data: any[];
  title?: string;
  xField?: string;
  yField?: string;
  seriesField?: string;
  colorField?: string;
  angleField?: string;
  stack?: boolean;
}

export default function ChartDisplayPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [statistics, setStatistics] = useState<StatisticsData | null>(null);
  const [crossAnalyses, setCrossAnalyses] = useState<CrossAnalysisResult[]>([]);
  const [activeTab, setActiveTab] = useState('frequency');
  
  const [selectedField, setSelectedField] = useState<string>('');
  const [chartType, setChartType] = useState<'bar' | 'pie' | 'line'>('bar');
  const [selectedAnalysis, setSelectedAnalysis] = useState<CrossAnalysisResult | null>(null);
  const [chartData, setChartData] = useState<ChartData | null>(null);

  const surveyId = 'survey_001';

  useEffect(() => {
    loadData();
  }, []);

  useEffect(() => {
    if (selectedField && statistics) {
      const stat = statistics.statistics.find(s => s.field_id === selectedField);
      if (stat) {
        generateFrequencyChart(stat);
      }
    }
  }, [selectedField, chartType, statistics]);

  useEffect(() => {
    if (selectedAnalysis) {
      generateCrossChart(selectedAnalysis);
    }
  }, [selectedAnalysis]);

  const loadData = async () => {
    setLoading(true);
    try {
      const [stats, analyses] = await Promise.all([
        analysisApi.getStatistics(surveyId),
        analysisApi.getSurveyCrossAnalyses(surveyId)
      ]);
      setStatistics(stats);
      setCrossAnalyses(analyses);
    } catch (error: any) {
      message.error(error.message || '加载数据失败');
    } finally {
      setLoading(false);
    }
  };

  const generateFrequencyChart = (stat: any) => {
    if (stat.type === 'frequency') {
      const data = stat.data as FrequencyResult;
      const chartData: ChartData = {
        type: chartType,
        data: data.frequencies.map(f => ({
          value: f.value,
          count: f.count,
          percentage: f.percentage
        })),
        title: data.field_name
      };
      setChartData(chartData);
    } else if (stat.type === 'descriptive') {
      const data = stat.data as DescriptiveStats;
      const chartData: ChartData = {
        type: 'box',
        data: [{
          field: data.field_name,
          min: data.min,
          q1: data.q25,
          median: data.median,
          q3: data.q75,
          max: data.max
        }],
        title: data.field_name
      };
      setChartData(chartData);
    }
  };

  const generateCrossChart = (analysis: CrossAnalysisResult) => {
    const config = analysis.chart_config;
    if (config && config.data) {
      setChartData({
        type: config.type || 'interval',
        data: config.data,
        title: config.title,
        xField: config.xField,
        yField: config.yField,
        seriesField: config.seriesField,
        stack: config.stack
      });
    }
  };

  const getFieldName = (fieldId: string) => {
    const field = statistics?.statistics.find(s => s.field_id === fieldId);
    return field?.field_name || fieldId;
  };

  const renderChart = () => {
    if (!chartData) {
      return (
        <div className="empty-container">
          <Empty description="请选择变量查看图表" />
        </div>
      );
    }

    const { type, data, xField, yField, seriesField, stack } = chartData;

    return (
      <div className="chart-container">
        <RenderChart
          type={type}
          data={data}
          xField={xField || 'value'}
          yField={yField || 'percentage'}
          seriesField={seriesField}
          stack={stack}
          title={chartData.title}
          chartType={chartType}
        />
      </div>
    );
  };

  const frequencyFields = statistics?.statistics.filter(s => s.type === 'frequency') || [];
  const descriptiveFields = statistics?.statistics.filter(s => s.type === 'descriptive') || [];

  return (
    <Spin spinning={loading}>
      <div>
        <Tabs activeKey={activeTab} onChange={setActiveTab}>
          <TabPane tab="频数图表" key="frequency">
            <Row gutter={16}>
              <Col span={8}>
                <Card title="选择变量" size="small">
                  <Select
                    style={{ width: '100%', marginBottom: 16 }}
                    placeholder="选择变量"
                    value={selectedField || undefined}
                    onChange={setSelectedField}
                    allowClear
                  >
                    {frequencyFields.map(field => (
                      <Option key={field.field_id} value={field.field_id}>
                        {field.field_name}
                      </Option>
                    ))}
                  </Select>

                  <Divider style={{ margin: '12px 0' }}>图表类型</Divider>
                  
                  <Radio.Group
                    value={chartType}
                    onChange={e => setChartType(e.target.value)}
                    optionType="button"
                    buttonStyle="solid"
                    style={{ width: '100%' }}
                  >
                    <Radio.Button value="bar" style={{ width: '33.33%', textAlign: 'center' }}>
                      <BarChartOutlined /> 柱状图
                    </Radio.Button>
                    <Radio.Button value="pie" style={{ width: '33.33%', textAlign: 'center' }}>
                      <PieChartOutlined /> 饼图
                    </Radio.Button>
                    <Radio.Button value="line" style={{ width: '33.33%', textAlign: 'center' }}>
                      <LineChartOutlined /> 折线图
                    </Radio.Button>
                  </Radio.Group>

                  {selectedField && statistics && (
                    <div style={{ marginTop: 24 }}>
                      <Divider>数据说明</Divider>
                      {(() => {
                        const stat = statistics.statistics.find(s => s.field_id === selectedField);
                        if (!stat) return null;
                        
                        const data = stat.data as FrequencyResult;
                        return (
                          <div>
                            <Alert
                              type="info"
                              message={`${data.field_name} - 频数分布`}
                              description={
                                <div>
                                  <p>有效样本: {data.total_valid}</p>
                                  <p>缺失值: {data.missing_count}</p>
                                  <p>选项数: {data.frequencies.length}</p>
                                </div>
                              }
                            />
                          </div>
                        );
                      })()}
                    </div>
                  )}
                </Card>
              </Col>

              <Col span={16}>
                <Card 
                  title={chartData?.title || '图表展示'}
                  extra={
                    <Button 
                      size="small" 
                      icon={<ReloadOutlined />} 
                      onClick={loadData}
                    >
                      刷新
                    </Button>
                  }
                >
                  {renderChart()}
                </Card>
              </Col>
            </Row>
          </TabPane>

          <TabPane tab="描述性图表" key="descriptive">
            <Row gutter={16}>
              <Col span={8}>
                <Card title="选择变量" size="small">
                  <Select
                    style={{ width: '100%', marginBottom: 16 }}
                    placeholder="选择数值型变量"
                    value={selectedField || undefined}
                    onChange={setSelectedField}
                    allowClear
                  >
                    {descriptiveFields.map(field => (
                      <Option key={field.field_id} value={field.field_id}>
                        {field.field_name}
                      </Option>
                    ))}
                  </Select>

                  {selectedField && statistics && (
                    <div style={{ marginTop: 24 }}>
                      <Divider>统计摘要</Divider>
                      {(() => {
                        const stat = statistics.statistics.find(s => s.field_id === selectedField);
                        if (!stat) return null;
                        
                        const data = stat.data as DescriptiveStats;
                        return (
                          <div>
                            <Alert
                              type="info"
                              message={`${data.field_name} - 描述性统计`}
                              description={
                                <div>
                                  <Row gutter={8}>
                                    <Col span={12}>
                                      <p>样本数: {data.count}</p>
                                      <p>均值: {data.mean.toFixed(4)}</p>
                                      <p>中位数: {data.median.toFixed(4)}</p>
                                      <p>标准差: {data.std.toFixed(4)}</p>
                                    </Col>
                                    <Col span={12}>
                                      <p>最小值: {data.min.toFixed(4)}</p>
                                      <p>最大值: {data.max.toFixed(4)}</p>
                                      <p>25分位: {data.q25.toFixed(4)}</p>
                                      <p>75分位: {data.q75.toFixed(4)}</p>
                                    </Col>
                                  </Row>
                                </div>
                              }
                            />
                          </div>
                        );
                      })()}
                    </div>
                  )}
                </Card>
              </Col>

              <Col span={16}>
                <Card 
                  title={chartData?.title || '箱线图'}
                  extra={
                    <Button 
                      size="small" 
                      icon={<ReloadOutlined />} 
                      onClick={loadData}
                    >
                      刷新
                    </Button>
                  }
                >
                  {renderChart()}
                </Card>
              </Col>
            </Row>
          </TabPane>

          <TabPane tab="交叉分析图表" key="cross">
            <Row gutter={16}>
              <Col span={8}>
                <Card title="选择交叉分析" size="small">
                  <Select
                    style={{ width: '100%', marginBottom: 16 }}
                    placeholder="选择已保存的交叉分析"
                    value={selectedAnalysis?.analysis_id || undefined}
                    onChange={(value) => {
                      const analysis = crossAnalyses.find(a => a.analysis_id === value);
                      setSelectedAnalysis(analysis || null);
                    }}
                    allowClear
                  >
                    {crossAnalyses.map(analysis => (
                      <Option key={analysis.analysis_id} value={analysis.analysis_id}>
                        {getFieldName(analysis.variables[0])} vs {getFieldName(analysis.variables[1])}
                        {analysis.significance?.significant && (
                          <Tag color="green" style={{ marginLeft: 8 }}>显著</Tag>
                        )}
                      </Option>
                    ))}
                  </Select>

                  {crossAnalyses.length === 0 && (
                    <Alert
                      type="warning"
                      message="暂无交叉分析结果"
                      description="请先在'分析配置'页面执行交叉分析"
                      showIcon
                    />
                  )}

                  {selectedAnalysis && (
                    <div style={{ marginTop: 24 }}>
                      <Divider>分析信息</Divider>
                      <Alert
                        type={selectedAnalysis.significance?.significant ? 'success' : 'info'}
                        message={
                          <div>
                            <p>行变量: {getFieldName(selectedAnalysis.variables[0])}</p>
                            <p>列变量: {getFieldName(selectedAnalysis.variables[1])}</p>
                            {selectedAnalysis.significance && (
                              <p>
                                {selectedAnalysis.significance.test_type}, 
                                p={selectedAnalysis.significance.p_value.toFixed(4)},
                                {selectedAnalysis.significance.significant ? ' 显著' : ' 不显著'}
                              </p>
                            )}
                          </div>
                        }
                      />
                    </div>
                  )}
                </Card>
              </Col>

              <Col span={16}>
                <Card 
                  title={chartData?.title || '交叉分析图表'}
                  extra={
                    <Button 
                      size="small" 
                      icon={<ReloadOutlined />} 
                      onClick={loadData}
                    >
                      刷新
                    </Button>
                  }
                >
                  {renderChart()}
                </Card>
              </Col>
            </Row>
          </TabPane>
        </Tabs>

        <div style={{ marginTop: 24, textAlign: 'center' }}>
          <Button type="primary" onClick={() => navigate('/report')}>
            生成分析报告
          </Button>
        </div>
      </div>
    </Spin>
  );
}

function RenderChart({
  type,
  data,
  xField,
  yField,
  seriesField,
  stack,
  title,
  chartType
}: {
  type: string;
  data: any[];
  xField: string;
  yField: string;
  seriesField?: string;
  stack?: boolean;
  title?: string;
  chartType?: string;
}) {
  if (!data || data.length === 0) {
    return <Empty description="暂无数据" />;
  }

  const chartTheme = {
    colors10: ['#1890ff', '#52c41a', '#faad14', '#f5222d', '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16', '#a0d911', '#2f54eb'],
    colors20: ['#1890ff', '#40a9ff', '#69c0ff', '#91d5ff', '#bae7ff', '#e6f7ff',
               '#52c41a', '#73d13d', '#95de64', '#b7eb8f', '#d9f7be', '#f6ffed',
               '#faad14', '#ffc53d', '#ffd666', '#ffe58f', '#fff3bf', '#fffbe6']
  };

  if (type === 'pie' || chartType === 'pie') {
    const pieData = data.map(d => ({
      type: d[xField] || d.value,
      value: d[yField] || d.percentage || d.count
    }));

    const PieChart = () => {
      const total = pieData.reduce((sum, item) => sum + item.value, 0);
      const colors = chartTheme.colors10;
      let startAngle = 0;

      return (
        <svg width="100%" height="350" viewBox="0 0 400 350">
          <g transform="translate(200, 175)">
            {pieData.map((item, index) => {
              const angle = (item.value / total) * 360;
              const endAngle = startAngle + angle;
              const startRad = (startAngle - 90) * Math.PI / 180;
              const endRad = (endAngle - 90) * Math.PI / 180;
              
              const x1 = 140 * Math.cos(startRad);
              const y1 = 140 * Math.sin(startRad);
              const x2 = 140 * Math.cos(endRad);
              const y2 = 140 * Math.sin(endRad);
              
              const largeArc = angle > 180 ? 1 : 0;
              
              const path = `M 0 0 L ${x1} ${y1} A 140 140 0 ${largeArc} 1 ${x2} ${y2} Z`;
              
              const labelAngle = startAngle + angle / 2;
              const labelRad = (labelAngle - 90) * Math.PI / 180;
              const labelR = 170;
              const labelX = labelR * Math.cos(labelRad);
              const labelY = labelR * Math.sin(labelRad);
              
              startAngle = endAngle;
              
              return (
                <g key={index}>
                  <path d={path} fill={colors[index % colors.length]} stroke="#fff" strokeWidth="2" />
                  <text
                    x={labelX}
                    y={labelY}
                    textAnchor="middle"
                    dominantBaseline="middle"
                    fontSize="12"
                    fill="#333"
                  >
                    {item.type} ({item.value.toFixed(1)}%)
                  </text>
                </g>
              );
            })}
          </g>
          {title && (
            <text x="200" y="320" textAnchor="middle" fontSize="14" fontWeight="bold" fill="#333">
              {title}
            </text>
          )}
        </svg>
      );
    };

    return <PieChart />;
  }

  if (type === 'box') {
    const item = data[0];
    
    return (
      <svg width="100%" height="350" viewBox="0 0 500 350">
        <g transform="translate(50, 30)">
          <line x1="0" y1="280" x2="400" y2="280" stroke="#ccc" strokeWidth="1" />
          
          {(() => {
            const range = item.max - item.min;
            const padding = range * 0.1;
            const totalRange = range + padding * 2;
            
            const toY = (val: number) => 280 - ((val - item.min + padding) / totalRange) * 250;
            
            const boxX = 180;
            const boxWidth = 80;
            
            return (
              <>
                <line x1={boxX + boxWidth/2} y1={toY(item.min)} x2={boxX + boxWidth/2} y2={toY(item.q1)} stroke="#1890ff" strokeWidth="2" />
                <line x1={boxX + boxWidth/2 - 15} y1={toY(item.min)} x2={boxX + boxWidth/2 + 15} y2={toY(item.min)} stroke="#1890ff" strokeWidth="2" />
                
                <rect x={boxX} y={toY(item.q3)} width={boxWidth} height={toY(item.q1) - toY(item.q3)} fill="none" stroke="#1890ff" strokeWidth="2" />
                
                <line x1={boxX} y1={toY(item.median)} x2={boxX + boxWidth} y2={toY(item.median)} stroke="#f5222d" strokeWidth="3" />
                
                <line x1={boxX + boxWidth/2} y1={toY(item.q3)} x2={boxX + boxWidth/2} y2={toY(item.max)} stroke="#1890ff" strokeWidth="2" />
                <line x1={boxX + boxWidth/2 - 15} y1={toY(item.max)} x2={boxX + boxWidth/2 + 15} y2={toY(item.max)} stroke="#1890ff" strokeWidth="2" />
                
                <text x={boxX + boxWidth + 20} y={toY(item.min)} fontSize="12" fill="#666">最小值: {item.min.toFixed(2)}</text>
                <text x={boxX + boxWidth + 20} y={toY(item.q1)} fontSize="12" fill="#666">25分位: {item.q1.toFixed(2)}</text>
                <text x={boxX + boxWidth + 20} y={toY(item.median)} fontSize="12" fill="#f5222d">中位数: {item.median.toFixed(2)}</text>
                <text x={boxX + boxWidth + 20} y={toY(item.q3)} fontSize="12" fill="#666">75分位: {item.q3.toFixed(2)}</text>
                <text x={boxX + boxWidth + 20} y={toY(item.max)} fontSize="12" fill="#666">最大值: {item.max.toFixed(2)}</text>
              </>
            );
          })()}
          
          {title && (
            <text x="200" y="310" textAnchor="middle" fontSize="14" fontWeight="bold" fill="#333">
              {title} - 箱线图
            </text>
          )}
        </g>
      </svg>
    );
  }

  const colors = chartTheme.colors10;
  
  if (seriesField) {
    const categories = [...new Set(data.map(d => d[xField]))];
    const seriesValues = [...new Set(data.map(d => d[seriesField]))];
    
    const chartWidth = 500;
    const chartHeight = 300;
    const padding = { left: 60, right: 40, top: 40, bottom: 60 };
    
    const innerWidth = chartWidth - padding.left - padding.right;
    const innerHeight = chartHeight - padding.top - padding.bottom;
    
    const maxValue = Math.max(...data.map(d => d[yField]));
    const barGroupWidth = innerWidth / categories.length;
    const barWidth = (barGroupWidth - 20) / seriesValues.length;
    
    return (
      <svg width="100%" height="350" viewBox={`0 0 ${chartWidth} ${chartHeight + 50}`}>
        <g transform={`translate(${padding.left}, ${padding.top})`}>
          <line x1="0" y1={innerHeight} x2={innerWidth} y2={innerHeight} stroke="#ccc" strokeWidth="1" />
          <line x1="0" y1="0" x2="0" y2={innerHeight} stroke="#ccc" strokeWidth="1" />
          
          {[0, 0.25, 0.5, 0.75, 1].map((ratio, i) => (
            <g key={i}>
              <line 
                x1="0" 
                y1={innerHeight * (1 - ratio)} 
                x2={innerWidth} 
                y2={innerHeight * (1 - ratio)} 
                stroke="#eee" 
                strokeWidth="1" 
                strokeDasharray="4,4"
              />
              <text 
                x="-10" 
                y={innerHeight * (1 - ratio) + 4} 
                textAnchor="end" 
                fontSize="10" 
                fill="#666"
              >
                {(maxValue * ratio).toFixed(1)}
              </text>
            </g>
          ))}
          
          {categories.map((category, catIndex) => {
            const x = catIndex * barGroupWidth + 10;
            
            return (
              <g key={category}>
                {seriesValues.map((seriesValue, seriesIndex) => {
                  const item = data.find(d => d[xField] === category && d[seriesField] === seriesValue);
                  const value = item ? item[yField] : 0;
                  const barHeight = (value / maxValue) * innerHeight;
                  const barX = x + seriesIndex * barWidth;
                  
                  return (
                    <g key={seriesValue}>
                      <rect
                        x={barX}
                        y={innerHeight - barHeight}
                        width={barWidth - 2}
                        height={barHeight}
                        fill={colors[seriesIndex % colors.length]}
                        rx="2"
                      />
                    </g>
                  );
                })}
                
                <text
                  x={x + (barGroupWidth - 20) / 2}
                  y={innerHeight + 20}
                  textAnchor="middle"
                  fontSize="10"
                  fill="#666"
                >
                  {String(category).length > 6 ? String(category).slice(0, 6) + '...' : category}
                </text>
              </g>
            );
          })}
          
          <g transform={`translate(${innerWidth - 120}, -30)`}>
            {seriesValues.map((sv, i) => (
              <g key={sv} transform={`translate(${i * 80}, 0)`}>
                <rect width="12" height="12" fill={colors[i % colors.length]} />
                <text x="18" y="10" fontSize="10">{String(sv)}</text>
              </g>
            ))}
          </g>
        </g>
        
        {title && (
          <text x={chartWidth / 2} y={chartHeight + 30} textAnchor="middle" fontSize="14" fontWeight="bold" fill="#333">
            {title}
          </text>
        )}
      </svg>
    );
  }

  const chartWidth = 500;
  const chartHeight = 300;
  const padding = { left: 60, right: 40, top: 40, bottom: 60 };
  
  const innerWidth = chartWidth - padding.left - padding.right;
  const innerHeight = chartHeight - padding.top - padding.bottom;
  
  const maxValue = Math.max(...data.map(d => d[yField]));
  const barWidth = Math.max(30, innerWidth / data.length - 10);

  return (
    <svg width="100%" height="350" viewBox={`0 0 ${chartWidth} ${chartHeight + 50}`}>
      <g transform={`translate(${padding.left}, ${padding.top})`}>
        <line x1="0" y1={innerHeight} x2={innerWidth} y2={innerHeight} stroke="#ccc" strokeWidth="1" />
        <line x1="0" y1="0" x2="0" y2={innerHeight} stroke="#ccc" strokeWidth="1" />
        
        {[0, 0.25, 0.5, 0.75, 1].map((ratio, i) => (
          <g key={i}>
            <line 
              x1="0" 
              y1={innerHeight * (1 - ratio)} 
              x2={innerWidth} 
              y2={innerHeight * (1 - ratio)} 
              stroke="#eee" 
              strokeWidth="1" 
              strokeDasharray="4,4"
            />
            <text 
              x="-10" 
              y={innerHeight * (1 - ratio) + 4} 
              textAnchor="end" 
              fontSize="10" 
              fill="#666"
            >
              {(maxValue * ratio).toFixed(1)}
            </text>
          </g>
        ))}
        
        {data.map((item, index) => {
          const x = index * (innerWidth / data.length) + (innerWidth / data.length - barWidth) / 2;
          const value = item[yField];
          const barHeight = (value / maxValue) * innerHeight;
          
          return (
            <g key={index}>
              <rect
                x={x}
                y={innerHeight - barHeight}
                width={barWidth}
                height={barHeight}
                fill={colors[index % colors.length]}
                rx="2"
              />
              
              <text
                x={x + barWidth / 2}
                y={innerHeight - barHeight - 5}
                textAnchor="middle"
                fontSize="10"
                fill="#666"
              >
                {value.toFixed(1)}
              </text>
              
              <text
                x={x + barWidth / 2}
                y={innerHeight + 20}
                textAnchor="middle"
                fontSize="10"
                fill="#666"
              >
                {String(item[xField] || item.value).length > 6 
                  ? String(item[xField] || item.value).slice(0, 6) + '...' 
                  : String(item[xField] || item.value)}
              </text>
            </g>
          );
        })}
      </g>
      
      {title && (
        <text x={chartWidth / 2} y={chartHeight + 30} textAnchor="middle" fontSize="14" fontWeight="bold" fill="#333">
          {title}
        </text>
      )}
    </svg>
  );
}
