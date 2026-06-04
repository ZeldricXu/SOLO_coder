import React, { useState, useEffect, useCallback } from 'react';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { GitStatus, GitCommit, GitConfig, DiffHunk, DiffLine, IPCResponse } from '@shared/types';
import { formatRelative, formatDate } from '@shared/utils/date';

const getStatusIcon = (status: string): string => {
  switch (status) {
    case 'added':
    case 'untracked':
      return '?';
    case 'modified':
      return 'M';
    case 'deleted':
      return 'D';
    case 'ignored':
      return '!';
    default:
      return '?';
  }
};

const getStatusBadgeClass = (status: string): string => {
  switch (status) {
    case 'added':
    case 'untracked':
      return 'badge-success';
    case 'modified':
      return 'badge-warning';
    case 'deleted':
      return 'badge-error';
    default:
      return 'badge-primary';
  }
};

const getStatusLabel = (status: string): string => {
  switch (status) {
    case 'added':
      return '新增';
    case 'untracked':
      return '未跟踪';
    case 'modified':
      return '修改';
    case 'deleted':
      return '删除';
    case 'ignored':
      return '忽略';
    default:
      return status;
  }
};

const handleIPCResponse = <T,>(response: IPCResponse<T>): T => {
  if (!response.success) {
    throw new Error(response.error);
  }
  return response.data;
};

const DiffLineComponent: React.FC<{ line: DiffLine }> = ({ line }) => {
  const getLineClass = (): string => {
    switch (line.type) {
      case 'added':
        return 'bg-green-500/10 text-green-400';
      case 'removed':
        return 'bg-red-500/10 text-red-400';
      default:
        return 'text-gray-400';
    }
  };

  const getPrefix = (): string => {
    switch (line.type) {
      case 'added':
        return '+';
      case 'removed':
        return '-';
      default:
        return ' ';
    }
  };

  return (
    <div className={`flex ${getLineClass()}`}>
      <span className="w-12 text-right pr-4 text-gray-500 select-none border-r border-gray-700 flex-shrink-0">
        {line.oldLineNumber || ''}
      </span>
      <span className="w-12 text-right pr-4 text-gray-500 select-none border-r border-gray-700 flex-shrink-0">
        {line.newLineNumber || ''}
      </span>
      <span className="px-2 select-none w-6 text-center">{getPrefix()}</span>
      <span className="flex-1 whitespace-pre px-2 font-mono text-sm">{line.content}</span>
    </div>
  );
};

const DiffViewer: React.FC<{ hunks: DiffHunk[]; filename: string }> = ({ hunks, filename }) => {
  if (hunks.length === 0) {
    return (
      <div className="p-4 text-center text-gray-500">
        该文件没有变更内容
      </div>
    );
  }

  return (
    <div className="h-full flex flex-col">
      <div className="px-4 py-2 border-b border-gray-700 bg-gray-800/50 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-lg">📄</span>
          <span className="font-medium">{filename}</span>
        </div>
      </div>
      <div className="flex-1 overflow-auto bg-gray-900/50">
        {hunks.map((hunk, hunkIndex) => (
          <div key={hunkIndex} className="mb-2">
            <div className="px-4 py-1 bg-blue-900/20 text-blue-400 text-sm font-mono">
              @@ -{hunk.oldStart},{hunk.oldLines} +{hunk.newStart},{hunk.newLines} @@
            </div>
            {hunk.lines.map((line, lineIndex) => (
              <DiffLineComponent key={lineIndex} line={line} />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
};

const GitPage: React.FC = () => {
  const [isInitialized, setIsInitialized] = useState<boolean>(false);
  const [isInitializing, setIsInitializing] = useState<boolean>(false);
  const [statuses, setStatuses] = useState<GitStatus[]>([]);
  const [commits, setCommits] = useState<GitCommit[]>([]);
  const [config, setConfig] = useState<GitConfig | null>(null);
  const [commitMessage, setCommitMessage] = useState<string>('');
  const [isCommitting, setIsCommitting] = useState<boolean>(false);
  const [isPushing, setIsPushing] = useState<boolean>(false);
  const [isPulling, setIsPulling] = useState<boolean>(false);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [diffFile, setDiffFile] = useState<string | null>(null);
  const [diffHunks, setDiffHunks] = useState<DiffHunk[]>([]);
  const [showDiff, setShowDiff] = useState<boolean>(false);
  const [remoteUrl, setRemoteUrl] = useState<string>('');
  const [showRemoteConfig, setShowRemoteConfig] = useState<boolean>(false);
  const [autoCommit, setAutoCommit] = useState<boolean>(true);
  const [autoCommitInterval, setAutoCommitInterval] = useState<number>(30);
  const [autoPush, setAutoPush] = useState<boolean>(false);
  const [activeTab, setActiveTab] = useState<'changes' | 'history'>('changes');
  const [error, setError] = useState<string | null>(null);

  const loadGitData = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const configResponse = await window.electron.ipc.invoke<IPCResponse<GitConfig>>(
        IPC_CHANNELS.GIT.CONFIG_GET
      );
      
      if (!configResponse.success) {
        setIsInitialized(false);
        setIsLoading(false);
        return;
      }

      const config = handleIPCResponse(configResponse);
      setConfig(config);
      setAutoCommit(config.autoCommit);
      setAutoCommitInterval(Math.floor(config.autoCommitInterval / 1000));
      setAutoPush(config.autoPush);
      setRemoteUrl(config.remoteUrl || '');

      const [statusResponse, logResponse] = await Promise.all([
        window.electron.ipc.invoke<IPCResponse<GitStatus[]>>(IPC_CHANNELS.GIT.STATUS),
        window.electron.ipc.invoke<IPCResponse<GitCommit[]>>(IPC_CHANNELS.GIT.LOG, 50),
      ]);

      setStatuses(handleIPCResponse(statusResponse) || []);
      setCommits(handleIPCResponse(logResponse) || []);
      setIsInitialized(true);
    } catch (err) {
      setIsInitialized(false);
      setError(err instanceof Error ? err.message : '加载 Git 数据失败');
      console.error('加载 Git 数据失败:', err);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    loadGitData();
  }, [loadGitData]);

  const handleInit = async () => {
    setIsInitializing(true);
    setError(null);
    try {
      const response = await window.electron.ipc.invoke<IPCResponse<void>>(IPC_CHANNELS.GIT.INIT);
      handleIPCResponse(response);
      await loadGitData();
    } catch (err) {
      setError(err instanceof Error ? err.message : '初始化仓库失败');
      console.error('初始化仓库失败:', err);
    } finally {
      setIsInitializing(false);
    }
  };

  const handleFileClick = async (filepath: string) => {
    try {
      const response = await window.electron.ipc.invoke<IPCResponse<DiffHunk[]>>(
        IPC_CHANNELS.GIT.DIFF,
        filepath
      );
      const hunks = handleIPCResponse(response);
      setDiffHunks(hunks || []);
      setDiffFile(filepath);
      setShowDiff(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : '获取差异失败');
      console.error('获取差异失败:', err);
    }
  };

  const handleCommit = async () => {
    if (!commitMessage.trim()) {
      setError('请输入提交消息');
      return;
    }
    if (statuses.length === 0) {
      setError('没有可提交的变更');
      return;
    }

    setIsCommitting(true);
    setError(null);
    try {
      const response = await window.electron.ipc.invoke<IPCResponse<string>>(
        IPC_CHANNELS.GIT.COMMIT,
        commitMessage.trim()
      );
      handleIPCResponse(response);
      setCommitMessage('');
      await loadGitData();
    } catch (err) {
      setError(err instanceof Error ? err.message : '提交失败');
      console.error('提交失败:', err);
    } finally {
      setIsCommitting(false);
    }
  };

  const handlePush = async () => {
    setIsPushing(true);
    setError(null);
    try {
      const response = await window.electron.ipc.invoke<IPCResponse<void>>(IPC_CHANNELS.GIT.PUSH);
      handleIPCResponse(response);
      await loadGitData();
    } catch (err) {
      setError(err instanceof Error ? err.message : '推送失败');
      console.error('推送失败:', err);
    } finally {
      setIsPushing(false);
    }
  };

  const handlePull = async () => {
    setIsPulling(true);
    setError(null);
    try {
      const response = await window.electron.ipc.invoke<IPCResponse<void>>(IPC_CHANNELS.GIT.PULL);
      handleIPCResponse(response);
      await loadGitData();
    } catch (err) {
      setError(err instanceof Error ? err.message : '拉取失败');
      console.error('拉取失败:', err);
    } finally {
      setIsPulling(false);
    }
  };

  const handleSaveRemote = async () => {
    try {
      const response = await window.electron.ipc.invoke<IPCResponse<void>>(
        IPC_CHANNELS.GIT.REMOTE_SET,
        remoteUrl.trim()
      );
      handleIPCResponse(response);
      setShowRemoteConfig(false);
      await loadGitData();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存远程配置失败');
      console.error('保存远程配置失败:', err);
    }
  };

  const handleAutoCommitChange = async (enabled: boolean) => {
    setAutoCommit(enabled);
    try {
      const response = await window.electron.ipc.invoke<IPCResponse<void>>(
        IPC_CHANNELS.GIT.CONFIG_SET,
        { autoCommit: enabled } as Partial<GitConfig>
      );
      handleIPCResponse(response);
    } catch (err) {
      console.error('保存自动提交设置失败:', err);
    }
  };

  const handleAutoPushChange = async (enabled: boolean) => {
    setAutoPush(enabled);
    try {
      const response = await window.electron.ipc.invoke<IPCResponse<void>>(
        IPC_CHANNELS.GIT.CONFIG_SET,
        { autoPush: enabled } as Partial<GitConfig>
      );
      handleIPCResponse(response);
    } catch (err) {
      console.error('保存自动推送设置失败:', err);
    }
  };

  const handleIntervalChange = async (seconds: number) => {
    setAutoCommitInterval(seconds);
    try {
      const response = await window.electron.ipc.invoke<IPCResponse<void>>(
        IPC_CHANNELS.GIT.CONFIG_SET,
        { autoCommitInterval: seconds * 1000 } as Partial<GitConfig>
      );
      handleIPCResponse(response);
    } catch (err) {
      console.error('保存自动提交间隔失败:', err);
    }
  };

  if (isLoading) {
    return (
      <div className="h-full flex items-center justify-center">
        <div className="text-center">
          <div className="text-4xl mb-4 animate-spin">⚙️</div>
          <div className="text-lg font-medium">正在加载 Git 数据...</div>
        </div>
      </div>
    );
  }

  if (!isInitialized) {
    return (
      <div className="h-full flex items-center justify-center">
        <div className="card p-8 max-w-md text-center">
          <div className="text-6xl mb-4">🔀</div>
          <h2 className="text-2xl font-bold mb-2">Git 版本控制</h2>
          <p className="text-gray-500 dark:text-gray-400 mb-6">
            当前工作区尚未初始化 Git 仓库。初始化后可以追踪文件变更、提交历史、同步到远程仓库。
          </p>
          <button
            onClick={handleInit}
            disabled={isInitializing}
            className="btn btn-primary px-6 py-2"
          >
            {isInitializing ? '正在初始化...' : '初始化 Git 仓库'}
          </button>
          {error && (
            <div className="mt-4 p-3 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-lg text-sm">
              {error}
            </div>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="h-full flex flex-col bg-[var(--background-color)]">
      <div className="px-6 py-4 border-b border-[var(--border-color)] flex items-center justify-between">
        <div className="flex items-center gap-4">
          <h1 className="text-xl font-bold">🔀 版本管理</h1>
          <div className="flex items-center gap-2">
            <span className="btn btn-secondary flex items-center gap-2 cursor-default">
              <span>🌿</span>
              <span>{config?.remoteName || 'main'}</span>
            </span>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <div className="flex items-center gap-2 mr-4">
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={autoCommit}
                onChange={(e) => handleAutoCommitChange(e.target.checked)}
                className="w-4 h-4 rounded border-gray-300"
              />
              <span className="text-sm">自动提交</span>
            </label>
            {autoCommit && (
              <select
                value={autoCommitInterval}
                onChange={(e) => handleIntervalChange(Number(e.target.value))}
                className="input text-sm py-1 w-28"
              >
                <option value={10}>10秒</option>
                <option value={30}>30秒</option>
                <option value={60}>1分钟</option>
                <option value={300}>5分钟</option>
                <option value={600}>10分钟</option>
              </select>
            )}
            <label className="flex items-center gap-2 cursor-pointer ml-2">
              <input
                type="checkbox"
                checked={autoPush}
                onChange={(e) => handleAutoPushChange(e.target.checked)}
                className="w-4 h-4 rounded border-gray-300"
              />
              <span className="text-sm">自动推送</span>
            </label>
          </div>

          <button
            onClick={() => setShowRemoteConfig(!showRemoteConfig)}
            className="btn btn-secondary"
          >
            ⚙️ 远程配置
          </button>
          <button
            onClick={handlePull}
            disabled={isPulling || !remoteUrl}
            className="btn btn-secondary"
          >
            {isPulling ? '拉取中...' : '⬇️ 拉取'}
          </button>
          <button
            onClick={handlePush}
            disabled={isPushing || !remoteUrl}
            className="btn btn-primary"
          >
            {isPushing ? '推送中...' : '⬆️ 推送'}
          </button>
          <button
            onClick={loadGitData}
            className="btn btn-secondary"
          >
            🔄 刷新
          </button>
        </div>
      </div>

      {error && (
        <div className="mx-6 mt-4 p-3 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-lg text-sm flex items-center justify-between">
          <span>⚠️ {error}</span>
          <button onClick={() => setError(null)} className="hover:text-red-800">✕</button>
        </div>
      )}

      {showRemoteConfig && (
        <div className="mx-6 mt-4 card p-4">
          <div className="flex items-center justify-between mb-3">
            <h3 className="font-medium">远程仓库配置</h3>
            <button
              onClick={() => setShowRemoteConfig(false)}
              className="text-gray-500 hover:text-gray-700"
            >
              ✕
            </button>
          </div>
          <div className="flex gap-2">
            <input
              type="text"
              placeholder="输入远程仓库 URL (例如: https://github.com/user/repo.git)"
              value={remoteUrl}
              onChange={(e) => setRemoteUrl(e.target.value)}
              className="input flex-1"
            />
            <button onClick={handleSaveRemote} className="btn btn-primary">
              保存
            </button>
          </div>
          {config?.remoteUrl && (
            <div className="mt-2 text-sm text-gray-500">
              当前远程: {config.remoteUrl}
            </div>
          )}
        </div>
      )}

      <div className="flex border-b border-[var(--border-color)]">
        <button
          onClick={() => setActiveTab('changes')}
          className={`tab ${activeTab === 'changes' ? 'tab-active' : ''}`}
        >
          📝 变更 ({statuses.length})
        </button>
        <button
          onClick={() => setActiveTab('history')}
          className={`tab ${activeTab === 'history' ? 'tab-active' : ''}`}
        >
          📜 历史 ({commits.length})
        </button>
      </div>

      <div className="flex-1 flex overflow-hidden">
        {activeTab === 'changes' && (
          <>
            <div className={`flex flex-col border-r border-[var(--border-color)] ${showDiff ? 'w-1/2' : 'w-full'}`}>
              <div className="p-4 border-b border-[var(--border-color)]">
                <div className="flex items-center justify-between mb-3">
                  <h3 className="font-medium">工作区变更</h3>
                  <span className="text-sm text-gray-500">
                    共 {statuses.length} 个变更文件
                  </span>
                </div>
                <div className="space-y-1 max-h-80 overflow-y-auto">
                  {statuses.length === 0 ? (
                    <div className="text-center py-8 text-gray-500">
                      <div className="text-3xl mb-2">✨</div>
                      <div>没有未提交的变更</div>
                    </div>
                  ) : (
                    statuses.map((status) => (
                      <div
                        key={status.filepath}
                        onClick={() => handleFileClick(status.filepath)}
                        className="flex items-center gap-3 p-2 rounded-lg cursor-pointer transition-colors hover:bg-gray-50 dark:hover:bg-gray-800"
                      >
                        <span
                          className={`badge ${getStatusBadgeClass(status.status)} w-6 justify-center`}
                        >
                          {getStatusIcon(status.status)}
                        </span>
                        <span className="flex-1 truncate">
                          {status.filepath}
                        </span>
                        <span className="text-xs text-gray-500">
                          {getStatusLabel(status.status)}
                        </span>
                      </div>
                    ))
                  )}
                </div>
              </div>

              <div className="p-4 flex-1">
                <h3 className="font-medium mb-3">提交更改</h3>
                <textarea
                  placeholder="输入提交消息..."
                  value={commitMessage}
                  onChange={(e) => setCommitMessage(e.target.value)}
                  className="input w-full h-24 resize-none mb-3"
                />
                <div className="flex items-center justify-between">
                  <span className="text-sm text-gray-500">
                    将提交 {statuses.length} 个变更文件
                  </span>
                  <button
                    onClick={handleCommit}
                    disabled={isCommitting || statuses.length === 0 || !commitMessage.trim()}
                    className="btn btn-primary"
                  >
                    {isCommitting ? '提交中...' : '✅ 提交'}
                  </button>
                </div>
              </div>
            </div>

            {showDiff && diffFile && (
              <div className="w-1/2 flex flex-col">
                <div className="p-2 border-b border-[var(--border-color)] flex items-center justify-between">
                  <span className="font-medium">差异对比</span>
                  <button
                    onClick={() => setShowDiff(false)}
                    className="btn btn-ghost text-sm"
                  >
                    ✕ 关闭
                  </button>
                </div>
                <div className="flex-1 overflow-hidden">
                  <DiffViewer hunks={diffHunks} filename={diffFile} />
                </div>
              </div>
            )}
          </>
        )}

        {activeTab === 'history' && (
          <div className="flex-1 overflow-y-auto">
            {commits.length === 0 ? (
              <div className="text-center py-16 text-gray-500">
                <div className="text-4xl mb-3">📜</div>
                <div className="text-lg">暂无提交记录</div>
                <div className="text-sm mt-1">提交更改后将在这里显示历史记录</div>
              </div>
            ) : (
              <div className="divide-y divide-[var(--border-color)]">
                {commits.map((commit) => (
                  <div
                    key={commit.sha}
                    className="p-4 hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors"
                  >
                    <div className="flex items-start justify-between">
                      <div className="flex-1">
                        <div className="font-medium mb-1">
                          {commit.message.split('\n')[0]}
                        </div>
                        <div className="text-sm text-gray-500 flex items-center gap-4">
                          <span>👤 {commit.author.name}</span>
                          <span>📧 {commit.author.email}</span>
                          <span>🕐 {formatRelative(commit.timestamp)}</span>
                          <span className="font-mono text-xs bg-gray-100 dark:bg-gray-700 px-2 py-0.5 rounded">
                            {commit.sha.slice(0, 7)}
                          </span>
                        </div>
                        <div className="text-xs text-gray-400 mt-1">
                          {formatDate(commit.timestamp, 'YYYY-MM-DD HH:mm:ss')}
                        </div>
                      </div>
                      <div className="flex gap-2">
                        <button
                          onClick={() => navigator.clipboard.writeText(commit.sha)}
                          className="btn btn-ghost text-xs"
                          title="复制提交哈希"
                        >
                          📋
                        </button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export { GitPage };
