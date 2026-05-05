import React, { useState } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, theme, Typography, Button, Space } from 'antd';
import {
  UploadOutlined,
  BarChartOutlined,
  PieChartOutlined,
  FileTextOutlined,
  HomeOutlined
} from '@ant-design/icons';
import DataImportPage from './pages/DataImportPage';
import AnalysisConfigPage from './pages/AnalysisConfigPage';
import ChartDisplayPage from './pages/ChartDisplayPage';
import ReportPreviewPage from './pages/ReportPreviewPage';

const { Header, Sider, Content } = Layout;
const { Title } = Typography;

const menuItems = [
  {
    key: '/',
    icon: <HomeOutlined />,
    label: '首页'
  },
  {
    key: '/import',
    icon: <UploadOutlined />,
    label: '数据导入'
  },
  {
    key: '/analysis',
    icon: <BarChartOutlined />,
    label: '分析配置'
  },
  {
    key: '/charts',
    icon: <PieChartOutlined />,
    label: '图表展示'
  },
  {
    key: '/report',
    icon: <FileTextOutlined />,
    label: '报告预览'
  }
];

function HomePage() {
  const navigate = useNavigate();
  
  return (
    <div style={{ padding: 48, textAlign: 'center' }}>
      <Title level={1}>SurveyAnalytics</Title>
      <Title level={4} style={{ color: '#666', marginBottom: 48 }}>
        问卷数据分析与报告生成平台
      </Title>
      
      <Space direction="vertical" size="large" style={{ width: '100%', maxWidth: 600, margin: '0 auto' }}>
        <div style={{ textAlign: 'left', padding: 24, background: '#f5f5f5', borderRadius: 8 }}>
          <Title level={4}>平台功能</Title>
          <ul style={{ listStyle: 'none', padding: 0, marginTop: 16 }}>
            <li style={{ marginBottom: 12 }}>
              <UploadOutlined style={{ marginRight: 8, color: '#1890ff' }} />
              数据导入：支持Excel/CSV格式，自动识别字段类型
            </li>
            <li style={{ marginBottom: 12 }}>
              <BarChartOutlined style={{ marginRight: 8, color: '#1890ff' }} />
              统计分析：频数统计、描述性统计、交叉分析
            </li>
            <li style={{ marginBottom: 12 }}>
              <PieChartOutlined style={{ marginRight: 8, color: '#1890ff' }} />
              可视化：柱状图、饼图、箱线图等多种图表
            </li>
            <li>
              <FileTextOutlined style={{ marginRight: 8, color: '#1890ff' }} />
              报告生成：自动生成分析报告，支持Word/PDF导出
            </li>
          </ul>
        </div>
        
        <Button type="primary" size="large" onClick={() => navigate('/import')}>
          开始使用
        </Button>
      </Space>
    </div>
  );
}

function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const {
    token: { colorBgContainer, borderRadiusLG }
  } = theme.useToken();

  const selectedKey = location.pathname === '/' ? '/' : 
    menuItems.find(item => location.pathname.startsWith(item.key))?.key || '/import';

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ 
        display: 'flex', 
        alignItems: 'center', 
        background: '#001529',
        padding: '0 24px'
      }}>
        <div style={{ color: 'white', fontSize: 20, fontWeight: 'bold', marginRight: 48 }}>
          SurveyAnalytics
        </div>
      </Header>
      
      <Layout>
        <Sider width={200} style={{ background: colorBgContainer }}>
          <Menu
            mode="inline"
            selectedKeys={[selectedKey]}
            style={{ height: '100%', borderRight: 0 }}
            items={menuItems}
            onClick={({ key }) => navigate(key)}
          />
        </Sider>
        
        <Layout style={{ padding: '24px' }}>
          <Content
            style={{
              padding: 24,
              margin: 0,
              minHeight: 280,
              background: colorBgContainer,
              borderRadius: borderRadiusLG
            }}
          >
            <Routes>
              <Route path="/" element={<HomePage />} />
              <Route path="/import" element={<DataImportPage />} />
              <Route path="/analysis" element={<AnalysisConfigPage />} />
              <Route path="/charts" element={<ChartDisplayPage />} />
              <Route path="/report" element={<ReportPreviewPage />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </Content>
        </Layout>
      </Layout>
    </Layout>
  );
}

export default function App() {
  return (
    <Router>
      <AppLayout />
    </Router>
  );
}
