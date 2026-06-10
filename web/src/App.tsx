import React from 'react';
import Canvas from './components/Canvas/Canvas';
import Toolbar from './components/Toolbar/Toolbar';
import ViewportControls from './components/Canvas/ViewportControls';
import UserAvatar from './components/Users/UserAvatar';
import UserCursor from './components/Users/UserCursor';
import ExportPanel from './components/Export/ExportPanel';
import VersionTree from './components/History/VersionTree';
import CommentThread from './components/Comments/CommentThread';
import { useWasm } from './hooks/useWasm';
import { useCollaboration } from './hooks/useCollaboration';
import { useBoardStore } from './stores/useBoardStore';
import { useUserStore } from './stores/useUserStore';

const App: React.FC = () => {
  const { isLoading: wasmLoading, error: wasmError } = useWasm();
  const { isConnected, users } = useCollaboration();
  const remoteUsers = useUserStore((state) => state.remoteUsers);
  const { showExportPanel, showVersionTree, showComments } = useBoardStore();

  if (wasmLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' }}>
        <div>正在加载核心模块...</div>
      </div>
    );
  }

  if (wasmError) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%', color: 'red' }}>
        <div>加载失败: {wasmError.message}</div>
      </div>
    );
  }

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%' }}>
      <Canvas />
      <Toolbar />
      <ViewportControls />
      
      <div style={{ position: 'absolute', top: 16, right: 16, display: 'flex', gap: 8 }}>
        {users.map((user) => (
          <UserAvatar key={user.id} user={user} />
        ))}
        <div style={{ alignSelf: 'center', marginLeft: 8, fontSize: 12, color: isConnected ? '#22c55e' : '#ef4444' }}>
          {isConnected ? '已连接' : '未连接'}
        </div>
      </div>

      {remoteUsers.map((user) => (
        user.cursor && <UserCursor key={user.id} user={user} />
      ))}

      {showExportPanel && <ExportPanel />}
      {showVersionTree && <VersionTree />}
      {showComments && <CommentThread commentId="demo-thread" />}
    </div>
  );
};

export default App;
