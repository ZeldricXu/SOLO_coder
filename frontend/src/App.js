import React, { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import DashboardView from './components/DashboardView';
import DashboardList from './components/DashboardList';
import DashboardEditor from './components/DashboardEditor';
import './App.css';

const API_BASE = window.location.origin;

function App() {
  const [view, setView] = useState('list');
  const [dashboards, setDashboards] = useState([]);
  const [currentDashboard, setCurrentDashboard] = useState(null);
  const [loading, setLoading] = useState(false);

  const fetchDashboards = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await axios.get(`${API_BASE}/api/v1/dashboards`);
      setDashboards(resp.data.data || []);
    } catch (e) {
      console.error('Failed to fetch dashboards', e);
    }
    setLoading(false);
  }, []);

  useEffect(() => {
    fetchDashboards();
  }, [fetchDashboards]);

  const handleCreate = () => {
    setCurrentDashboard(null);
    setView('editor');
  };

  const handleEdit = (dashboard) => {
    setCurrentDashboard(dashboard);
    setView('editor');
  };

  const handleView = (dashboard) => {
    setCurrentDashboard(dashboard);
    setView('dashboard');
  };

  const handleDelete = async (id) => {
    if (!window.confirm('确认删除此仪表盘？')) return;
    try {
      await axios.delete(`${API_BASE}/api/v1/dashboards/${id}`);
      fetchDashboards();
    } catch (e) {
      console.error('Failed to delete dashboard', e);
    }
  };

  const handleSave = async (dashboardData) => {
    try {
      if (currentDashboard) {
        await axios.put(
          `${API_BASE}/api/v1/dashboards/${currentDashboard.id}`,
          dashboardData
        );
      } else {
        await axios.post(`${API_BASE}/api/v1/dashboards`, dashboardData);
      }
      fetchDashboards();
      setView('list');
    } catch (e) {
      console.error('Failed to save dashboard', e);
    }
  };

  const handleBack = () => {
    setView('list');
    setCurrentDashboard(null);
  };

  return (
    <div className="app">
      <header className="app-header">
        <h1 onClick={handleBack} style={{ cursor: 'pointer' }}>
          📊 日志分析仪表盘
        </h1>
        {view === 'list' && (
          <button className="btn-primary" onClick={handleCreate}>
            + 新建仪表盘
          </button>
        )}
        {view !== 'list' && (
          <button className="btn-secondary" onClick={handleBack}>
            ← 返回列表
          </button>
        )}
      </header>

      <main className="app-main">
        {view === 'list' && (
          <DashboardList
            dashboards={dashboards}
            loading={loading}
            onView={handleView}
            onEdit={handleEdit}
            onDelete={handleDelete}
          />
        )}
        {view === 'dashboard' && currentDashboard && (
          <DashboardView dashboard={currentDashboard} apiBase={API_BASE} />
        )}
        {view === 'editor' && (
          <DashboardEditor
            dashboard={currentDashboard}
            onSave={handleSave}
            onCancel={handleBack}
          />
        )}
      </main>
    </div>
  );
}

export default App;
