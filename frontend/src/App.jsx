import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { Layout, Menu, theme } from 'antd';
import {
  CodeOutlined,
  BarChartOutlined,
  MessageOutlined,
  FileTextOutlined,
  HomeOutlined
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';

import Dashboard from './pages/Dashboard';
import CodeChange from './pages/CodeChange';
import AnalysisResult from './pages/AnalysisResult';
import ReviewComment from './pages/ReviewComment';
import QualityReport from './pages/QualityReport';

const { Header, Sider, Content } = Layout;

const menuItems = [
  {
    key: '/',
    icon: <HomeOutlined />,
    label: '仪表板',
  },
  {
    key: '/code',
    icon: <CodeOutlined />,
    label: '代码变更',
  },
  {
    key: '/analysis',
    icon: <BarChartOutlined />,
    label: '分析结果',
  },
  {
    key: '/review',
    icon: <MessageOutlined />,
    label: '审查意见',
  },
  {
    key: '/report',
    icon: <FileTextOutlined />,
    label: '质量报告',
  },
];

function AppContent() {
  const location = useLocation();
  const navigate = useNavigate();
  const {
    token: { colorBgContainer, borderRadiusLG },
  } = theme.useToken();

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={240} theme="dark">
        <div style={{ 
          height: 64, 
          display: 'flex', 
          alignItems: 'center', 
          justifyContent: 'center',
          color: 'white',
          fontSize: 18,
          fontWeight: 'bold'
        }}>
          <CodeOutlined style={{ marginRight: 8 }} />
          CodeReview
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header style={{ 
          padding: '0 24px', 
          background: colorBgContainer,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between'
        }}>
          <h2 style={{ margin: 0 }}>
            {menuItems.find(item => item.key === location.pathname)?.label || '仪表板'}
          </h2>
        </Header>
        <Content style={{ margin: '24px' }}>
          <div
            style={{
              padding: 24,
              minHeight: 360,
              background: colorBgContainer,
              borderRadius: borderRadiusLG,
            }}
          >
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/code" element={<CodeChange />} />
              <Route path="/analysis" element={<AnalysisResult />} />
              <Route path="/review" element={<ReviewComment />} />
              <Route path="/report" element={<QualityReport />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </div>
        </Content>
      </Layout>
    </Layout>
  );
}

function App() {
  return (
    <Router>
      <AppContent />
    </Router>
  );
}

export default App;
