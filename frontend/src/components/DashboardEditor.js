import React, { useState } from 'react';

const CHART_TYPES = [
  { value: 'line', label: '折线图' },
  { value: 'bar', label: '柱状图' },
  { value: 'stat', label: '数值卡' },
  { value: 'table', label: '表格' },
];

const REFRESH_INTERVALS = [
  { value: '10s', label: '10秒' },
  { value: '30s', label: '30秒' },
  { value: '1m', label: '1分钟' },
  { value: '5m', label: '5分钟' },
];

function DashboardEditor({ dashboard, onSave, onCancel }) {
  const isEdit = !!dashboard;
  const [name, setName] = useState(dashboard?.name || '');
  const [description, setDescription] = useState(dashboard?.description || '');
  const [panels, setPanels] = useState(
    dashboard?.panels || [createDefaultPanel(0)]
  );

  function createDefaultPanel(index) {
    return {
      title: `面板 ${index + 1}`,
      query: '',
      chart_type: 'line',
      grid_x: (index % 2) * 6,
      grid_y: Math.floor(index / 2) * 4,
      grid_w: 6,
      grid_h: 4,
      refresh_interval: '1m',
      color: '',
      unit: '',
    };
  }

  const addPanel = () => {
    setPanels([...panels, createDefaultPanel(panels.length)]);
  };

  const removePanel = (index) => {
    setPanels(panels.filter((_, i) => i !== index));
  };

  const updatePanel = (index, field, value) => {
    const updated = [...panels];
    updated[index] = { ...updated[index], [field]: value };
    setPanels(updated);
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    onSave({
      name,
      description: description || undefined,
      panels: panels.map((p) => ({
        ...p,
        refresh_interval: p.refresh_interval || '1m',
      })),
    });
  };

  return (
    <form className="editor-form" onSubmit={handleSubmit}>
      <div className="form-group">
        <label>仪表盘名称</label>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="例如：API监控面板"
          required
        />
      </div>

      <div className="form-group">
        <label>描述</label>
        <textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="可选描述"
          rows={2}
        />
      </div>

      <div style={{ marginBottom: 16 }}>
        <h3 style={{ color: '#e2e8f0', marginBottom: 12 }}>面板配置</h3>
        {panels.map((panel, index) => (
          <div key={index} className="panel-editor">
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginBottom: 12,
              }}
            >
              <h4>面板 {index + 1}</h4>
              {panels.length > 1 && (
                <button
                  type="button"
                  className="btn-danger"
                  onClick={() => removePanel(index)}
                >
                  删除
                </button>
              )}
            </div>

            <div className="form-group">
              <label>标题</label>
              <input
                value={panel.title}
                onChange={(e) => updatePanel(index, 'title', e.target.value)}
                placeholder="面板标题"
              />
            </div>

            <div className="form-group">
              <label>查询语句 (PromQL)</label>
              <input
                value={panel.query}
                onChange={(e) => updatePanel(index, 'query', e.target.value)}
                placeholder='例如：rate(http_requests_total[5m])'
              />
            </div>

            <div className="panel-grid-config">
              <div className="form-group">
                <label>图表类型</label>
                <select
                  value={panel.chart_type}
                  onChange={(e) =>
                    updatePanel(index, 'chart_type', e.target.value)
                  }
                >
                  {CHART_TYPES.map((ct) => (
                    <option key={ct.value} value={ct.value}>
                      {ct.label}
                    </option>
                  ))}
                </select>
              </div>

              <div className="form-group">
                <label>刷新间隔</label>
                <select
                  value={panel.refresh_interval}
                  onChange={(e) =>
                    updatePanel(index, 'refresh_interval', e.target.value)
                  }
                >
                  {REFRESH_INTERVALS.map((ri) => (
                    <option key={ri.value} value={ri.value}>
                      {ri.label}
                    </option>
                  ))}
                </select>
              </div>

              <div className="form-group">
                <label>单位</label>
                <input
                  value={panel.unit || ''}
                  onChange={(e) => updatePanel(index, 'unit', e.target.value)}
                  placeholder="如: ms, %, req/s"
                />
              </div>

              <div className="form-group">
                <label>颜色</label>
                <input
                  value={panel.color || ''}
                  onChange={(e) => updatePanel(index, 'color', e.target.value)}
                  placeholder="#60a5fa"
                />
              </div>
            </div>

            <div className="panel-grid-config">
              <div className="form-group">
                <label>列 X</label>
                <input
                  type="number"
                  min="0"
                  max="11"
                  value={panel.grid_x}
                  onChange={(e) =>
                    updatePanel(index, 'grid_x', parseInt(e.target.value) || 0)
                  }
                />
              </div>
              <div className="form-group">
                <label>行 Y</label>
                <input
                  type="number"
                  min="0"
                  value={panel.grid_y}
                  onChange={(e) =>
                    updatePanel(index, 'grid_y', parseInt(e.target.value) || 0)
                  }
                />
              </div>
              <div className="form-group">
                <label>宽度</label>
                <input
                  type="number"
                  min="1"
                  max="12"
                  value={panel.grid_w}
                  onChange={(e) =>
                    updatePanel(index, 'grid_w', parseInt(e.target.value) || 6)
                  }
                />
              </div>
              <div className="form-group">
                <label>高度</label>
                <input
                  type="number"
                  min="1"
                  value={panel.grid_h}
                  onChange={(e) =>
                    updatePanel(index, 'grid_h', parseInt(e.target.value) || 4)
                  }
                />
              </div>
            </div>
          </div>
        ))}

        <button
          type="button"
          className="btn-secondary"
          onClick={addPanel}
          style={{ width: '100%', padding: 10 }}
        >
          + 添加面板
        </button>
      </div>

      <div style={{ display: 'flex', gap: 12, marginTop: 24 }}>
        <button type="submit" className="btn-primary" style={{ flex: 1, padding: 12 }}>
          {isEdit ? '保存修改' : '创建仪表盘'}
        </button>
        <button
          type="button"
          className="btn-secondary"
          onClick={onCancel}
          style={{ padding: 12 }}
        >
          取消
        </button>
      </div>
    </form>
  );
}

export default DashboardEditor;
