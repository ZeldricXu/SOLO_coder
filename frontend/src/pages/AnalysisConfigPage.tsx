import React, { useState, useEffect } from 'react';
import {
  Card,
  Form,
  Select,
  Button,
  Radio,
  Table,
  Tag,
  Divider,
  Row,
  Col,
  Statistic,
  Alert,
  Space,
  Spin,
  message,
  Tabs,
  List,
  Descriptions
} from 'antd';
import {
  BarChartOutlined,
  LineChartOutlined,
  PieChartOutlined,
  ReloadOutlined,
  PlayCircleOutlined,
  CheckCircleOutlined,
  DeleteOutlined
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { analysisApi } from '../services/api';
import type {
  Field,
  StatisticsData,
  FrequencyResult,
  DescriptiveStats,
  CrossAnalysisResult,
  SignificanceResult
} from '../types';

const { Option } = Select;
const { TabPane } = Tabs;

interface FieldOption {
  label: string;
  value: string;
  type: string;
}

export default function AnalysisConfigPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [statistics, setStatistics] = useState<StatisticsData | null>(null);
  const [crossAnalyses, setCrossAnalyses] = useState<CrossAnalysisResult[]>([]);
  const [selectedField, setSelectedField] = useState<string>('');
  const [selectedAnalysis, setSelectedAnalysis] = useState<CrossAnalysisResult | null>(null);
  const [activeTab, setActiveTab] = useState('basic');
  
  const [crossForm] = Form.useForm();
  const [availableFields, setAvailableFields] = useState<FieldOption[]>([]);

  const surveyId = 'survey_001';

  useEffect(() => {
    loadStatistics();
    loadCrossAnalyses();
  }, []);

  const loadStatistics = async () => {
    setLoading(true);
    try {
      const result = await analysisApi.getStatistics(surveyId);
      setStatistics(result);
      
      const fields: FieldOption[] = result.statistics.map(s => ({
        label: s.field_name,
        value: s.field_id,
        type: s.field_type
      }));
      setAvailableFields(fields);
      
    } catch (error: any) {
      message.error(error.message || '加载统计数据失败');
    } finally {
      setLoading(false);
    }
  };

  const loadCrossAnalyses = async () => {
    try {
      const analyses = await analysisApi.getSurveyCrossAnalyses(surveyId);
      setCrossAnalyses(analyses);
    } catch (error) {
      console.error('Failed to load cross analyses:', error);
    }
  };

  const handleFieldSelect = async (fieldId: string) => {
    setSelectedField(fieldId);
  };

  const handleCrossAnalysis = async (values: {
    rowVariable: string;
    colVariable: string;
    analysisType: string;
  }) => {
    if (values.rowVariable === values.colVariable) {
      message.error('请选择不同的变量进行交叉分析');
      return;
    }

    setLoading(true);
    try {
      const result = await analysisApi.performCrossAnalysis({
        survey_id: surveyId,
        variables: [values.rowVariable, values.colVariable],
        analysis_type: values.analysisType
      });
      
      setCrossAnalyses(prev => [...prev, result]);
      setSelectedAnalysis(result);
      message.success('交叉分析完成');
    } catch (error: any) {
      message.error(error.message || '交叉分析失败');
    } finally {
      setLoading(false);
    }
  };

  const getFieldName = (fieldId: string) => {
    const field = availableFields.find(f => f.value === fieldId);
    return field?.label || fieldId;
  };

  const getFieldTypeLabel = (type: string) => {
    const typeMap: Record<string, string> = {
      single_choice: '单选题',
      multiple_choice: '多选题',
      numeric: '数值型',
      text: '文本型',
      date: '日期型'
    };
    return typeMap[type] || type;
  };

  const getFieldTypeTag = (type: string) => {
    const colorMap: Record<string, string> = {
      single_choice: 'blue',
      multiple_choice: 'purple',
      numeric: 'green',
      text: 'default',
      date: 'orange'
    };
    return <Tag color={colorMap[type] || 'default'}>{getFieldTypeLabel(type)}</Tag>;
  };

  const selectedStatistic = statistics?.statistics.find(s => s.field_id === selectedField);

  const crossAnalysisColumns = [
    {
      title: '行变量',
      dataIndex: ['variables', 0],
      key: 'rowVar',
      render: (fieldId: string) => getFieldName(fieldId)
    },
    {
      title: '列变量',
      dataIndex: ['variables', 1],
      key: 'colVar',
      render: (fieldId: string) => getFieldName(fieldId)
    },
    {
      title: '检验方法',
      key: 'testType',
      render: (_: any, record: CrossAnalysisResult) => 
        record.significance?.test_type || '-'
    },
    {
      title: 'P值',
      key: 'pValue',
      render: (_: any, record: CrossAnalysisResult) => 
        record.significance ? record.significance.p_value.toFixed(6) : '-'
    },
    {
      title: '显著性',
      key: 'significant',
      render: (_: any, record: CrossAnalysisResult) => {
        if (!record.significance) return '-';
        return record.significance.significant ? (
          <Tag color="green" className="significant">显著 (p<0.05)</Tag>
        ) : (
          <Tag color="default" className="not-significant">不显著</Tag>
        );
      }
    },
    {
      title: '操作',
      key: 'action',
      render: (_: any, record: CrossAnalysisResult) => (
        <Space>
          <Button type="link" size="small" onClick={() => setSelectedAnalysis(record)}>
            查看详情
          </Button>
        </Space>
      )
    }
  ];

  return (
    <Spin spinning={loading}>
      <div>
        <Tabs activeKey={activeTab} onChange={setActiveTab}>
          <TabPane tab="基础统计" key="basic">
            <Row gutter={16}>
              <Col span={8}>
                <Card title="变量列表" size="small">
                  <List
                    dataSource={statistics?.statistics || []}
                    locale={{ emptyText: '暂无数据，请先导入问卷' }}
                    renderItem={(item) => (
                      <List.Item
                        style={{
                          cursor: 'pointer',
                          background: selectedField === item.field_id ? '#e6f7ff' : undefined,
                          padding: '8px 12px',
                          borderRadius: 4,
                          marginBottom: 4
                        }}
                        onClick={() => handleFieldSelect(item.field_id)}
                      >
                        <List.Item.Meta
                          title={item.field_name}
                          description={
                            <Space>
                              {getFieldTypeTag(item.field_type)}
                              <Tag>{item.type === 'frequency' ? '频数分析' : '描述性统计'}</Tag>
                            </Space>
                          }
                        />
                      </List.Item>
                    )}
                  />
                </Card>
              </Col>

              <Col span={16}>
                {selectedStatistic ? (
                  <Card title={`分析结果: ${selectedStatistic.field_name}`}>
                    {selectedStatistic.type === 'frequency' && (
                      <div>
                        <Descriptions bordered size="small" column={2}>
                          <Descriptions.Item label="字段标识">
                            {selectedStatistic.field_id}
                          </Descriptions.Item>
                          <Descriptions.Item label="字段类型">
                            {getFieldTypeTag(selectedStatistic.field_type)}
                          </Descriptions.Item>
                        </Descriptions>
                        
                        <Divider>频数分布</Divider>
                        
                        {(() => {
                          const data = selectedStatistic.data as FrequencyResult;
                          return (
                            <>
                              <Row gutter={16} style={{ marginBottom: 16 }}>
                                <Col span={8}>
                                  <Card size="small">
                                    <Statistic title="有效样本" value={data.total_valid} />
                                  </Card>
                                </Col>
                                <Col span={8}>
                                  <Card size="small">
                                    <Statistic title="缺失值" value={data.missing_count} />
                                  </Card>
                                </Col>
                                <Col span={8}>
                                  <Card size="small">
                                    <Statistic title="选项数" value={data.frequencies.length} />
                                  </Card>
                                </Col>
                              </Row>
                              
                              <Table
                                dataSource={data.frequencies}
                                rowKey={(record, index) => index.toString()}
                                size="small"
                                pagination={false}
                                columns={[
                                  { title: '选项值', dataIndex: 'value', key: 'value' },
                                  { title: '数量', dataIndex: 'count', key: 'count' },
                                  {
                                    title: '百分比',
                                    key: 'percentage',
                                    render: (_, record) => `${record.percentage}%`
                                  },
                                  {
                                    title: '占比可视化',
                                    key: 'bar',
                                    width: 200,
                                    render: (_, record) => (
                                      <div style={{ 
                                        height: 20, 
                                        background: '#f0f0f0', 
                                        borderRadius: 4,
                                        overflow: 'hidden'
                                      }}>
                                        <div style={{
                                          height: '100%',
                                          width: `${Math.min(record.percentage, 100)}%`,
                                          background: '#1890ff',
                                          borderRadius: 4,
                                          transition: 'width 0.3s'
                                        }} />
                                      </div>
                                    )
                                  }
                                ]}
                              />
                            </>
                          );
                        })()}
                      </div>
                    )}

                    {selectedStatistic.type === 'descriptive' && (
                      <div>
                        {(() => {
                          const data = selectedStatistic.data as DescriptiveStats;
                          return (
                            <>
                              <Descriptions bordered size="small" column={4}>
                                <Descriptions.Item label="样本数">{data.count}</Descriptions.Item>
                                <Descriptions.Item label="均值">{data.mean.toFixed(4)}</Descriptions.Item>
                                <Descriptions.Item label="中位数">{data.median.toFixed(4)}</Descriptions.Item>
                                <Descriptions.Item label="标准差">{data.std.toFixed(4)}</Descriptions.Item>
                                <Descriptions.Item label="最小值">{data.min.toFixed(4)}</Descriptions.Item>
                                <Descriptions.Item label="最大值">{data.max.toFixed(4)}</Descriptions.Item>
                                <Descriptions.Item label="25分位">{data.q25.toFixed(4)}</Descriptions.Item>
                                <Descriptions.Item label="75分位">{data.q75.toFixed(4)}</Descriptions.Item>
                              </Descriptions>
                              
                              <Divider>统计分布</Divider>
                              
                              <Card size="small">
                                <Row gutter={16}>
                                  <Col span={12}>
                                    <Alert
                                      message="集中趋势"
                                      type="info"
                                      showIcon
                                      description={
                                        <div>
                                          <p>均值: {data.mean.toFixed(4)}</p>
                                          <p>中位数: {data.median.toFixed(4)}</p>
                                          <p>差异: {Math.abs(data.mean - data.median).toFixed(4)}</p>
                                        </div>
                                      }
                                    />
                                  </Col>
                                  <Col span={12}>
                                    <Alert
                                      message="离散程度"
                                      type="info"
                                      showIcon
                                      description={
                                        <div>
                                          <p>标准差: {data.std.toFixed(4)}</p>
                                          <p>四分位距: {(data.q75 - data.q25).toFixed(4)}</p>
                                          <p>全距: {(data.max - data.min).toFixed(4)}</p>
                                        </div>
                                      }
                                    />
                                  </Col>
                                </Row>
                              </Card>
                            </>
                          );
                        })()}
                      </div>
                    )}
                  </Card>
                ) : (
                  <div className="empty-container">
                    <p>请从左侧选择一个变量查看分析结果</p>
                  </div>
                )}
              </Col>
            </Row>
          </TabPane>

          <TabPane tab="交叉分析" key="cross">
            <Card title="交叉分析配置">
              <Form
                form={crossForm}
                layout="vertical"
                onFinish={handleCrossAnalysis}
                initialValues={{ analysisType: 'comparison' }}
              >
                <Row gutter={16}>
                  <Col span={8}>
                    <Form.Item
                      name="rowVariable"
                      label="行变量"
                      rules={[{ required: true, message: '请选择行变量' }]}
                    >
                      <Select placeholder="选择行变量">
                        {availableFields.map(field => (
                          <Option key={field.value} value={field.value}>
                            {field.label} {getFieldTypeTag(field.type)}
                          </Option>
                        ))}
                      </Select>
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item
                      name="colVariable"
                      label="列变量"
                      rules={[{ required: true, message: '请选择列变量' }]}
                    >
                      <Select placeholder="选择列变量">
                        {availableFields.map(field => (
                          <Option key={field.value} value={field.value}>
                            {field.label} {getFieldTypeTag(field.type)}
                          </Option>
                        ))}
                      </Select>
                    </Form.Item>
                  </Col>
                  <Col span={8}>
                    <Form.Item
                      name="analysisType"
                      label="分析类型"
                    >
                      <Radio.Group>
                        <Radio value="comparison">比较分析</Radio>
                        <Radio value="association">关联分析</Radio>
                      </Radio.Group>
                    </Form.Item>
                  </Col>
                </Row>
                
                <Form.Item>
                  <Button type="primary" icon={<PlayCircleOutlined />} loading={loading}>
                    执行交叉分析
                  </Button>
                </Form.Item>
              </Form>
            </Card>

            <Card title="交叉分析历史" style={{ marginTop: 16 }}>
              {crossAnalyses.length > 0 ? (
                <Table
                  dataSource={crossAnalyses}
                  rowKey="analysis_id"
                  size="small"
                  columns={crossAnalysisColumns}
                  pagination={{ pageSize: 10 }}
                />
              ) : (
                <div className="empty-container">
                  <p>暂无交叉分析结果，请先执行交叉分析</p>
                </div>
              )}
            </Card>

            {selectedAnalysis && (
              <Card 
                title="交叉分析详情" 
                style={{ marginTop: 16 }}
                extra={
                  <Button size="small" onClick={() => setSelectedAnalysis(null)}>
                    关闭
                  </Button>
                }
              >
                <Descriptions bordered size="small" column={3}>
                  <Descriptions.Item label="分析ID">
                    {selectedAnalysis.analysis_id}
                  </Descriptions.Item>
                  <Descriptions.Item label="行变量">
                    {getFieldName(selectedAnalysis.variables[0])}
                  </Descriptions.Item>
                  <Descriptions.Item label="列变量">
                    {getFieldName(selectedAnalysis.variables[1])}
                  </Descriptions.Item>
                </Descriptions>

                {selectedAnalysis.significance && (
                  <Alert
                    style={{ marginTop: 16 }}
                    message={
                      <Space>
                        <span>检验方法: {selectedAnalysis.significance.test_type}</span>
                        <span>P值: {selectedAnalysis.significance.p_value.toFixed(6)}</span>
                        {selectedAnalysis.significance.significant ? (
                          <Tag color="green">显著 (p<0.05)</Tag>
                        ) : (
                          <Tag color="default">不显著</Tag>
                        )}
                      </Space>
                    }
                    type={selectedAnalysis.significance.significant ? 'success' : 'info'}
                    showIcon
                  />
                )}

                <Divider>交叉表</Divider>
                
                {(() => {
                  const table = selectedAnalysis.cross_table;
                  if (table.length === 0) return null;
                  
                  const firstRow = table[0];
                  const hasMean = 'mean' in firstRow.col_values;
                  
                  if (hasMean) {
                    return (
                      <Table
                        dataSource={table}
                        rowKey={(record, index) => index.toString()}
                        size="small"
                        pagination={false}
                        columns={[
                          { title: '分组', dataIndex: 'row', key: 'row' },
                          { 
                            title: '均值', 
                            key: 'mean',
                            render: (_, record) => record.col_values.mean?.toFixed(4) || '-'
                          },
                          { 
                            title: '标准差', 
                            key: 'std',
                            render: (_, record) => record.col_values.std?.toFixed(4) || '-'
                          },
                          { 
                            title: '样本数', 
                            key: 'count',
                            render: (_, record) => record.col_values.count || 0
                          },
                          { 
                            title: '最小值', 
                            key: 'min',
                            render: (_, record) => record.col_values.min?.toFixed(4) || '-'
                          },
                          { 
                            title: '最大值', 
                            key: 'max',
                            render: (_, record) => record.col_values.max?.toFixed(4) || '-'
                          }
                        ]}
                      />
                    );
                  } else {
                    const colNames = Object.keys(table[0].col_values).filter(k => !k.startsWith('_'));
                    
                    const columns = [
                      { title: '分组', dataIndex: 'row', key: 'row' },
                      ...colNames.map(colName => ({
                        title: colName,
                        key: colName,
                        render: (_: any, record: typeof table[0]) => {
                          const val = record.col_values[colName];
                          if (typeof val === 'object') {
                            return `${val.count} (${val.row_percentage}%)`;
                          }
                          return val;
                        }
                      })),
                      {
                        title: '合计',
                        key: 'total',
                        render: (_: any, record: typeof table[0]) => {
                          const total = record.col_values['_total'];
                          if (total) {
                            return `${total.count} (${total.percentage}%)`;
                          }
                          return '-';
                        }
                      }
                    ];
                    
                    return (
                      <Table
                        dataSource={table}
                        rowKey={(record, index) => index.toString()}
                        size="small"
                        pagination={false}
                        columns={columns}
                        scroll={{ x: 800 }}
                      />
                    );
                  }
                })()}
              </Card>
            )}
          </TabPane>
        </Tabs>

        <div style={{ marginTop: 24, textAlign: 'center' }}>
          <Button type="primary" onClick={() => navigate('/charts')}>
            查看可视化图表
          </Button>
        </div>
      </div>
    </Spin>
  );
}
