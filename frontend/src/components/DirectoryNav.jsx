import React, { useState, useEffect, useCallback } from 'react';
import { Folder, File, ChevronRight, ChevronDown, Plus, Trash2, Edit2, MoreVertical, FileText } from 'lucide-react';
import { folderApi, documentApi } from '../lib/api';

function DirectoryItem({ item, onSelect, isSelected, onToggleExpand, onAddFolder, onAddDocument, onRename, onDelete, level = 0 }) {
  const [isEditing, setIsEditing] = useState(false);
  const [editName, setEditName] = useState(item.name || '');
  const [showMenu, setShowMenu] = useState(false);
  const inputRef = React.useRef(null);

  useEffect(() => {
    if (isEditing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [isEditing]);

  const isFolder = item.type === 'folder';
  const isExpanded = item.is_expanded;

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') {
      handleSaveRename();
    } else if (e.key === 'Escape') {
      handleCancelRename();
    }
  };

  const handleSaveRename = () => {
    if (editName.trim() && editName !== item.name) {
      onRename(item, editName.trim());
    }
    setIsEditing(false);
  };

  const handleCancelRename = () => {
    setEditName(item.name || '');
    setIsEditing(false);
  };

  const handleToggleExpand = (e) => {
    e.stopPropagation();
    if (isFolder) {
      onToggleExpand(item);
    }
  };

  const handleClick = (e) => {
    if (!isEditing) {
      onSelect(item);
    }
  };

  return (
    <div className="select-none">
      <div
        className={`folder-tree-item flex items-center gap-1.5 px-2 py-1.5 cursor-pointer rounded group ${
          isSelected ? 'selected' : ''
        }`}
        style={{ paddingLeft: `${level * 16 + 8}px` }}
        onClick={handleClick}
      >
        {isFolder && (
          <button
            onClick={handleToggleExpand}
            className="p-0.5 rounded hover:bg-slate-200 transition-colors"
          >
            {isExpanded ? (
              <ChevronDown size={14} className="text-slate-500" />
            ) : (
              <ChevronRight size={14} className="text-slate-500" />
            )}
          </button>
        )}
        
        {!isFolder && <div className="w-6" />}

        {isFolder ? (
          <Folder
            size={16}
            className={isExpanded ? 'text-primary-500' : 'text-slate-400'}
          />
        ) : (
          <FileText size={16} className="text-slate-400" />
        )}

        {isEditing ? (
          <input
            ref={inputRef}
            type="text"
            value={editName}
            onChange={(e) => setEditName(e.target.value)}
            onKeyDown={handleKeyDown}
            onBlur={handleSaveRename}
            className="flex-1 px-1 py-0.5 text-sm border border-primary-500 rounded focus:outline-none"
            onClick={(e) => e.stopPropagation()}
          />
        ) : (
          <span className="flex-1 text-sm truncate">
            {item.name || 'Untitled'}
          </span>
        )}

        <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
          {isFolder && (
            <>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onAddDocument(item.folder_id);
                }}
                className="p-1 rounded hover:bg-slate-200 text-slate-500 hover:text-slate-700"
                title="新建文档"
              >
                <File size={14} />
              </button>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onAddFolder(item.folder_id);
                }}
                className="p-1 rounded hover:bg-slate-200 text-slate-500 hover:text-slate-700"
                title="新建文件夹"
              >
                <Plus size={14} />
              </button>
            </>
          )}
          
          <div className="relative">
            <button
              onClick={(e) => {
                e.stopPropagation();
                setShowMenu(!showMenu);
              }}
              className="p-1 rounded hover:bg-slate-200 text-slate-500 hover:text-slate-700"
              title="更多"
            >
              <MoreVertical size={14} />
            </button>

            {showMenu && (
              <div className="absolute right-0 top-full mt-1 bg-white border border-slate-200 rounded-lg shadow-lg z-50 py-1 min-w-[120px]">
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setIsEditing(true);
                    setShowMenu(false);
                  }}
                  className="w-full px-3 py-1.5 text-left text-sm text-slate-700 hover:bg-slate-50 flex items-center gap-2"
                >
                  <Edit2 size={14} />
                  重命名
                </button>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onDelete(item);
                    setShowMenu(false);
                  }}
                  className="w-full px-3 py-1.5 text-left text-sm text-red-600 hover:bg-red-50 flex items-center gap-2"
                >
                  <Trash2 size={14} />
                  删除
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      {isFolder && isExpanded && item.children && item.children.length > 0 && (
        <div>
          {item.children.map((child) => (
            <DirectoryItem
              key={child.folder_id || child.doc_id}
              item={child}
              onSelect={onSelect}
              isSelected={isSelected}
              onToggleExpand={onToggleExpand}
              onAddFolder={onAddFolder}
              onAddDocument={onAddDocument}
              onRename={onRename}
              onDelete={onDelete}
              level={level + 1}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function DirectoryNav({ onSelectDocument, onSelectFolder, selectedItem }) {
  const [tree, setTree] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const loadTree = useCallback(async () => {
    try {
      setLoading(true);
      const response = await folderApi.getTree();
      if (response.code === 200) {
        setTree(response.data || []);
      }
      setError(null);
    } catch (err) {
      console.error('Failed to load folder tree:', err);
      setError('加载目录失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadTree();
  }, [loadTree]);

  const handleToggleExpand = async (folder) => {
    try {
      await folderApi.update(folder.folder_id, {
        is_expanded: !folder.is_expanded
      });
      
      setTree(prev => {
        const updateTree = (items) => {
          return items.map(item => {
            if (item.folder_id === folder.folder_id) {
              return { ...item, is_expanded: !item.is_expanded };
            }
            if (item.children) {
              return { ...item, children: updateTree(item.children) };
            }
            return item;
          });
        };
        return updateTree(prev);
      });
    } catch (err) {
      console.error('Failed to toggle expand:', err);
    }
  };

  const handleSelect = (item) => {
    if (item.type === 'folder') {
      if (onSelectFolder) {
        onSelectFolder(item);
      }
    } else {
      if (onSelectDocument) {
        onSelectDocument(item);
      }
    }
  };

  const handleAddFolder = async (parentId = null) => {
    try {
      const response = await folderApi.create({
        name: '新建文件夹',
        parent_id: parentId,
        created_by: 'user_default'
      });
      
      if (response.code === 201) {
        loadTree();
      }
    } catch (err) {
      console.error('Failed to create folder:', err);
    }
  };

  const handleAddDocument = async (folderId = null) => {
    try {
      const response = await documentApi.create({
        title: '新建文档',
        content: '',
        folder_id: folderId,
        created_by: 'user_default'
      });
      
      if (response.code === 201) {
        loadTree();
        if (onSelectDocument) {
          onSelectDocument(response.data);
        }
      }
    } catch (err) {
      console.error('Failed to create document:', err);
    }
  };

  const handleRename = async (item, newName) => {
    try {
      if (item.type === 'folder') {
        await folderApi.update(item.folder_id, { name: newName });
      } else {
        await documentApi.update(item.doc_id, { title: newName });
      }
      loadTree();
    } catch (err) {
      console.error('Failed to rename:', err);
    }
  };

  const handleDelete = async (item) => {
    if (!window.confirm(`确定要删除 "${item.name}" 吗？`)) {
      return;
    }

    try {
      if (item.type === 'folder') {
        await folderApi.delete(item.folder_id, true);
      } else {
        await documentApi.delete(item.doc_id);
      }
      loadTree();
    } catch (err) {
      console.error('Failed to delete:', err);
    }
  };

  const isItemSelected = (item) => {
    if (!selectedItem) return false;
    if (item.type === 'folder') {
      return selectedItem.folder_id === item.folder_id;
    }
    return selectedItem.doc_id === item.doc_id;
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full text-slate-500 text-sm">
        加载中...
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center h-full gap-2">
        <p className="text-sm text-red-500">{error}</p>
        <button
          onClick={loadTree}
          className="text-sm text-primary-600 hover:text-primary-700"
        >
          重试
        </button>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-white">
      <div className="flex items-center justify-between px-3 py-2 border-b border-slate-200">
        <h3 className="text-sm font-semibold text-slate-700">目录导航</h3>
        
        <div className="flex items-center gap-1">
          <button
            onClick={() => handleAddDocument(null)}
            className="p-1.5 rounded hover:bg-slate-100 text-slate-500 hover:text-slate-700"
            title="新建文档"
          >
            <File size={16} />
          </button>
          <button
            onClick={() => handleAddFolder(null)}
            className="p-1.5 rounded hover:bg-slate-100 text-slate-500 hover:text-slate-700"
            title="新建文件夹"
          >
            <Plus size={16} />
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-auto py-1">
        {tree.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full gap-3 px-4 text-center">
            <Folder size={48} className="text-slate-300" />
            <div>
              <p className="text-sm text-slate-600">暂无文档</p>
              <p className="text-xs text-slate-400 mt-1">点击上方按钮创建第一个文档</p>
            </div>
          </div>
        ) : (
          tree.map((item) => (
            <DirectoryItem
              key={item.folder_id || item.doc_id}
              item={item}
              onSelect={handleSelect}
              isSelected={isItemSelected(item)}
              onToggleExpand={handleToggleExpand}
              onAddFolder={handleAddFolder}
              onAddDocument={handleAddDocument}
              onRename={handleRename}
              onDelete={handleDelete}
              level={0}
            />
          ))
        )}
      </div>
    </div>
  );
}

export default DirectoryNav;
