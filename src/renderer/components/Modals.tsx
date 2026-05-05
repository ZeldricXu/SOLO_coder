import React, { useState, useEffect } from 'react';
import { Folder, SyncConfig, AIConfig } from '../../shared/types';

interface ModalProps {
  onClose: () => void;
}

const Modal: React.FC<{
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  footer?: React.ReactNode;
}> = ({ title, onClose, children, footer }) => {
  return (
    <div className="modal-overlay" onClick={(e) => {
      if (e.target === e.currentTarget) onClose();
    }}>
      <div className="modal">
        <div className="modal-header">
          <span className="modal-title">{title}</span>
          <button className="modal-close" onClick={onClose}>
            ✕
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-footer">{footer}</div>}
      </div>
    </div>
  );
};

interface CreateNoteModalProps extends ModalProps {
  folders: Folder[];
  currentFolderId: string | null;
  onCreate: (title: string, folderId?: string) => void;
}

export const CreateNoteModal: React.FC<CreateNoteModalProps> = ({
  folders,
  currentFolderId,
  onClose,
  onCreate,
}) => {
  const [title, setTitle] = useState('');
  const [selectedFolderId, setSelectedFolderId] = useState<string | null>(currentFolderId);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onCreate(title, selectedFolderId || undefined);
    onClose();
  };

  return (
    <Modal title="新建笔记" onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label className="form-label">标题</label>
          <input
            type="text"
            className="form-input"
            placeholder="输入笔记标题..."
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            autoFocus
          />
        </div>
        <div className="form-group">
          <label className="form-label">文件夹</label>
          <select
            className="form-input"
            value={selectedFolderId || ''}
            onChange={(e) => setSelectedFolderId(e.target.value || null)}
          >
            <option value="">全部笔记</option>
            {folders.map(folder => (
              <option key={folder.folder_id} value={folder.folder_id}>
                {folder.name}
              </option>
            ))}
          </select>
        </div>
        <div className="modal-footer" style={{ justifyContent: 'flex-end', padding: '16px 0 0 0', borderTop: 'none' }}>
          <button type="button" className="button button-secondary" onClick={onClose}>
            取消
          </button>
          <button type="submit" className="button button-primary">
            创建
          </button>
        </div>
      </form>
    </Modal>
  );
};

interface CreateFolderModalProps extends ModalProps {
  folders: Folder[];
  onCreate: (name: string, parentId?: string) => void;
}

export const CreateFolderModal: React.FC<CreateFolderModalProps> = ({
  folders,
  onClose,
  onCreate,
}) => {
  const [name, setName] = useState('');
  const [parentId, setParentId] = useState<string | null>(null);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (name.trim()) {
      onCreate(name.trim(), parentId || undefined);
      onClose();
    }
  };

  return (
    <Modal title="新建文件夹" onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label className="form-label">文件夹名称</label>
          <input
            type="text"
            className="form-input"
            placeholder="输入文件夹名称..."
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoFocus
          />
        </div>
        {folders.length > 0 && (
          <div className="form-group">
            <label className="form-label">父文件夹 (可选)</label>
            <select
              className="form-input"
              value={parentId || ''}
              onChange={(e) => setParentId(e.target.value || null)}
            >
              <option value="">根目录</option>
              {folders.map(folder => (
                <option key={folder.folder_id} value={folder.folder_id}>
                  {folder.name}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="modal-footer" style={{ justifyContent: 'flex-end', padding: '16px 0 0 0', borderTop: 'none' }}>
          <button type="button" className="button button-secondary" onClick={onClose}>
            取消
          </button>
          <button type="submit" className="button button-primary" disabled={!name.trim()}>
            创建
          </button>
        </div>
      </form>
    </Modal>
  );
};

interface SettingsModalProps extends ModalProps {}

export const SettingsModal: React.FC<SettingsModalProps> = ({ onClose }) => {
  const [syncConfig, setSyncConfig] = useState<{
    api_url: string;
    api_key: string;
    auto_sync: boolean;
    sync_interval: number;
  }>({
    api_url: '',
    api_key: '',
    auto_sync: false,
    sync_interval: 30,
  });
  const [aiConfig, setAiConfig] = useState<{
    api_url: string;
    api_key: string;
    model: string;
    max_tokens: number;
  }>({
    api_url: '',
    api_key: '',
    model: 'gpt-3.5-turbo',
    max_tokens: 500,
  });
  const [activeTab, setActiveTab] = useState<'sync' | 'ai' | 'general'>('general');
  const [saving, setSaving] = useState(false);
  const [encryptionAvailable, setEncryptionAvailable] = useState<boolean>(false);
  const [syncKeySaved, setSyncKeySaved] = useState(false);
  const [aiKeySaved, setAiKeySaved] = useState(false);
  const [showSyncKey, setShowSyncKey] = useState(false);
  const [showAiKey, setShowAiKey] = useState(false);

  useEffect(() => {
    loadSettings();
  }, []);

  const loadSettings = async () => {
    if (!window.electronAPI) return;

    try {
      const [syncRes, aiRes, secureRes] = await Promise.all([
        window.electronAPI.sync.getConfig(),
        window.electronAPI.ai.getConfig(),
        window.electronAPI.secureStorage.getStatus(),
      ]);

      if (syncRes.success && syncRes.data) {
        setSyncConfig({
          api_url: syncRes.data.api_url,
          api_key: syncRes.data.api_key,
          auto_sync: syncRes.data.auto_sync || false,
          sync_interval: syncRes.data.sync_interval || 30,
        });
        if (syncRes.data.api_key === '********') {
          setSyncKeySaved(true);
        }
      }

      if (aiRes.success && aiRes.data) {
        setAiConfig({
          api_url: aiRes.data.api_url,
          api_key: aiRes.data.api_key,
          model: aiRes.data.model || 'gpt-3.5-turbo',
          max_tokens: aiRes.data.max_tokens || 500,
        });
        if (aiRes.data.api_key === '********') {
          setAiKeySaved(true);
        }
      }

      if (secureRes.success && secureRes.data) {
        setEncryptionAvailable(secureRes.data.encryptionAvailable);
      }
    } catch (error) {
      console.error('Failed to load settings:', error);
    }
  };

  const saveSyncConfig = async () => {
    if (!window.electronAPI) return;

    setSaving(true);
    try {
      const configToSave = {
        api_url: syncConfig.api_url,
        api_key: syncKeySaved && syncConfig.api_key === '********' 
          ? ''  
          : syncConfig.api_key,
        auto_sync: syncConfig.auto_sync,
        sync_interval: syncConfig.sync_interval,
      };

      const result = await window.electronAPI.sync.setConfig(configToSave);
      if (result.success) {
        setSyncKeySaved(true);
        setShowSyncKey(false);
        alert('同步设置已安全保存');
      } else {
        alert('保存失败: ' + (result.error || '未知错误'));
      }
    } catch (error) {
      console.error('Failed to save sync config:', error);
      alert('保存失败');
    } finally {
      setSaving(false);
    }
  };

  const saveAiConfig = async () => {
    if (!window.electronAPI) return;

    setSaving(true);
    try {
      const configToSave = {
        api_url: aiConfig.api_url,
        api_key: aiKeySaved && aiConfig.api_key === '********'
          ? ''
          : aiConfig.api_key,
        model: aiConfig.model,
        max_tokens: aiConfig.max_tokens,
      };

      const result = await window.electronAPI.ai.setConfig(configToSave);
      if (result.success) {
        setAiKeySaved(true);
        setShowAiKey(false);
        alert('AI 设置已安全保存');
      } else {
        alert('保存失败: ' + (result.error || '未知错误'));
      }
    } catch (error) {
      console.error('Failed to save AI config:', error);
      alert('保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleSyncApiKeyChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSyncConfig(prev => ({
      ...prev,
      api_key: e.target.value
    }));
    setSyncKeySaved(false);
  };

  const handleAiApiKeyChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setAiConfig(prev => ({
      ...prev,
      api_key: e.target.value
    }));
    setAiKeySaved(false);
  };

  const tabs = [
    { id: 'general' as const, label: '常规' },
    { id: 'sync' as const, label: '同步' },
    { id: 'ai' as const, label: 'AI服务' },
  ];

  const SecurityIndicator = () => (
    <div style={{ 
      display: 'flex', 
      alignItems: 'center', 
      gap: '6px',
      padding: '8px 12px',
      backgroundColor: encryptionAvailable ? '#e8f5e9' : '#fff3e0',
      borderRadius: '6px',
      marginBottom: '16px',
    }}>
      <span style={{ fontSize: '16px' }}>
        {encryptionAvailable ? '🔐' : '⚠️'}
      </span>
      <div style={{ fontSize: '12px', color: encryptionAvailable ? '#2e7d32' : '#f57c00' }}>
        {encryptionAvailable 
          ? '密钥安全加密存储（系统密钥环）' 
          : '加密不可用，密钥将明文存储'}
      </div>
    </div>
  );

  return (
    <Modal title="设置" onClose={onClose}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '24px' }}>
        <div style={{ display: 'flex', gap: '8px', borderBottom: '1px solid #e0e0e0', paddingBottom: '12px' }}>
          {tabs.map(tab => (
            <button
              key={tab.id}
              style={{
                padding: '8px 16px',
                border: 'none',
                background: activeTab === tab.id ? '#007aff' : 'transparent',
                color: activeTab === tab.id ? '#fff' : '#333',
                borderRadius: '6px',
                cursor: 'pointer',
                fontSize: '13px',
                transition: 'all 0.15s ease',
              }}
              onClick={() => setActiveTab(tab.id)}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {activeTab === 'general' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <div className="form-group">
              <label className="form-label">主题</label>
              <select className="form-input" defaultValue="system">
                <option value="light">浅色</option>
                <option value="dark">深色</option>
                <option value="system">跟随系统</option>
              </select>
            </div>
            <div className="form-group">
              <label className="form-label">语言</label>
              <select className="form-input" defaultValue="zh-CN">
                <option value="zh-CN">简体中文</option>
                <option value="en-US">English</option>
              </select>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <input type="checkbox" id="auto-save" defaultChecked />
              <label htmlFor="auto-save" style={{ fontSize: '13px', color: '#333' }}>
                自动保存笔记
              </label>
            </div>
          </div>
        )}

        {activeTab === 'sync' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <SecurityIndicator />
            
            <div className="form-group">
              <label className="form-label">API 地址</label>
              <input
                type="text"
                className="form-input"
                placeholder="https://api.example.com"
                value={syncConfig.api_url}
                onChange={(e) => setSyncConfig(prev => ({ ...prev, api_url: e.target.value }))}
              />
            </div>
            <div className="form-group">
              <label className="form-label">
                API 密钥 
                {syncKeySaved && (
                  <span style={{ 
                    marginLeft: '8px', 
                    fontSize: '11px', 
                    color: '#34c759',
                    fontWeight: '500'
                  }}>
                    ✓ 已安全存储
                  </span>
                )}
              </label>
              <div style={{ position: 'relative' }}>
                <input
                  type={showSyncKey ? 'text' : 'password'}
                  className="form-input"
                  placeholder={syncKeySaved ? "已安全存储，输入新密钥可替换" : "输入你的 API 密钥"}
                  value={syncConfig.api_key}
                  onChange={handleSyncApiKeyChange}
                  style={{ paddingRight: '80px' }}
                />
                <button
                  type="button"
                  style={{
                    position: 'absolute',
                    right: '8px',
                    top: '50%',
                    transform: 'translateY(-50%)',
                    padding: '4px 8px',
                    border: 'none',
                    background: 'transparent',
                    cursor: 'pointer',
                    fontSize: '12px',
                    color: '#007aff',
                  }}
                  onClick={() => setShowSyncKey(!showSyncKey)}
                >
                  {showSyncKey ? '隐藏' : '显示'}
                </button>
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <input
                type="checkbox"
                id="auto-sync-setting"
                checked={syncConfig.auto_sync}
                onChange={(e) => setSyncConfig(prev => ({ ...prev, auto_sync: e.target.checked }))}
              />
              <label htmlFor="auto-sync-setting" style={{ fontSize: '13px', color: '#333' }}>
                自动同步
              </label>
            </div>
            {syncConfig.auto_sync && (
              <div className="form-group">
                <label className="form-label">同步间隔 (分钟)</label>
                <input
                  type="number"
                  className="form-input"
                  min="5"
                  value={syncConfig.sync_interval}
                  onChange={(e) => setSyncConfig(prev => ({ 
                    ...prev, 
                    sync_interval: parseInt(e.target.value) || 30 
                  }))}
                />
              </div>
            )}
            <div className="modal-footer" style={{ justifyContent: 'flex-end', padding: '16px 0 0 0', borderTop: 'none' }}>
              <button className="button button-primary" onClick={saveSyncConfig} disabled={saving}>
                {saving ? <span className="loading" /> : '保存'}
              </button>
            </div>
          </div>
        )}

        {activeTab === 'ai' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <SecurityIndicator />
            
            <div className="form-group">
              <label className="form-label">API 地址</label>
              <input
                type="text"
                className="form-input"
                placeholder="https://api.openai.com/v1"
                value={aiConfig.api_url}
                onChange={(e) => setAiConfig(prev => ({ ...prev, api_url: e.target.value }))}
              />
            </div>
            <div className="form-group">
              <label className="form-label">
                API 密钥
                {aiKeySaved && (
                  <span style={{ 
                    marginLeft: '8px', 
                    fontSize: '11px', 
                    color: '#34c759',
                    fontWeight: '500'
                  }}>
                    ✓ 已安全存储
                  </span>
                )}
              </label>
              <div style={{ position: 'relative' }}>
                <input
                  type={showAiKey ? 'text' : 'password'}
                  className="form-input"
                  placeholder={aiKeySaved ? "已安全存储，输入新密钥可替换" : "sk-..."}
                  value={aiConfig.api_key}
                  onChange={handleAiApiKeyChange}
                  style={{ paddingRight: '80px' }}
                />
                <button
                  type="button"
                  style={{
                    position: 'absolute',
                    right: '8px',
                    top: '50%',
                    transform: 'translateY(-50%)',
                    padding: '4px 8px',
                    border: 'none',
                    background: 'transparent',
                    cursor: 'pointer',
                    fontSize: '12px',
                    color: '#007aff',
                  }}
                  onClick={() => setShowAiKey(!showAiKey)}
                >
                  {showAiKey ? '隐藏' : '显示'}
                </button>
              </div>
            </div>
            <div className="form-group">
              <label className="form-label">模型</label>
              <input
                type="text"
                className="form-input"
                placeholder="gpt-3.5-turbo"
                value={aiConfig.model}
                onChange={(e) => setAiConfig(prev => ({ ...prev, model: e.target.value }))}
              />
            </div>
            <div className="form-group">
              <label className="form-label">最大输出 Token</label>
              <input
                type="number"
                className="form-input"
                min="100"
                max="4000"
                value={aiConfig.max_tokens}
                onChange={(e) => setAiConfig(prev => ({ 
                  ...prev, 
                  max_tokens: parseInt(e.target.value) || 500 
                }))}
              />
            </div>
            <p style={{ fontSize: '12px', color: '#999' }}>
              配置后可使用 AI 摘要生成功能。您的 API 密钥将通过系统密钥环加密存储，绝不会暴露给渲染进程。
            </p>
            <div className="modal-footer" style={{ justifyContent: 'flex-end', padding: '16px 0 0 0', borderTop: 'none' }}>
              <button className="button button-primary" onClick={saveAiConfig} disabled={saving}>
                {saving ? <span className="loading" /> : '保存'}
              </button>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
};
