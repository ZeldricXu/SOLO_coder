import React from 'react';

function DashboardList({ dashboards, loading, onView, onEdit, onDelete }) {
  if (loading) {
    return <div className="loading">加载中...</div>;
  }

  if (dashboards.length === 0) {
    return (
      <div className="empty-state">
        <h3>暂无仪表盘</h3>
        <p>点击"新建仪表盘"创建第一个监控面板</p>
      </div>
    );
  }

  return (
    <div className="dashboard-grid">
      {dashboards.map((d) => (
        <div key={d.id} className="dashboard-card">
          <h3>{d.name}</h3>
          <p>{d.description || '无描述'}</p>
          <div style={{ fontSize: 12, color: '#64748b', marginBottom: 12 }}>
            {d.panels.length} 个面板 · 更新于 {new Date(d.updated_at).toLocaleString('zh-CN')}
          </div>
          <div className="dashboard-card-actions">
            <button className="btn-primary btn-sm" onClick={() => onView(d)}>
              查看
            </button>
            <button className="btn-secondary btn-sm" onClick={() => onEdit(d)}>
              编辑
            </button>
            <button className="btn-danger" onClick={() => onDelete(d.id)}>
              删除
            </button>
          </div>
        </div>
      ))}
    </div>
  );
}

export default DashboardList;
