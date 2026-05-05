import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import MainLayout from './layouts/MainLayout';
import LoginPage from './pages/Login/LoginPage';
import EventList from './pages/Events/EventList';
import EventForm from './pages/Events/EventForm';
import FormConfigPage from './pages/FormConfig/FormConfigPage';
import ReviewList from './pages/Reviews/ReviewList';
import CheckInPage from './pages/CheckIn/CheckInPage';
import AnalyticsPage from './pages/Analytics/AnalyticsPage';
import ReportConfigPage from './pages/Analytics/ReportConfigPage';
import './App.css';

const PrivateRoute = ({ children }) => {
  const token = localStorage.getItem('token');
  return token ? children : <Navigate to="/login" />;
};

function App() {
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#1890ff',
          borderRadius: 6,
        },
      }}
    >
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/"
            element={
              <PrivateRoute>
                <MainLayout />
              </PrivateRoute>
            }
          >
            <Route index element={<Navigate to="/events" replace />} />
            <Route path="events" element={<EventList />} />
            <Route path="events/create" element={<EventForm />} />
            <Route path="events/edit/:eventId" element={<EventForm />} />
            <Route path="form-config" element={<FormConfigPage />} />
            <Route path="reviews" element={<ReviewList />} />
            <Route path="check-in" element={<CheckInPage />} />
            <Route path="analytics" element={<AnalyticsPage />} />
            <Route path="report-config" element={<ReportConfigPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </ConfigProvider>
  );
}

export default App;
