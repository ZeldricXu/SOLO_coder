import React from 'react';
import { Folder } from '../../shared/types';

interface SidebarProps {
  folders: Folder[];
  currentFolderId: string | null;
  onSelectFolder: (folderId: string | null) => void;
  onCreateNote: () => void;
  onCreateFolder: () => void;
  onOpenSettings: () => void;
}

export const Sidebar: React.FC<SidebarProps> = ({
  folders,
  currentFolderId,
  onSelectFolder,
  onCreateNote,
  onCreateFolder,
  onOpenSettings,
}) => {
  return (
    <div className="sidebar">
      <div className="sidebar-section">
        <div className="sidebar-section-title">笔记</div>
        <div
          className={`sidebar-item ${currentFolderId === null ? 'active' : ''}`}
          onClick={() => onSelectFolder(null)}
        >
          <div className="sidebar-item-icon">📁</div>
          <div className="sidebar-item-text">全部笔记</div>
        </div>
      </div>

      <div className="sidebar-section">
        <div className="sidebar-section-title">文件夹</div>
        {folders.length === 0 ? (
          <div style={{ padding: '8px 16px', color: '#999', fontSize: 12 }}>
          暂无文件夹
        </div>
      ) : (
          folders.map(folder => (
            <div
              key={folder.folder_id}
              className={`sidebar-item ${currentFolderId === folder.folder_id ? 'active' : ''}`}
              onClick={() => onSelectFolder(folder.folder_id)}
            >
              <div className="sidebar-item-icon">📂</div>
              <div className="sidebar-item-text">{folder.name}</div>
            </div>
          ))
        )}
      </div>

      <div style={{ marginTop: 'auto' }} />

      <div className="sidebar-toolbar">
        <button className="icon-button" onClick={onCreateNote} title="新建笔记">
          ✏️
        </button>
        <button className="icon-button" onClick={onCreateFolder} title="新建文件夹">
          📁
        </button>
        <button className="icon-button" onClick={onOpenSettings} title="设置">
          ⚙️
        </button>
      </div>
    </div>
  );
};
