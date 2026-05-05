import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ConfigProvider, Spin } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { AuthProvider, useAuth } from './contexts/AuthContext';
import { NotificationProvider } from './contexts/NotificationContext';
import AppLayout from './components/AppLayout';
import Login from './pages/Login';
import TaskListView from './components/TaskListView';
import EnhancedGanttView from './components/EnhancedGanttView';
import CalendarView from './components/CalendarView';
import 'antd/dist/reset.css';

const PrivateRoute = ({ children }) => {
  const { user, loading } = useAuth();
  
  if (loading) {
    return (
      <div style={{ 
        display: 'flex', 
        justifyContent: 'center', 
        alignItems: 'center', 
        height: '100vh' 
      }}>
        <Spin size="large" />
      </div>
    );
  }
  
  return user ? children : <Navigate to="/login" replace />;
};

const PublicRoute = ({ children }) => {
  const { user, loading } = useAuth();
  
  if (loading) {
    return (
      <div style={{ 
        display: 'flex', 
        justifyContent: 'center', 
        alignItems: 'center', 
        height: '100vh' 
      }}>
        <Spin size="large" />
      </div>
    );
  }
  
  return user ? <Navigate to="/" replace /> : children;
};

const AppContent = () => {
  return (
    <NotificationProvider>
      <Routes>
        <Route 
          path="/login" 
          element={
            <PublicRoute>
              <Login />
            </PublicRoute>
          } 
        />
        
        <Route 
          path="/" 
          element={
            <PrivateRoute>
              <AppLayout>
                <TaskListView />
              </AppLayout>
            </PrivateRoute>
          } 
        />
        
        <Route 
          path="/gantt" 
          element={
            <PrivateRoute>
              <AppLayout>
                <EnhancedGanttView />
              </AppLayout>
            </PrivateRoute>
          } 
        />
        
        <Route 
          path="/calendar" 
          element={
            <PrivateRoute>
              <AppLayout>
                <CalendarView />
              </AppLayout>
            </PrivateRoute>
          } 
        />
        
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </NotificationProvider>
  );
};

const App = () => {
  return (
    <ConfigProvider locale={zhCN}>
      <BrowserRouter>
        <AuthProvider>
          <AppContent />
        </AuthProvider>
      </BrowserRouter>
    </ConfigProvider>
  );
};

export default App;
