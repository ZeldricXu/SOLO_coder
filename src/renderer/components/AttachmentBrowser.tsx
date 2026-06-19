import React, { useState, useEffect, useCallback, useMemo } from 'react';
import type { AttachmentFile } from '@shared/types';
import './attachmentBrowser.css';

interface AttachmentBrowserProps {
  onInsertImage?: (relativePath: string) => void;
}

const FILE_TYPE_ICONS: Record<string, string> = {
  image: '🖼️',
  pdf: '📄',
  document: '📝',
  other: '📎',
};

const FILE_TYPE_LABELS: Record<string, string> = {
  image: '图片',
  pdf: 'PDF',
  document: '文档',
  other: '其他',
};

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatDate(timestamp: number): string {
  return new Date(timestamp).toLocaleDateString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export const AttachmentBrowser: React.FC<AttachmentBrowserProps> = ({ onInsertImage }) => {
  const [attachments, setAttachments] = useState<AttachmentFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedType, setSelectedType] = useState<string>('all');
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [previewId, setPreviewId] = useState<string | null>(null);
  const [previewData, setPreviewData] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);

  const fileInputRef = React.useRef<HTMLInputElement>(null);

  const loadAttachments = useCallback(async () => {
    setLoading(true);
    try {
      const files = await window.api.attachments.list();
      setAttachments(files);
    } catch (err) {
      console.error('Error loading attachments:', err);
      setAttachments([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadAttachments();
  }, [loadAttachments]);

  useEffect(() => {
    let mounted = true;
    
    if (previewId) {
      window.api.attachments.getThumbnail(previewId).then(data => {
        if (mounted) {
          setPreviewData(data);
        }
      });
    } else {
      setPreviewData(null);
    }
    
    return () => {
      mounted = false;
    };
  }, [previewId]);

  const handleFileUpload = async (files: FileList) => {
    if (!files || files.length === 0) return;
    
    setUploading(true);
    try {
      const assetsPath = await window.api.attachments.getAssetsPath();
      
      for (let i = 0; i < files.length; i++) {
        const file = files[i];
        const tempPath = await copyFileToTemp(file);
        await window.api.attachments.upload(tempPath);
      }
      
      await loadAttachments();
    } catch (err) {
      console.error('Error uploading files:', err);
    } finally {
      setUploading(false);
    }
  };

  const copyFileToTemp = async (file: File): Promise<string> => {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = async (e) => {
        try {
          const arrayBuffer = e.target?.result as ArrayBuffer;
          const buffer = Buffer.from(arrayBuffer);
          const tempDir = await window.api.dialog.openDirectory();
          const tempPath = `${tempDir}/${file.name}`;
          const fs = require('fs');
          fs.writeFileSync(tempPath, buffer);
          resolve(tempPath);
        } catch (err) {
          reject(err);
        }
      };
      reader.onerror = reject;
      reader.readAsArrayBuffer(file);
    });
  };

  const handleDelete = async (attachmentId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    
    const confirmed = confirm('确定要删除这个附件吗？');
    if (!confirmed) return;
    
    try {
      await window.api.attachments.delete(attachmentId);
      await loadAttachments();
      if (previewId === attachmentId) {
        setPreviewId(null);
      }
    } catch (err) {
      console.error('Error deleting attachment:', err);
    }
  };

  const handleStartRename = (attachment: AttachmentFile, e: React.MouseEvent) => {
    e.stopPropagation();
    setRenamingId(attachment.id);
    setRenameValue(attachment.name);
  };

  const handleConfirmRename = async (attachmentId: string) => {
    if (!renameValue.trim()) return;
    
    try {
      const result = await window.api.attachments.rename(attachmentId, renameValue.trim());
      if (result) {
        await loadAttachments();
      }
    } catch (err) {
      console.error('Error renaming attachment:', err);
    } finally {
      setRenamingId(null);
      setRenameValue('');
    }
  };

  const handleInsertImage = (attachment: AttachmentFile, e: React.MouseEvent) => {
    e.stopPropagation();
    if (attachment.type === 'image' && onInsertImage) {
      onInsertImage(`assets/${attachment.relativePath.split('assets/')[1] || attachment.relativePath}`);
    }
  };

  const handleDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    
    const files = e.dataTransfer.files;
    if (files.length > 0) {
      await handleFileUpload(files);
    }
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
  };

  const filteredAttachments = useMemo(() => {
    if (selectedType === 'all') return attachments;
    return attachments.filter(a => a.type === selectedType);
  }, [attachments, selectedType]);

  const groupedAttachments = useMemo(() => {
    const groups: Record<string, AttachmentFile[]> = {};
    for (const attachment of filteredAttachments) {
      const type = attachment.type;
      if (!groups[type]) groups[type] = [];
      groups[type].push(attachment);
    }
    return groups;
  }, [filteredAttachments]);

  const typeCounts = useMemo(() => {
    const counts: Record<string, number> = { all: attachments.length };
    for (const attachment of attachments) {
      counts[attachment.type] = (counts[attachment.type] || 0) + 1;
    }
    return counts;
  }, [attachments]);

  return (
    <div 
      className="attachment-browser"
      onDrop={handleDrop}
      onDragOver={handleDragOver}
    >
      <div className="attachment-browser-header">
        <h4>📎 附件管理</h4>
        <button
          className="upload-button"
          onClick={() => fileInputRef.current?.click()}
          disabled={uploading}
        >
          {uploading ? '上传中...' : '📁 上传'}
        </button>
        <input
          ref={fileInputRef}
          type="file"
          multiple
          hidden
          onChange={(e) => e.target.files && handleFileUpload(e.target.files)}
        />
      </div>

      <div className="attachment-filter-tabs">
        <button
          className={`filter-tab ${selectedType === 'all' ? 'active' : ''}`}
          onClick={() => setSelectedType('all')}
        >
          全部 <span className="count">({typeCounts.all})</span>
        </button>
        {Object.entries(FILE_TYPE_LABELS).map(([type, label]) => (
          typeCounts[type] > 0 && (
            <button
              key={type}
              className={`filter-tab ${selectedType === type ? 'active' : ''}`}
              onClick={() => setSelectedType(type)}
            >
              {FILE_TYPE_ICONS[type]} {label} <span className="count">({typeCounts[type]})</span>
            </button>
          )
        ))}
      </div>

      {loading ? (
        <div className="attachment-loading">
          <div className="spinner"></div>
          <span>加载中...</span>
        </div>
      ) : filteredAttachments.length === 0 ? (
        <div className="attachment-empty">
          <div className="empty-icon">📂</div>
          <p>暂无附件</p>
          <p className="hint">拖放文件到此处或点击上传按钮添加附件</p>
        </div>
      ) : (
        <div className="attachment-groups">
          {Object.entries(groupedAttachments).map(([type, items]) => (
            <div key={type} className="attachment-group">
              <div className="group-title">
                {FILE_TYPE_ICONS[type]} {FILE_TYPE_LABELS[type]} ({items.length})
              </div>
              <div className="attachment-grid">
                {items.map(attachment => (
                  <div
                    key={attachment.id}
                    className={`attachment-item ${previewId === attachment.id ? 'selected' : ''}`}
                    onClick={() => setPreviewId(previewId === attachment.id ? null : attachment.id)}
                  >
                    <div className="attachment-thumbnail">
                      {attachment.type === 'image' && previewId === attachment.id && previewData ? (
                        <img src={previewData} alt={attachment.name} />
                      ) : (
                        <span className="thumbnail-icon">{FILE_TYPE_ICONS[attachment.type]}</span>
                      )}
                    </div>
                    
                    {renamingId === attachment.id ? (
                      <div className="rename-input-container">
                        <input
                          type="text"
                          value={renameValue}
                          onChange={(e) => setRenameValue(e.target.value)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter') handleConfirmRename(attachment.id);
                            if (e.key === 'Escape') {
                              setRenamingId(null);
                              setRenameValue('');
                            }
                          }}
                          onClick={(e) => e.stopPropagation()}
                          autoFocus
                        />
                        <div className="rename-actions">
                          <button onClick={(e) => { e.stopPropagation(); handleConfirmRename(attachment.id); }}>✓</button>
                          <button onClick={(e) => { e.stopPropagation(); setRenamingId(null); setRenameValue(''); }}>✕</button>
                        </div>
                      </div>
                    ) : (
                      <>
                        <div className="attachment-name" title={attachment.name}>
                          {attachment.name}
                        </div>
                        <div className="attachment-meta">
                          <span>{formatFileSize(attachment.size)}</span>
                          <span>·</span>
                          <span>{formatDate(attachment.updatedAt)}</span>
                        </div>
                      </>
                    )}

                    <div className="attachment-actions">
                      {attachment.type === 'image' && onInsertImage && (
                        <button
                          className="action-btn"
                          title="插入到笔记"
                          onClick={(e) => handleInsertImage(attachment, e)}
                        >
                          📝
                        </button>
                      )}
                      <button
                        className="action-btn"
                        title="重命名"
                        onClick={(e) => handleStartRename(attachment, e)}
                      >
                        ✏️
                      </button>
                      <button
                        className="action-btn delete"
                        title="删除"
                        onClick={(e) => handleDelete(attachment.id, e)}
                      >
                        🗑️
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {previewId && (
        <div className="attachment-preview-panel">
          <div className="preview-header">
            <h5>预览</h5>
            <button onClick={() => setPreviewId(null)}>✕</button>
          </div>
          {(() => {
            const attachment = attachments.find(a => a.id === previewId);
            if (!attachment) return null;
            
            return (
              <div className="preview-content">
                {attachment.type === 'image' && previewData ? (
                  <img src={previewData} alt={attachment.name} className="preview-image" />
                ) : (
                  <div className="preview-placeholder">
                    <span className="preview-icon">{FILE_TYPE_ICONS[attachment.type]}</span>
                    <p>{attachment.name}</p>
                    <p className="preview-type">{FILE_TYPE_LABELS[attachment.type]}</p>
                  </div>
                )}
                <div className="preview-info">
                  <p><strong>大小:</strong> {formatFileSize(attachment.size)}</p>
                  <p><strong>路径:</strong> {attachment.relativePath}</p>
                  <p><strong>更新时间:</strong> {formatDate(attachment.updatedAt)}</p>
                </div>
              </div>
            );
          })()}
        </div>
      )}
    </div>
  );
};

export default AttachmentBrowser;
