import React, { useState } from 'react';
import { useBoardStore } from '../../stores/useBoardStore';
import type { Version } from '../../types';

const containerStyle: React.CSSProperties = {
  position: 'absolute',
  top: 80,
  right: 16,
  width: 320,
  maxHeight: 480,
  backgroundColor: '#ffffff',
  borderRadius: 12,
  boxShadow: '0 8px 24px rgba(0, 0, 0, 0.12)',
  border: '1px solid #e5e7eb',
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  zIndex: 100,
};

const headerStyle: React.CSSProperties = {
  padding: '12px 16px',
  borderBottom: '1px solid #e5e7eb',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
};

const versionListStyle: React.CSSProperties = {
  flex: 1,
  overflowY: 'auto',
  padding: 16,
};

const buttonStyle = (active: boolean): React.CSSProperties => ({
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  padding: 12,
  borderRadius: 8,
  cursor: 'pointer',
  backgroundColor: active ? '#eff6ff' : 'transparent',
  border: active ? '1px solid #bfdbfe' : '1px solid transparent',
  transition: 'all 0.15s',
});

const demoVersions: Version[] = [
  {
    id: 'v1',
    boardId: 'default-board',
    name: '初始版本',
    description: '创建白板',
    snapshot: '',
    userId: 'user-1',
    createdAt: Date.now() - 86400000,
    childrenIds: ['v2'],
  },
  {
    id: 'v2',
    boardId: 'default-board',
    name: '添加草图',
    description: '添加了一些基础图形',
    snapshot: '',
    userId: 'user-1',
    createdAt: Date.now() - 43200000,
    parentId: 'v1',
    childrenIds: ['v3'],
  },
  {
    id: 'v3',
    boardId: 'default-board',
    name: '当前版本',
    description: '完善设计稿',
    snapshot: '',
    userId: 'user-2',
    createdAt: Date.now() - 3600000,
    parentId: 'v2',
    childrenIds: [],
  },
];

const VersionTree: React.FC = () => {
  const { toggleVersionTree, history } = useBoardStore();
  const [selectedVersion, setSelectedVersion] = useState<string | null>(null);
  const [versions] = useState<Version[]>(history.length > 0 ? history : demoVersions);
  const [showNewName, setShowNewName] = useState('');
  const [showNewDesc, setShowNewDesc] = useState('');

  const formatDate = (timestamp: number) => {
    return new Date(timestamp).toLocaleString('zh-CN', {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const handleSaveVersion = () => {
    if (!showNewName.trim()) return;
    console.log('保存新版本:', showNewName, showNewDesc);
    setShowNewName('');
    setShowNewDesc('');
  };

  return (
    <div style={containerStyle}>
      <div style={headerStyle}>
        <div style={{ fontWeight: 600, fontSize: 14, color: '#111827' }}>
        🕒 历史版本
        </div>
        <button
          onClick={toggleVersionTree}
          style={{
            border: 'none',
            background: 'transparent',
            cursor: 'pointer',
            fontSize: 16,
            color: '#6b7280',
            padding: 4,
          }}
          title="关闭"
        >
          ✕
        </button>
      </div>

      <div
        style={{
          padding: 12,
          borderBottom: '1px solid #e5e7eb',
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
        }}
      >
        <input
          type="text"
          placeholder="版本名称"
          value={showNewName}
          onChange={(e) => setShowNewName(e.target.value)}
          style={{
            padding: '8px 12px',
            border: '1px solid #d1d5db',
            borderRadius: 6,
            fontSize: 13,
            outline: 'none',
          }}
        />
        <input
          type="text"
          placeholder="描述（可选）"
          value={showNewDesc}
          onChange={(e) => setShowNewDesc(e.target.value)}
          style={{
            padding: '8px 12px',
            border: '1px solid #d1d5db',
            borderRadius: 6,
            fontSize: 13,
            outline: 'none',
          }}
        />
        <button
          onClick={handleSaveVersion}
          disabled={!showNewName.trim()}
          style={{
            padding: '8px 16px',
            border: 'none',
            borderRadius: 6,
            backgroundColor: '#3b82f6',
            color: '#ffffff',
            fontSize: 13,
            fontWeight: 500,
            cursor: showNewName.trim() ? 'pointer' : 'not-allowed',
            opacity: showNewName.trim() ? 1 : 0.5,
          }}
        >
          保存当前版本
        </button>
      </div>

      <div style={versionListStyle}>
        {versions.map((version, index) => (
          <div
            key={version.id}
            style={{ position: 'relative', marginLeft: index > 0 ? 20 : 0 }}
          >
            {index > 0 && (
              <div
                style={{
                  position: 'absolute',
                  left: -10,
                  top: -12,
                  width: 1,
                  height: 12,
                  backgroundColor: '#e5e7eb',
                }}
              />
            )}
            <div
              style={buttonStyle(selectedVersion === version.id)}
              onClick={() => setSelectedVersion(version.id)}
              onMouseOver={(e) => {
                if (selectedVersion !== version.id) {
                  e.currentTarget.style.backgroundColor = '#f9fafb';
                }
              }}
              onMouseOut={(e) => {
                if (selectedVersion !== version.id) {
                  e.currentTarget.style.backgroundColor = 'transparent';
                }
              }}
            >
              <div
                style={{
                  width: 10,
                  height: 10,
                  borderRadius: '50%',
                  backgroundColor: selectedVersion === version.id ? '#3b82f6' : '#d1d5db',
                  flexShrink: 0,
                }}
              />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, fontSize: 13, color: '#111827', marginBottom: 2 }}>
                  {version.name}
                </div>
                {version.description && (
                  <div style={{ fontSize: 11, color: '#6b7280', marginBottom: 4 }}>
                    {version.description}
                  </div>
                )}
                <div style={{ fontSize: 11, color: '#9ca3af' }}>
                  {formatDate(version.createdAt)}
                </div>
              </div>
              {selectedVersion === version.id && (
                <div style={{ display: 'flex', gap: 4 }}>
                  <button
                  style={{
                    padding: '4px 8px',
                    border: '1px solid #d1d5db',
                    borderRadius: 4,
                    backgroundColor: '#ffffff',
                    cursor: 'pointer',
                    fontSize: 11,
                    color: '#374151',
                  }}
                  onClick={(e) => {
                    e.stopPropagation();
                    console.log('恢复版本:', version.name);
                  }}
                >
                  恢复
                </button>
              </div>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default VersionTree;
