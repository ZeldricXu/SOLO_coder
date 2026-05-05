import React, { useState, useEffect } from 'react';
import { Clock, RotateCcw, User, FileText, ChevronRight, Eye, Compare } from 'lucide-react';
import { versionApi, documentApi } from '../lib/api';

function VersionItem({ version, isCurrent, onRestore, onView, onCompare }) {
  const [showDiff, setShowDiff] = useState(false);

  return (
    <div className={`p-3 border-b border-slate-100 hover:bg-slate-50 transition-colors ${
      isCurrent ? 'bg-primary-50' : ''
    }`}>
      <div className="flex items-start justify-between gap-2">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium text-slate-900">
              版本 {version.version_number}
            </span>
            {isCurrent && (
              <span className="px-2 py-0.5 text-xs font-medium bg-primary-100 text-primary-700 rounded-full">
                当前
              </span>
            )}
          </div>
          
          {version.edit_summary && (
            <p className="text-sm text-slate-600 mt-1">
              {version.edit_summary}
            </p>
          )}
          
          <div className="flex items-center gap-3 mt-2 text-xs text-slate-400">
            <span className="flex items-center gap-1">
              <User size={12} />
              {version.edited_by || 'Unknown'}
            </span>
            <span className="flex items-center gap-1">
              <Clock size={12} />
              {new Date(version.created_at).toLocaleString('zh-CN')}
            </span>
          </div>
        </div>

        <div className="flex items-center gap-1">
          <button
            onClick={() => onView(version)}
            className="p-1.5 rounded hover:bg-slate-200 text-slate-500 hover:text-slate-700"
            title="查看此版本"
          >
            <Eye size={14} />
          </button>
          
          {!isCurrent && (
            <button
              onClick={() => onRestore(version)}
              className="p-1.5 rounded hover:bg-slate-200 text-slate-500 hover:text-slate-700"
              title="恢复到此版本"
            >
              <RotateCcw size={14} />
            </button>
          )}
        </div>
      </div>

      {showDiff && (
        <div className="mt-3 p-2 bg-slate-100 rounded text-xs font-mono overflow-x-auto">
          {/* 差异显示占位 - 实际项目中可以集成 diff 库 */}
          <p className="text-slate-500">版本差异对比...</p>
        </div>
      )}
    </div>
  );
}

function VersionPanel({ documentId, onClose }) {
  const [versions, setVersions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [selectedVersion, setSelectedVersion] = useState(null);
  const [viewingVersion, setViewingVersion] = useState(null);

  useEffect(() => {
    if (!documentId) return;

    const loadVersions = async () => {
      try {
        setLoading(true);
        const response = await versionApi.getByDocument(documentId);
        
        if (response.code === 200) {
          setVersions(response.data || []);
        }
      } catch (error) {
        console.error('Failed to load versions:', error);
      } finally {
        setLoading(false);
      }
    };

    loadVersions();
  }, [documentId]);

  const handleRestore = async (version) => {
    if (!window.confirm(`确定要恢复到版本 ${version.version_number} 吗？当前内容将被覆盖。`)) {
      return;
    }

    try {
      const response = await versionApi.restore(version.version_id, {
        restored_by: 'user_default',
        restore_summary: `恢复到版本 ${version.version_number}`
      });

      if (response.code === 200) {
        setViewingVersion(null);
        onClose?.();
      }
    } catch (error) {
      console.error('Failed to restore version:', error);
    }
  };

  const handleView = async (version) => {
    try {
      const response = await versionApi.getById(version.version_id);
      
      if (response.code === 200) {
        setViewingVersion(response.data);
      }
    } catch (error) {
      console.error('Failed to load version content:', error);
    }
  };

  if (!documentId) {
    return (
      <div className="flex items-center justify-center h-full text-slate-500 text-sm">
        请先选择一个文档
      </div>
    );
  }

  if (viewingVersion) {
    return (
      <div className="flex flex-col h-full">
        <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200 bg-slate-50">
          <div className="flex items-center gap-2">
            <button
              onClick={() => setViewingVersion(null)}
              className="p-1 rounded hover:bg-slate-200"
            >
              <ChevronRight size={16} className="rotate-180" />
            </button>
            <span className="text-sm font-medium text-slate-700">
              版本 {viewingVersion.version_number}
            </span>
          </div>
          
          <button
            onClick={() => handleRestore(viewingVersion)}
            className="flex items-center gap-1 px-3 py-1.5 text-sm text-primary-600 hover:bg-primary-50 rounded"
          >
            <RotateCcw size={14} />
            恢复
          </button>
        </div>

        <div className="flex-1 overflow-auto p-4">
          {viewingVersion.edit_summary && (
            <div className="mb-4 p-3 bg-yellow-50 border border-yellow-200 rounded-lg">
              <p className="text-sm text-yellow-800">
                <strong>编辑摘要:</strong> {viewingVersion.edit_summary}
              </p>
            </div>
          )}

          <div className="prose prose-sm max-w-none">
            <pre className="bg-slate-50 p-4 rounded-lg overflow-x-auto text-sm text-slate-700 whitespace-pre-wrap">
              {viewingVersion.content_snapshot || '（空内容）'}
            </pre>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200">
        <h3 className="text-sm font-semibold text-slate-700 flex items-center gap-2">
          <Clock size={16} />
          版本历史
        </h3>
        <span className="text-xs text-slate-400">
          {versions.length} 个版本
        </span>
      </div>

      <div className="flex-1 overflow-auto">
        {loading ? (
          <div className="flex items-center justify-center h-full text-slate-500 text-sm">
            加载中...
          </div>
        ) : versions.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full gap-3 px-4 text-center">
            <Clock size={48} className="text-slate-200" />
            <div>
              <p className="text-sm text-slate-600">暂无版本记录</p>
              <p className="text-xs text-slate-400 mt-1">保存文档后会自动创建版本</p>
            </div>
          </div>
        ) : (
          versions.map((version, index) => (
            <VersionItem
              key={version.version_id}
              version={version}
              isCurrent={index === 0}
              onRestore={handleRestore}
              onView={handleView}
              onCompare={() => {}}
            />
          ))
        )}
      </div>
    </div>
  );
}

export default VersionPanel;
