import React from 'react';
import { Routes, Route, useNavigate } from 'react-router-dom';
import { ConfigProvider, theme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import HomePage from './pages/HomePage';
import FormEditorPage from './pages/FormEditorPage';
import DataCollectionPage from './pages/DataCollectionPage';

const AppContent = () => {
  const navigate = useNavigate();

  const handleEditForm = () => {
    navigate('/editor');
  };

  const handleViewData = () => {
    navigate('/data');
  };

  const handleBackToHome = () => {
    navigate('/');
  };

  return (
    <Routes>
      <Route
        path="/"
        element={
          <HomePage
            onEditForm={handleEditForm}
            onViewData={handleViewData}
          />
        }
      />
      <Route
        path="/editor"
        element={
          <FormEditorPage
            onBack={handleBackToHome}
          />
        }
      />
      <Route
        path="/data"
        element={
          <DataCollectionPage
            onBack={handleBackToHome}
          />
        }
      />
    </Routes>
  );
};

const App = () => {
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: theme.defaultAlgorithm,
        token: {
          colorPrimary: '#1890ff',
        },
      }}
    >
      <AppContent />
    </ConfigProvider>
  );
};

export default App;
