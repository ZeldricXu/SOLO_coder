import React, { useState, useRef } from 'react';
import {
  Upload,
  Button,
  Card,
  Table,
  Form,
  Input,
  Select,
  Steps,
  Alert,
  Tag,
  Statistic,
  Row,
  Col,
  Divider,
  Space,
  message,
  Spin,
  Modal
} from 'antd';
import {
  UploadOutlined,
  InboxOutlined,
  CheckCircleOutlined,
  WarningOutlined,
  ReloadOutlined,
  SaveOutlined
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { surveyApi } from '../services/api';
import type { ImportResult, Field, ValidationError } from '../types';

const { Step } = Steps;
const { Dragger } = Upload;
const { Option } = Select;
const { TextArea } = Input;

interface FieldMappingForm {
  field_id: string;
  field_name: string;
  field_type: string;
  source_column: string;
  options?: string;
}

export default function DataImportPage() {
  const navigate = useNavigate();
  const [currentStep, setCurrentStep] = useState(0);
  const [file, setFile] = useState<File | null>(null);
  const [importResult, setImportResult] = useState<ImportResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [previewData, setPreviewData] = useState<any[]>([]);
  const [previewColumns, setPreviewColumns] = useState<any[]>([]);
  const [fieldMappings, setFieldMappings] = useState<FieldMappingForm[]>([]);
  const [showConfirmModal, setShowConfirmModal] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const fieldTypeOptions = [
    { value: 'single_choice', label: '单选题' },
    { value: 'multiple_choice', label: '多选题' },
    { value: 'numeric', label: '数值型' },
    { value: 'text', label: '文本型' },
    { value: 'date', label: '日期型' }
  ];

  const handleFileUpload = (options: any) => {
    const { file: uploadFile, onSuccess, onError } = options;
    const selectedFile = uploadFile as File;
    
    const ext = selectedFile.name.split('.').pop()?.toLowerCase();
    if (!['xlsx', 'xls', 'csv'].includes(ext || '')) {
      message.error('仅支持 Excel (.xlsx, .xls) 和 CSV 格式文件');
      onError?.({ message: 'Invalid file type' });
      return;
    }
    
    setFile(selectedFile);
    setCurrentStep(1);
    onSuccess?.({ status: 'success' });
  };

  const handleImport = async () => {
    if (!file) return;
    
    setLoading(true);
    try {
      const result = await surveyApi.import(file);
      setImportResult(result);
      
      if (result.fields && result.fields.length > 0) {
        const mappings: FieldMappingForm[] = result.fields.map((field, index) => ({
          field_id: field.field_id,
          field_name: field.field_name,
          field_type: field.field_type,
          source_column: field.field_name
        }));
        setFieldMappings(mappings);
      }
      
      message.success('文件导入成功');
      setCurrentStep(2);
    } catch (error: any) {
      message.error(error.message || '导入失败，请重试');
    } finally {
      setLoading(false);
    }
  };

  const handleLoadPreview = async () => {
    if (!importResult) return;
    
    setLoading(true);
    try {
      const preview = await surveyApi.getPreview(importResult.survey_id, 20);
      
      if (preview.preview_data && preview.preview_data.length > 0) {
        const columns = Object.keys(preview.preview_data[0]).map(key => ({
          title: key,
          dataIndex: key,
          key: key,
          ellipsis: true,
          width: 150
        }));
        setPreviewColumns(columns);
        setPreviewData(preview.preview_data);
      }
    } catch (error: any) {
      message.error(error.message || '加载预览失败');
    } finally {
      setLoading(false);
    }
  };

  const handleFieldMappingChange = (index: number, field: string, value: any) => {
    const newMappings = [...fieldMappings];
    newMappings[index] = { ...newMappings[index], [field]: value };
    setFieldMappings(newMappings);
  };

  const handleSaveMapping = async () => {
    if (!importResult) return;
    
    setLoading(true);
    try {
      const mappings = fieldMappings.map(m => ({
        ...m,
        options: m.options ? m.options.split(',').map(s => s.trim()) : undefined
      }));
      
      const result = await surveyApi.updateFieldMapping(importResult.survey_id, mappings);
      setImportResult(result);
      message.success('字段映射已保存');
      setShowConfirmModal(false);
    } catch (error: any) {
      message.error(error.message || '保存失败');
    } finally {
      setLoading(false);
    }
  };

  const getFieldTypeTag = (type: string) => {
    const typeMap: Record<string, { color: string; text: string } = {
      single_choice: { color: 'blue', text: '单选题' },
      multiple_choice: { color: 'purple', text: '多选题' },
      numeric: { color: 'green', text: '数值型' },
      text: { color: 'default', text: '文本型' },
      date: { color: 'orange', text: '日期型' }
    };
    const info = typeMap[type] || { color: 'default', text: type };
    return <Tag color={info.color}>{info.text}</Tag>;
  };

  const fieldMappingColumns = [
    {
      title: '字段标识',
      dataIndex: 'field_id',
      key: 'field_id',
      width: 180,
      render: (_: any, __: any, index: number) => (
        <Input
          value={fieldMappings[index]?.field_id}
          onChange={(e) => handleFieldMappingChange(index, 'field_id', e.target.value)}
          size="small"
        />
      )
    },
    {
      title: '字段名称',
      dataIndex: 'field_name',
      key: 'field_name',
      width: 180,
      render: (_: any, __: any, index: number) => (
        <Input
          value={fieldMappings[index]?.field_name}
          onChange={(e) => handleFieldMappingChange(index, 'field_name', e.target.value)}
          size="small"
        />
      )
    },
    {
      title: '字段类型',
      dataIndex: 'field_type',
      key: 'field_type',
      width: 150,
      render: (_: any, __: any, index: number) => (
        <Select
          value={fieldMappings[index]?.field_type}
          onChange={(value) => handleFieldMappingChange(index, 'field_type', value)}
          size="small"
          style={{ width: '100%' }}
        >
          {fieldTypeOptions.map(opt => (
            <Option key={opt.value} value={opt.value}>{opt.label}</Option>
          ))}
        </Select>
      )
    },
    {
      title: '选项列表（逗号分隔）',
      dataIndex: 'options',
      key: 'options',
      width: 200,
      render: (_: any, __: any, index: number) => {
        const fieldType = fieldMappings[index]?.field_type;
        if (fieldType !== 'single_choice' && fieldType !== 'multiple_choice') {
          return <span style={{ color: '#999' }}>-</span>;
        }
        return (
          <Input
            value={fieldMappings[index]?.options || ''}
            onChange={(e) => handleFieldMappingChange(index, 'options', e.target.value)}
            size="small"
            placeholder="例如: 男,女"
          />
        );
      }
    }
  ];

  const validationErrorColumns = [
    {
      title: '行号',
      dataIndex: 'row',
      key: 'row',
      width: 80
    },
    {
      title: '列名/字段',
      dataIndex: 'column',
      key: 'column',
      width: 150,
      render: (_: any, record: ValidationError) => record.column || record.field_id
    },
    {
      title: '错误信息',
      dataIndex: 'message',
      key: 'message'
    }
  ];

  const steps = [
    {
      title: '上传文件',
      icon: <UploadOutlined />,
      content: (
        <div style={{ textAlign: 'center', padding: '40px 0' }}>
          <Dragger
            customRequest={handleFileUpload}
            multiple={false}
            accept=".xlsx,.xls,.csv"
            fileList={file ? [{ uid: '1', name: file.name, status: 'done' as const } : []}
          >
            <p className="ant-upload-drag-icon">
              <InboxOutlined />
            </p>
            <p className="ant-upload-text">点击或拖拽文件到此处上传</p>
            <p className="ant-upload-hint">支持 Excel (.xlsx, .xls) 和 CSV 格式文件</p>
          </Dragger>
          
          {file && (
            <div style={{ marginTop: 24 }}>
              <Alert
                message={`已选择文件: ${file.name}`}
                type="info"
                showIcon
                action={
                  <Button size="small" onClick={handleImport} loading={loading}>
                    开始导入
                  </Button>
                }
              />
            </div>
          )}
        </div>
      )
    },
    {
      title: '导入结果',
      icon: <CheckCircleOutlined />,
      content: (
        <div>
          {importResult && (
          <>
            <Row gutter={16} style={{ marginBottom: 24 }}>
              <Col span={6}>
                <Card size="small">
                  <Statistic
                    title="总记录数"
                    value={importResult.total_records}
                    prefix={<span style={{ fontSize: 14 }}>条</span>}
                  />
                </Card>
              </Col>
              <Col span={6}>
                <Card size="small">
                  <Statistic
                    title="有效记录"
                    value={importResult.valid_records}
                    valueStyle={{ color: '#3f8600 }}
                    prefix={<CheckCircleOutlined />}
                  />
                </Card>
              </Col>
              <Col span={6}>
                <Card size="small">
                  <Statistic
                    title="无效记录"
                    value={importResult.invalid_records}
                    valueStyle={{ color: importResult.invalid_records > 0 ? '#cf1322' : '#666' }}
                    prefix={<WarningOutlined />}
                  />
                </Card>
              </Col>
              <Col span={6}>
                <Card size="small">
                  <Statistic
                    title="字段数量"
                    value={importResult.fields.length}
                    prefix={<span style={{ fontSize: 14 }}>个</span>}
                  />
                </Card>
              </Col>
            </Row>

            <Divider>字段信息</Divider>
            
            <Card size="small" title="自动识别的字段列表">
              <Table
                dataSource={importResult.fields}
                rowKey="field_id"
                size="small"
                pagination={false}
                columns={[
                  { title: '字段标识', dataIndex: 'field_id', key: 'field_id' },
                  { title: '字段名称', dataIndex: 'field_name', key: 'field_name' },
                  {
                    title: '字段类型',
                    dataIndex: 'field_type',
                    key: 'field_type',
                    render: (type: string) => getFieldTypeTag(type)
                  },
                  {
                    title: '选项/范围',
                    key: 'options',
                    render: (_: any, record: Field) => {
                      if (record.options) {
                        return record.options.join(', ');
                      }
                      if (record.range) {
                        return `[${record.range[0]}, ${record.range[1]}]`;
                      }
                      return '-';
                    }
                  }
                ]}
              />
            </Card>

            {importResult.validation_errors && importResult.validation_errors.length > 0 && (
              <>
                <Divider>数据校验错误</Divider>
                <Alert
                  message={`发现 ${importResult.validation_errors.length} 个数据校验问题（最多显示50条）`}
                  type="warning"
                  showIcon
                  style={{ marginBottom: 16 }}
                />
                <Table
                  dataSource={importResult.validation_errors}
                  rowKey={(record, index) => `${record.row}-${index}`}
                  size="small"
                  columns={validationErrorColumns}
                  pagination={{ pageSize: 10 }}
                />
              </>
            )}

            <div style={{ marginTop: 24, textAlign: 'center' }}>
              <Space>
                <Button onClick={handleLoadPreview} loading={loading}>
                  查看数据预览
                </Button>
                <Button type="primary" onClick={() => setShowConfirmModal(true)}>
                  配置字段映射
                </Button>
                <Button 
                  type="primary" onClick={() => navigate('/analysis')}>
                  继续分析
                </Button>
              </Space>
            </div>
          </>
        )}
        </div>
      )
    },
    {
      title: '数据预览',
      icon: <ReloadOutlined />,
      content: (
        <div>
          {previewData.length > 0 ? (
            <Card title="数据前20行预览">
              <Table
                dataSource={previewData}
                rowKey={(record, index) => index.toString()}
                size="small"
                scroll={{ x: 800 }}
                pagination={false}
                columns={previewColumns}
              />
            </Card>
          ) : (
            <div className="empty-container">
              <p>暂无预览数据</p>
              <Button onClick={handleLoadPreview} type="primary" style={{ marginTop: 16 }}>
                加载预览数据
              </Button>
            </div>
          )}
          
          <div style={{ marginTop: 24, textAlign: 'center' }}>
            <Button onClick={() => setCurrentStep(1)}>返回</Button>
          </div>
        </div>
      )
    }
  ];

  return (
    <div>
      <Card>
        <Steps current={currentStep} onChange={setCurrentStep} items={steps.map(s => ({ title: s.title, icon: s.icon })} />
        <div style={{ marginTop: 32 }}>
          {steps[currentStep].content}
        </div>
      </Card>

      <Modal
        title="字段映射配置"
        open={showConfirmModal}
        onCancel={() => setShowConfirmModal(false)}
        width={900}
        footer={
          <Space>
            <Button onClick={() => setShowConfirmModal(false)}>取消</Button>
            <Button type="primary" onClick={handleSaveMapping} loading={loading}>
              保存配置
            </Button>
          </Space>
        }
      >
        <p style={{ marginBottom: 16, color: '#666' }}>
          您可以在此调整字段的标识、名称和类型。对于单选题和多选题，还可以指定选项列表。
        </p>
        <Table
          dataSource={fieldMappings}
          rowKey={(record, index) => index.toString()}
          size="small"
          columns={fieldMappingColumns}
          pagination={false}
          scroll={{ y: 400 }}
        />
      </Modal>
    </div>
  );
}
