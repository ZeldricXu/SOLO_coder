import React, { useState, useEffect, useCallback } from 'react';
import { Search, MessageSquare, Clock, Menu, X, BookOpen, ChevronRight } from 'lucide-react';
import DirectoryNav from './components/DirectoryNav';
import DocumentEditor from './components/DocumentEditor';
import SearchComponent from './components/SearchComponent';
import VersionPanel from './components/VersionPanel';
import CommentPanel from './components/CommentPanel';
import { documentApi } from './lib/api';

function App() {
  const [selectedDocument, setSelectedDocument] = useState(null);
  const [selectedFolder, setSelectedFolder] = useState(null);
  const [searchOpen, setSearchOpen] = useState(false);
  const [activePanel, setActivePanel] = useState(null);
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [loading, setLoading] = useState(false);

  const currentUserId = 'user_default';
  const currentUserName = '当前用户';

  const handleSelectDocument = async (doc) => {
    try {
      setLoading(true);
      
      if (doc.doc_id) {
        const response = await documentApi.getById(doc.doc_id);
        if (response.code === 200) {
          setSelectedDocument(response.data);
        }
      } else {
        setSelectedDocument(doc);
      }
      
      setSelectedFolder(null);
      setActivePanel(null);
    } catch (error) {
      console.error('Failed to load document:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleSelectFolder = (folder) => {
    setSelectedFolder(folder);
    setSelectedDocument(null);
    setActivePanel(null);
  };

  const togglePanel = (panel) => {
    setActivePanel(activePanel === panel ? null : panel);
  };

  useEffect(() => {
    const handleKeyDown = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
        e.preventDefault();
        setSearchOpen(true);
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, []);

  return (
    <div className="h-screen flex flex-col bg-slate-50">
      <header className="h-14 bg-white border-b border-slate-200 flex items-center justify-between px-4 shrink-0">
        <div className="flex items-center gap-3">
          <button
            onClick={() => setSidebarOpen(!sidebarOpen)}
            className="p-2 rounded-lg hover:bg-slate-100 transition-colors lg:hidden"
          >
            {sidebarOpen ? <X size={20} /> : <Menu size={20} />}
          </button>
          
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-primary-600 flex items-center justify-center">
              <BookOpen size={18} className="text-white" />
            </div>
            <h1 className="text-lg font-semibold text-slate-900">WikiHub</h1>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => setSearchOpen(true)}
            className="flex items-center gap-2 px-3 py-1.5 bg-slate-100 hover:bg-slate-200 rounded-lg text-slate-500 hover:text-slate-700 transition-colors"
          >
            <Search size={16} />
            <span className="text-sm hidden sm:inline">搜索文档...</span>
            <kbd className="hidden md:inline-block px-1.5 py-0.5 text-xs bg-white border border-slate-300 rounded">
              ⌘K
            </kbd>
          </button>

          {selectedDocument && (
            <div className="flex items-center gap-1">
              <button
                onClick={() => togglePanel('comments')}
                className={`p-2 rounded-lg transition-colors ${
                  activePanel === 'comments'
                    ? 'bg-primary-100 text-primary-600'
                    : 'text-slate-500 hover:bg-slate-100 hover:text-slate-700'
                }`}
                title="评论"
              >
                <MessageSquare size={20} />
              </button>
              <button
                onClick={() => togglePanel('versions')}
                className={`p-2 rounded-lg transition-colors ${
                  activePanel === 'versions'
                    ? 'bg-primary-100 text-primary-600'
                    : 'text-slate-500 hover:bg-slate-100 hover:text-slate-700'
                }`}
                title="版本历史"
              >
                <Clock size={20} />
              </button>
            </div>
          )}

          <div className="w-8 h-8 rounded-full bg-primary-600 flex items-center justify-center text-white text-sm font-medium">
            {currentUserName.charAt(0)}
          </div>
        </div>
      </header>

      <div className="flex-1 flex overflow-hidden">
        <aside
          className={`${
            sidebarOpen ? 'w-72' : 'w-0'
          } lg:w-72 shrink-0 bg-white border-r border-slate-200 overflow-hidden transition-all duration-300`}
        >
          <DirectoryNav
            onSelectDocument={handleSelectDocument}
            onSelectFolder={handleSelectFolder}
            selectedItem={selectedDocument || selectedFolder}
          />
        </aside>

        <main className="flex-1 flex overflow-hidden">
          <div className="flex-1 overflow-hidden">
            {loading ? (
              <div className="flex items-center justify-center h-full">
                <div className="w-8 h-8 border-4 border-slate-200 border-t-primary-500 rounded-full animate-spin" />
              </div>
            ) : selectedDocument ? (
              <DocumentEditor
                document={selectedDocument}
                userId={currentUserId}
                userName={currentUserName}
              />
            ) : selectedFolder ? (
              <div className="flex flex-col items-center justify-center h-full gap-4 px-8 text-center">
                <div className="w-16 h-16 rounded-full bg-primary-100 flex items-center justify-center">
                  <BookOpen size={32} className="text-primary-600" />
                </div>
                <div>
                  <h2 className="text-xl font-semibold text-slate-900">
                    {selectedFolder.name}
                  </h2>
                  <p className="text-slate-500 mt-1">
                    从左侧目录中选择一个文档开始编辑，或创建新文档
                  </p>
                </div>
              </div>
            ) : (
              <div className="flex flex-col items-center justify-center h-full gap-6 px-8 text-center">
                <div className="w-20 h-20 rounded-2xl bg-gradient-to-br from-primary-500 to-primary-700 flex items-center justify-center">
                  <BookOpen size={40} className="text-white" />
                </div>
                <div>
                  <h2 className="text-2xl font-bold text-slate-900">欢迎使用 WikiHub</h2>
                  <p className="text-slate-500 mt-2 max-w-md">
                    知识库文档协作与版本管理平台，支持多人实时协作编辑、文档版本历史、全文检索搜索
                  </p>
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mt-4 max-w-2xl">
                  <div className="p-4 bg-white border border-slate-200 rounded-xl">
                    <div className="w-10 h-10 rounded-lg bg-blue-100 flex items-center justify-center mb-3">
                      <BookOpen size={20} className="text-blue-600" />
                    </div>
                    <h3 className="font-medium text-slate-900">文档编辑</h3>
                    <p className="text-sm text-slate-500 mt-1">支持 Markdown 与富文本双模式</p>
                  </div>
                  <div className="p-4 bg-white border border-slate-200 rounded-xl">
                    <div className="w-10 h-10 rounded-lg bg-green-100 flex items-center justify-center mb-3">
                      <MessageSquare size={20} className="text-green-600" />
                    </div>
                    <h3 className="font-medium text-slate-900">实时协作</h3>
                    <p className="text-sm text-slate-500 mt-1">多人同时编辑，即时同步</p>
                  </div>
                  <div className="p-4 bg-white border border-slate-200 rounded-xl">
                    <div className="w-10 h-10 rounded-lg bg-purple-100 flex items-center justify-center mb-3">
                      <Clock size={20} className="text-purple-600" />
                    </div>
                    <h3 className="font-medium text-slate-900">版本管理</h3>
                    <p className="text-sm text-slate-500 mt-1">历史版本记录，一键回滚</p>
                  </div>
                </div>
                <button
                  onClick={() => setSearchOpen(true)}
                  className="flex items-center gap-2 px-6 py-3 bg-primary-600 hover:bg-primary-700 text-white rounded-xl font-medium transition-colors"
                >
                  <Search size={18} />
                  开始搜索文档
                  <kbd className="px-1.5 py-0.5 text-xs bg-primary-800 rounded">
                    ⌘K
                  </kbd>
                </button>
              </div>
            )}
          </div>

          {activePanel && selectedDocument && (
            <aside className="w-80 shrink-0 bg-white border-l border-slate-200 overflow-hidden">
              {activePanel === 'comments' ? (
                <CommentPanel
                  documentId={selectedDocument.doc_id}
                />
              ) : activePanel === 'versions' ? (
                <VersionPanel
                  documentId={selectedDocument.doc_id}
                />
              ) : null}
            </aside>
          )}
        </main>
      </div>

      <SearchComponent
        isOpen={searchOpen}
        onClose={() => setSearchOpen(false)}
        onOpen={() => setSearchOpen(true)}
        onSelectDocument={handleSelectDocument}
        onSelectFolder={handleSelectFolder}
      />
    </div>
  );
}

export default App;
