import React, { useEffect } from 'react';
import { ThemeProvider } from './contexts/ThemeContext';
import { MainLayout } from './layouts/MainLayout';
import { EditorPage } from './pages/EditorPage';
import { GraphPage } from './pages/GraphPage';
import { SearchPage } from './pages/SearchPage';
import { GitPage } from './pages/GitPage';
import { WelcomePage } from './pages/WelcomePage';
import { ExportPage } from './pages/ExportPage';
import { useAppStore } from './stores/appStore';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import { useIPCListener } from './hooks/useIPCListener';
import './styles/globals.css';

const AppContent: React.FC = () => {
  const initApp = useAppStore((state) => state.initApp);
  const isLoading = useAppStore((state) => state.isLoading);
  const error = useAppStore((state) => state.error);
  const setError = useAppStore((state) => state.setError);
  const activeTab = useAppStore((state) => state.activeTab);
  const currentDocument = useAppStore((state) => state.currentDocument);
  const documents = useAppStore((state) => state.documents);

  useEffect(() => {
    initApp();

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && error) {
        setError(null);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [initApp, error, setError]);

  useIPCListener(IPC_CHANNELS.FILE.CHANGED, (data: any) => {
    console.log('文件变更:', data);
    initApp();
  });

  useIPCListener(IPC_CHANNELS.GIT.COMMITTED, (data: any) => {
    console.log('Git提交成功:', data);
  });

  const renderContent = () => {
    if (isLoading) {
      return (
        <div className="h-full flex items-center justify-center">
          <div className="text-center">
            <div className="text-4xl mb-4">📚</div>
            <div className="text-lg font-medium">正在加载知识库...</div>
            <div className="text-sm text-gray-500 mt-2">请稍候</div>
          </div>
        </div>
      );
    }

    if (documents.length === 0 && activeTab === 'editor') {
      return <WelcomePage />;
    }

    if (!currentDocument && activeTab === 'editor') {
      return <WelcomePage />;
    }

    switch (activeTab) {
      case 'editor':
        return <EditorPage />;
      case 'graph':
        return <GraphPage />;
      case 'search':
        return <SearchPage />;
      case 'git':
        return <GitPage />;
      case 'export':
        return <ExportPage />;
      default:
        return <EditorPage />;
    }
  };

  return (
    <MainLayout>
      {error && (
        <div className="fixed top-16 right-4 z-50 max-w-sm">
          <div className="card p-4 border-red-300 dark:border-red-700 bg-red-50 dark:bg-red-900/20">
            <div className="flex items-start justify-between">
              <div className="flex items-start gap-2">
                <span className="text-red-500">⚠️</span>
                <div>
                  <div className="font-medium text-red-800 dark:text-red-200">错误</div>
                  <div className="text-sm text-red-600 dark:text-red-300 mt-1">
                    {error}
                  </div>
                </div>
              </div>
              <button
                onClick={() => setError(null)}
                className="text-red-400 hover:text-red-600"
              >
                ✕
              </button>
            </div>
          </div>
        </div>
      )}
      {renderContent()}
    </MainLayout>
  );
};

export const App: React.FC = () => {
  return (
    <ThemeProvider>
      <AppContent />
    </ThemeProvider>
  );
};

export default App;
