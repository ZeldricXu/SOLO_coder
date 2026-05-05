import React, { useState, useEffect } from 'react';
import {
  Card,
  Button,
  Spin,
  message,
  Row,
  Col,
  Statistic,
  Descriptions,
  Divider,
  List,
  Alert,
  Space,
  Input,
  Tag,
  Modal,
  Tabs
} from 'antd';
import {
  FileWordOutlined,
  FilePdfOutlined,
  GenerateOutlined,
  ReloadOutlined,
  DownloadOutlined,
  CheckCircleOutlined
} from '@ant-design/icons';
import { reportApi } from '../services/api';
import type { Report, ReportPreview, ReportSection } from '../types';

const { TextArea } = Input;
const { TabPane } = Tabs;

export default function ReportPreviewPage() {
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [report, setReport] = useState<Report | null>(null);
  const [reportPreview, setReportPreview] = useState<ReportPreview | null>(null);
  const [reportTitle, setReportTitle] = useState<string>('');
  const [activeSection, setActiveSection] = useState<number>(0);
  const [showConfirmModal, setShowConfirmModal] = useState(false);

  const surveyId = 'survey_001';

  const handleGenerateReport = async () => {
    setLoading(true);
    try {
      const result = await reportApi.generate(surveyId, reportTitle || undefined);
      setReport(result);
      
      const preview = await reportApi.getPreview(result.report_id);
      setReportPreview(preview);
      
      message.success('报告生成成功');
    } catch (error: any) {
      message.error(error.message || '生成报告失败');
    } finally {
      setLoading(false);
    }
  };

  const handleExport = async (format: 'word' | 'pdf') => {
    if (!report) {
      message.warning('请先生成报告');
      return;
    }

    setExporting(true);
    try {
      const blob = await reportApi.export(report.report_id, format);
      
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${report.title}.${format === 'word' ? 'docx' : 'pdf'}`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);
      
      message.success(`${format === 'word' ? 'Word' : 'PDF'} 导出成功`);
    } catch (error: any) {
      message.error(error.message || '导出失败');
    } finally {
      setExporting(false);
    }
  };

  const renderSection = (section: ReportSection, index: number) => {
    return (
      <Card 
        key={index} 
        size="small" 
        style={{ marginBottom: 16 }}
        extra={
          <Tag color="blue">
            {getSectionTypeLabel(section.section_type)}
          </Tag>
        }
      >
        <h3 style={{ marginBottom: 12, color: '#1890ff' }}>{section.title}</h3>
        
        <div style={{ whiteSpace: 'pre-line', marginBottom: 16, lineHeight: 1.8 }}>
          {section.content}
        </div>
        
        {section.data && (
          <div>
            <Divider style={{ margin: '12px 0' }}>详细数据</Divider>
            {renderSectionData(section)}
          </div>
        )}
      </Card>
    );
  };

  const getSectionTypeLabel = (type: string) => {
    const typeMap: Record<string, string> = {
      introduction: '概述',
      frequency: '频数分析',
      descriptive: '描述性统计',
      cross_analysis: '交叉分析',
      conclusion: '结论'
    };
    return typeMap[type] || type;
  };

  const renderSectionData = (section: ReportSection) => {
    const { section_type, data } = section;
    
    if (section_type === 'frequency' && data?.statistics) {
      return (
        <div>
          {data.statistics.map((stat: any, idx: number) => (
            <div key={idx} style={{ marginBottom: 16 }}>
              <h4 style={{ marginBottom: 8 }}>{stat.field_name}</h4>
              <List
                size="small"
                dataSource={stat.data?.frequencies || []}
                renderItem={(item: any) => (
                  <List.Item>
                    <Space>
                      <span>{item.value}:</span>
                      <Tag>{item.count} 人</Tag>
                      <Tag color="blue">{item.percentage}%</Tag>
                    </Space>
                  </List.Item>
                )}
              />
            </div>
          ))}
        </div>
      );
    }
    
    if (section_type === 'descriptive' && data?.statistics) {
      return (
        <div>
          {data.statistics.map((stat: any, idx: number) => (
            <div key={idx} style={{ marginBottom: 16 }}>
              <h4 style={{ marginBottom: 8 }}>{stat.field_name}</h4>
              <Descriptions bordered size="small" column={4}>
                <Descriptions.Item label="样本数">{stat.data?.count}</Descriptions.Item>
                <Descriptions.Item label="均值">{stat.data?.mean?.toFixed(4)}</Descriptions.Item>
                <Descriptions.Item label="中位数">{stat.data?.median?.toFixed(4)}</Descriptions.Item>
                <Descriptions.Item label="标准差">{stat.data?.std?.toFixed(4)}</Descriptions.Item>
                <Descriptions.Item label="最小值">{stat.data?.min?.toFixed(4)}</Descriptions.Item>
                <Descriptions.Item label="最大值">{stat.data?.max?.toFixed(4)}</Descriptions.Item>
                <Descriptions.Item label="25分位">{stat.data?.q25?.toFixed(4)}</Descriptions.Item>
                <Descriptions.Item label="75分位">{stat.data?.q75?.toFixed(4)}</Descriptions.Item>
              </Descriptions>
            </div>
          ))}
        </div>
      );
    }
    
    if (section_type === 'cross_analysis' && data?.analyses) {
      return (
        <div>
          {data.analyses.map((analysis: any, idx: number) => (
            <div key={idx} style={{ marginBottom: 24 }}>
              <h4 style={{ marginBottom: 8 }}>
                分析 {idx + 1}: {analysis.variables?.join(' vs ')}
              </h4>
              
              {analysis.significance && (
                <Alert
                  style={{ marginBottom: 12 }}
                  message={
                    <Space>
                      <span>检验方法: {analysis.significance.test_type}</span>
                      <span>P值: {analysis.significance.p_value}</span>
                      {analysis.significance.significant ? (
                        <Tag color="green">显著</Tag>
                      ) : (
                        <Tag color="default">不显著</Tag>
                      )}
                    </Space>
                  }
                  type={analysis.significance.significant ? 'success' : 'info'}
                  showIcon
                />
              )}
              
              <p style={{ color: '#666', fontSize: 12 }}>
                交叉表数据包含 {analysis.cross_table?.length || 0} 行
              </p>
            </div>
          ))}
        </div>
      );
    }
    
    return null;
  };

  return (
    <Spin spinning={loading}>
      <div>
        <Card title="报告生成设置">
          <Row gutter={16}>
            <Col span={12}>
              <Input
                placeholder="请输入报告标题（可选，留空将使用默认标题）"
                value={reportTitle}
                onChange={(e) => setReportTitle(e.target.value)}
                prefix={<span style={{ color: '#999' }}>标题:</span>}
              />
            </Col>
            <Col span={12}>
              <Space>
                <Button
                  type="primary"
                  icon={<GenerateOutlined />}
                  onClick={handleGenerateReport}
                  loading={loading}
                >
                  生成报告
                </Button>
                {report && (
                  <>
                    <Button
                      icon={<FileWordOutlined />}
                      onClick={() => handleExport('word')}
                      loading={exporting}
                    >
                      导出 Word
                    </Button>
                    <Button
                      icon={<FilePdfOutlined />}
                      onClick={() => handleExport('pdf')}
                      loading={exporting}
                    >
                      导出 PDF
                    </Button>
                  </>
                )}
              </Space>
            </Col>
          </Row>
        </Card>

        {report && (
          <Card 
            title="报告概览" 
            style={{ marginTop: 16 }}
            extra={
              <Space>
                <Tag color="blue">{report.sections.length} 个章节</Tag>
                <Tag color="green">
                  <CheckCircleOutlined /> 已生成
                </Tag>
              </Space>
            }
          >
            <Descriptions bordered size="small" column={3}>
              <Descriptions.Item label="报告ID">{report.report_id}</Descriptions.Item>
              <Descriptions.Item label="报告标题">{report.title}</Descriptions.Item>
              <Descriptions.Item label="生成时间">{report.created_at}</Descriptions.Item>
            </Descriptions>
          </Card>
        )}

        {reportPreview && (
          <Card title="报告目录" style={{ marginTop: 16 }}>
            <List
              dataSource={reportPreview.toc}
              renderItem={(item: any) => (
                <List.Item
                  style={{
                    cursor: 'pointer',
                    background: activeSection === item.section_number - 1 ? '#e6f7ff' : undefined,
                    borderRadius: 4,
                    padding: '8px 16px',
                    marginBottom: 4
                  }}
                  onClick={() => setActiveSection(item.section_number - 1)}
                >
                  <List.Item.Meta
                    title={
                      <span style={{ fontWeight: activeSection === item.section_number - 1 ? 'bold' : 'normal' }}>
                        {item.section_number}. {item.title}
                      </span>
                    }
                    description={
                      <Tag color="blue">{getSectionTypeLabel(item.type)}</Tag>
                    }
                  />
                </List.Item>
              )}
            />
          </Card>
        )}

        {report && (
          <Card 
            title="报告内容预览" 
            style={{ marginTop: 16 }}
            extra={
              <Button size="small" icon={<ReloadOutlined />} onClick={handleGenerateReport}>
                重新生成
              </Button>
            }
          >
            {report.sections.map((section, index) => renderSection(section, index))}
          </Card>
        )}

        {!report && (
          <div className="empty-container" style={{ marginTop: 48 }}>
            <GenerateOutlined style={{ fontSize: 64, color: '#999', marginBottom: 16 }} />
            <h3 style={{ color: '#666', marginBottom: 8 }}>暂无报告</h3>
            <p style={{ color: '#999' }}>请点击上方"生成报告"按钮创建分析报告</p>
            
            <Alert
              style={{ marginTop: 24, maxWidth: 500 }}
              message="报告生成说明"
              type="info"
              showIcon
              description={
                <div>
                  <p>报告将自动包含以下内容：</p>
                  <ul style={{ marginTop: 8, paddingLeft: 20 }}>
                    <li>问卷概述 - 样本量、字段数量等基础信息</li>
                    <li>频数分析 - 分类变量的分布统计</li>
                    <li>描述性统计 - 数值变量的统计摘要</li>
                    <li>交叉分析 - 变量间的关联分析结果</li>
                    <li>分析结论 - 综合分析结果的总结</li>
                  </ul>
                </div>
              }
            />
          </div>
        )}

        <Card title="导出说明" style={{ marginTop: 16 }}>
          <Row gutter={16}>
            <Col span={12}>
              <Alert
                message="Word 导出 (.docx)"
                type="info"
                showIcon
                icon={<FileWordOutlined />}
                description="导出为 Microsoft Word 格式，支持完整的表格和格式，便于进一步编辑和修改。"
              />
            </Col>
            <Col span={12}>
              <Alert
                message="PDF 导出 (.pdf)"
                type="info"
                showIcon
                icon={<FilePdfOutlined />}
                description="导出为 PDF 格式，保持固定布局，适合分享和打印，确保在不同设备上显示一致。"
              />
            </Col>
          </Row>
        </Card>
      </div>
    </Spin>
  );
}
