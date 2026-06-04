import React, { useState, useEffect, useCallback, useRef } from 'react';
import axios from 'axios';
import UplotReact from 'uplot-react';
import 'uplot/dist/uPlot.min.css';

const CHART_COLORS = ['#3b82f6', '#22c55e', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4'];

function DashboardView({ dashboard, apiBase }) {
  const [panelData, setPanelData] = useState({});
  const [loading, setPanelLoading] = useState({});
  const intervalsRef = useRef({});

  const fetchPanelData = useCallback(async (panel) => {
    const key = panel.id;
    setPanelLoading((prev) => ({ ...prev, [key]: true }));
    try {
      const resp = await axios.get(`${apiBase}/api/v1/query`, {
        params: { query: panel.query },
      });
      setPanelData((prev) => ({
        ...prev,
        [key]: resp.data.data || resp.data,
      }));
    } catch (e) {
      console.error(`Failed to query panel ${panel.title}`, e);
    }
    setPanelLoading((prev) => ({ ...prev, [key]: false }));
  }, [apiBase]);

  useEffect(() => {
    dashboard.panels.forEach((panel) => {
      fetchPanelData(panel);

      const seconds = refreshToSeconds(panel.refresh_interval);
      if (seconds > 0) {
        intervalsRef.current[panel.id] = setInterval(() => {
          fetchPanelData(panel);
        }, seconds * 1000);
      }
    });

    return () => {
      Object.values(intervalsRef.current).forEach(clearInterval);
    };
  }, [dashboard, fetchPanelData]);

  const getGridStyle = (panel) => ({
    gridColumn: `${panel.grid_x + 1} / span ${panel.grid_w}`,
    gridRow: `${panel.grid_y + 1} / span ${panel.grid_h}`,
  });

  return (
    <div>
      <div style={{ marginBottom: 20 }}>
        <h2 style={{ color: '#f1f5f9', marginBottom: 4 }}>{dashboard.name}</h2>
        {dashboard.description && (
          <p style={{ color: '#94a3b8', fontSize: 14 }}>{dashboard.description}</p>
        )}
      </div>
      <div className="panel-grid">
        {dashboard.panels.map((panel) => (
          <div
            key={panel.id}
            className="panel"
            style={getGridStyle(panel)}
          >
            <div className="panel-header">
              <h3>{panel.title}</h3>
              <div className="panel-meta">
                <span
                  className={`refresh-indicator ${loading[panel.id] ? '' : 'active'}`}
                />
                {panel.refresh_interval}
              </div>
            </div>
            <div className="panel-body">
              {renderPanel(panel, panelData[panel.id])}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function renderPanel(panel, data) {
  switch (panel.chart_type) {
    case 'line':
    case 'bar':
      return <LineChartPanel panel={panel} data={data} />;
    case 'stat':
      return <StatPanel panel={panel} data={data} />;
    case 'table':
      return <TablePanel data={data} />;
    default:
      return <LineChartPanel panel={panel} data={data} />;
  }
}

function LineChartPanel({ panel, data }) {
  if (!data || !data.results || data.results.length === 0) {
    return <div style={{ color: '#64748b', textAlign: 'center', paddingTop: 60 }}>暂无数据</div>;
  }

  const allTimestamps = new Set();
  data.results.forEach((ts) => {
    ts.points.forEach((p) => allTimestamps.add(p.timestamp));
  });
  const timestamps = [...allTimestamps].sort().map((t) => new Date(t).getTime() / 1000);

  const series = [{}];
  const dataArr = [timestamps];

  data.results.forEach((ts, i) => {
    const values = timestamps.map((t) => {
      const point = ts.points.find(
        (p) => Math.abs(new Date(p.timestamp).getTime() / 1000 - t) < 1
      );
      return point ? point.value : null;
    });
    dataArr.push(values);
    series.push({
      stroke: CHART_COLORS[i % CHART_COLORS.length],
      label: ts.metric_name,
    });
  });

  const opts = {
    width: 800,
    height: 200,
    scales: { x: { time: true }, y: { auto: true } },
    series,
    axes: [{}, { size: 60 }],
    cursor: { drag: { x: true, y: true } },
  };

  return (
    <div className="uplot-container">
      <UplotReact options={opts} data={dataArr} />
    </div>
  );
}

function StatPanel({ panel, data }) {
  let value = '--';
  if (data && data.results && data.results.length > 0) {
    const ts = data.results[0];
    if (ts.points && ts.points.length > 0) {
      value = ts.points[ts.points.length - 1].value.toFixed(2);
    }
  }

  const color = panel.color || '#60a5fa';

  return (
    <div className="stat-card">
      <div className="stat-value" style={{ color }}>
        {value}
        {panel.unit && <span className="stat-unit">{panel.unit}</span>}
      </div>
      <div className="stat-label">{panel.query}</div>
    </div>
  );
}

function TablePanel({ data }) {
  if (!data || !data.results || data.results.length === 0) {
    return <div style={{ color: '#64748b', textAlign: 'center', paddingTop: 40 }}>暂无数据</div>;
  }

  const ts = data.results[0];
  const columns = ['timestamp', 'value', ...(ts.labels ? ts.labels.map((l) => l.name) : [])];

  return (
    <div className="table-container">
      <table>
        <thead>
          <tr>
            {columns.map((col) => (
              <th key={col}>{col}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {ts.points.slice(-20).map((point, i) => (
            <tr key={i}>
              <td>{new Date(point.timestamp).toLocaleString('zh-CN')}</td>
              <td>{point.value.toFixed(4)}</td>
              {ts.labels &&
                ts.labels.map((l) => <td key={l.name}>{l.value}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function refreshToSeconds(interval) {
  switch (interval) {
    case '10s': return 10;
    case '30s': return 30;
    case '1m': return 60;
    case '5m': return 300;
    default: return 60;
  }
}

export default DashboardView;
