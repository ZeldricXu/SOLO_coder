import React, { useState } from 'react';
import { IPCSyncConflict, IPCConflictResolution } from '../../shared/ipc-channels';

interface ConflictModalProps {
  conflicts: IPCSyncConflict[];
  onResolve: (noteId: string, resolution: IPCConflictResolution) => Promise<void>;
  onClose: () => void;
}

export const ConflictModal: React.FC<ConflictModalProps> = ({
  conflicts,
  onResolve,
  onClose,
}) => {
  const [currentIndex, setCurrentIndex] = useState(0);
  const [resolving, setResolving] = useState(false);
  const [showDiff, setShowDiff] = useState(false);

  if (conflicts.length === 0) {
    return null;
  }

  const conflict = conflicts[currentIndex];

  const handleResolve = async (resolution: IPCConflictResolution) => {
    setResolving(true);
    try {
      await onResolve(conflict.note_id, resolution);
      
      if (currentIndex < conflicts.length - 1) {
        setCurrentIndex(currentIndex + 1);
      } else {
        onClose();
      }
    } finally {
      setResolving(false);
    }
  };

  const formatDate = (dateStr: string) => {
    try {
      const date = new Date(dateStr);
      return date.toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return dateStr;
    }
  };

  return (
    <div className="modal-overlay" onClick={(e) => {
      if (e.target === e.currentTarget && !resolving) onClose();
    }}>
      <div className="modal" style={{ minWidth: '600px', maxWidth: '900px' }}>
        <div className="modal-header">
          <span className="modal-title">
            ⚠️ 同步冲突 ({currentIndex + 1}/{conflicts.length})
          </span>
          <button className="modal-close" onClick={onClose} disabled={resolving}>
            ✕
          </button>
        </div>
        
        <div className="modal-body" style={{ padding: '16px 24px' }}>
          <div style={{ marginBottom: '16px' }}>
            <h3 style={{ fontSize: '16px', marginBottom: '12px', color: '#ff9500' }}>
              "{conflict.title}"
            </h3>
            
            <div style={{ 
              display: 'grid', 
              gridTemplateColumns: '1fr 1fr', 
              gap: '16px',
              marginBottom: '16px'
            }}>
              <div style={{ 
                padding: '12px', 
                backgroundColor: '#fff3cd', 
                borderRadius: '8px',
                border: '1px solid #ffeaa7'
              }}>
                <div style={{ fontSize: '12px', fontWeight: '600', color: '#856404', marginBottom: '4px' }}>
                  📝 本地版本 (v{conflict.local_version})
                </div>
                <div style={{ fontSize: '11px', color: '#666' }}>
                  更新于: {formatDate(conflict.local_updated_at)}
                </div>
              </div>
              
              <div style={{ 
                padding: '12px', 
                backgroundColor: '#cce5ff', 
                borderRadius: '8px',
                border: '1px solid #b8daff'
              }}>
                <div style={{ fontSize: '12px', fontWeight: '600', color: '#004085', marginBottom: '4px' }}>
                  ☁️ 云端版本 (v{conflict.remote_version})
                </div>
                <div style={{ fontSize: '11px', color: '#666' }}>
                  更新于: {formatDate(conflict.remote_updated_at)}
                </div>
              </div>
            </div>

            <div style={{ marginBottom: '12px' }}>
              <button
                style={{
                  padding: '6px 12px',
                  fontSize: '12px',
                  border: '1px solid #ddd',
                  borderRadius: '4px',
                  background: '#f5f5f5',
                  cursor: 'pointer',
                  color: '#666',
                }}
                onClick={() => setShowDiff(!showDiff)}
              >
                {showDiff ? '隐藏内容预览' : '查看内容预览'}
              </button>
            </div>

            {showDiff && (
              <div style={{ 
                display: 'grid', 
                gridTemplateColumns: '1fr 1fr', 
                gap: '16px',
                marginTop: '8px'
              }}>
                <div>
                  <div style={{ fontSize: '11px', fontWeight: '600', color: '#856404', marginBottom: '4px' }}>
                    本地内容:
                  </div>
                  <div style={{ 
                    padding: '8px', 
                    backgroundColor: '#fff3cd', 
                    borderRadius: '4px',
                    fontSize: '12px',
                    color: '#333',
                    maxHeight: '150px',
                    overflow: 'auto',
                    fontFamily: 'monospace',
                    whiteSpace: 'pre-wrap',
                  }}>
                    {conflict.remote_note.content.substring(0, 500)}...
                  </div>
                </div>
                <div>
                  <div style={{ fontSize: '11px', fontWeight: '600', color: '#004085', marginBottom: '4px' }}>
                    云端内容:
                  </div>
                  <div style={{ 
                    padding: '8px', 
                    backgroundColor: '#cce5ff', 
                    borderRadius: '4px',
                    fontSize: '12px',
                    color: '#333',
                    maxHeight: '150px',
                    overflow: 'auto',
                    fontFamily: 'monospace',
                    whiteSpace: 'pre-wrap',
                  }}>
                    {conflict.remote_note.content.substring(0, 500)}...
                  </div>
                </div>
              </div>
            )}
          </div>

          <div style={{ 
            padding: '16px', 
            backgroundColor: '#f8f9fa', 
            borderRadius: '8px',
            marginBottom: '8px'
          }}>
            <div style={{ fontSize: '13px', fontWeight: '600', color: '#333', marginBottom: '12px' }}>
              请选择解决方案:
            </div>
            
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              <button
                className="button button-secondary"
                style={{ 
                  justifyContent: 'flex-start', 
                  textAlign: 'left',
                  borderColor: '#ffeaa7',
                  backgroundColor: '#fff3cd',
                }}
                onClick={() => handleResolve('keep_local')}
                disabled={resolving}
              >
                <span style={{ fontWeight: '600' }}>📝 保留本地版本</span>
                <span style={{ fontSize: '11px', color: '#666', marginLeft: '8px' }}>
                  用本地笔记覆盖云端版本
                </span>
              </button>
              
              <button
                className="button button-secondary"
                style={{ 
                  justifyContent: 'flex-start', 
                  textAlign: 'left',
                  borderColor: '#b8daff',
                  backgroundColor: '#cce5ff',
                }}
                onClick={() => handleResolve('use_remote')}
                disabled={resolving}
              >
                <span style={{ fontWeight: '600' }}>☁️ 使用云端版本</span>
                <span style={{ fontSize: '11px', color: '#666', marginLeft: '8px' }}>
                  用云端笔记替换本地版本
                </span>
              </button>
              
              <button
                className="button button-primary"
                style={{ justifyContent: 'flex-start', textAlign: 'left' }}
                onClick={() => handleResolve('merge')}
                disabled={resolving}
              >
                <span style={{ fontWeight: '600' }}>🔀 合并内容</span>
                <span style={{ fontSize: '11px', color: 'rgba(255,255,255,0.8)', marginLeft: '8px' }}>
                  合并两个版本的内容（保留所有非重复行）
                </span>
              </button>
            </div>
          </div>
        </div>

        <div className="modal-footer" style={{ justifyContent: 'space-between' }}>
          <div style={{ fontSize: '12px', color: '#999' }}>
            提示: 合并后请手动检查内容是否正确
          </div>
          <button 
            className="button button-secondary" 
            onClick={onClose}
            disabled={resolving}
          >
            稍后处理
          </button>
        </div>
      </div>
    </div>
  );
};
