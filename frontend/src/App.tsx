import React from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';

const LoginPage = React.lazy(() => import('@/pages/LoginPage'));
const Layout = React.lazy(() => import('@/pages/Layout'));
const DashboardList = React.lazy(() => import('@/pages/DashboardList'));
const DashboardDetail = React.lazy(() => import('@/pages/DashboardDetail'));
const DataSourcePage = React.lazy(() => import('@/pages/DataSourcePage'));
const MetricPage = React.lazy(() => import('@/pages/MetricPage'));
const AlertPage = React.lazy(() => import('@/pages/AlertPage'));

const App: React.FC = () => {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<Layout />}>
        <Route index element={<Navigate to="/dashboards" replace />} />
        <Route path="dashboards" element={<DashboardList />} />
        <Route path="dashboards/:id" element={<DashboardDetail />} />
        <Route path="data-sources" element={<DataSourcePage />} />
        <Route path="metrics" element={<MetricPage />} />
        <Route path="alerts" element={<AlertPage />} />
      </Route>
    </Routes>
  );
};

export default App;
