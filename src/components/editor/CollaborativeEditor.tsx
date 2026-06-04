'use client';

import * as React from 'react';
import { useState, useEffect, useRef, useCallback } from 'react';
import * as Y from 'yjs';
import { Editor } from '@tiptap/react';
import Collaboration from '@tiptap/extension-collaboration';
import CollaborationCursor from '@tiptap/extension-collaboration-cursor';
import { TiptapEditor } from './TiptapEditor';
import { useYjsProvider } from './useYjsProvider';
import { CursorPresence, SaveStatusIndicator, UserActivityIndicator } from './CursorPresence';
import type { CollabConnectionConfig, DocumentPermissions, AwarenessState, CursorPosition } from '@/lib/collab/types';
import { generateUserColor, markdownToYDoc, yDocToMarkdown } from '@/lib/collab/utils';
import { AlertCircle, Wifi, WifiOff, RefreshCw } from 'lucide-react';

interface CollaborativeEditorProps {
  config?: CollabConnectionConfig;
  documentId?: string;
  initialContent?: string;
  initialTitle?: string;
  userId?: string;
  userName?: string;
  userAvatar?: string;
  token?: string;
  permissions?: DocumentPermissions;
  placeholder?: string;
  className?: string;
  onContentChange?: (content: string, markdown: string) => void;
  onSync?: (doc: Y.Doc) => void;
  onSave?: (version: number, timestamp: Date) => void;
  onStatusChange?: (status: string) => void;
  onError?: (error: string) => void;
  readOnly?: boolean;
  showToolbar?: boolean;
  showStatusBar?: boolean;
  showUserPresence?: boolean;
}

export function CollaborativeEditor(props: CollaborativeEditorProps) {
  const {
    config,
    documentId,
    initialContent = '',
    initialTitle,
    userId,
    userName,
    userAvatar,
    token,
    permissions,
    placeholder = '开始协作编辑...',
    className = '',
    onContentChange,
    onSync,
    onSave,
    onStatusChange,
    onError,
    readOnly = false,
    showToolbar = true,
    showStatusBar = true,
    showUserPresence = true,
  } = props;

  const resolvedConfig: CollabConnectionConfig | undefined = config ?? (
    documentId && userId && userName
      ? {
          documentId,
          userId,
          userName,
          userAvatar,
          token: token ?? '',
          roomName: `doc-${documentId}`,
        }
      : undefined
  );
  const editorRef = useRef<Editor | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [isEditorReady, setIsEditorReady] = useState(false);
  const [contentInitialized, setContentInitialized] = useState(false);
  const [isLocalChange, setIsLocalChange] = useState(false);
  const activityTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  const userColor = resolvedConfig ? generateUserColor(resolvedConfig.userId) : { primary: '#3b82f6', secondary: '#93c5fd' };

  const {
    doc,
    provider,
    awareness,
    status,
    editorState,
    onlineUsers,
    connect,
    disconnect,
    forceSync,
    setLocalAwareness,
  } = useYjsProvider({
    ...resolvedConfig!,
    autoConnect: true,
    onStatusChange: (newStatus) => {
      onStatusChange?.(newStatus);
    },
    onSync: (syncedDoc) => {
      if (!contentInitialized && initialContent) {
        const xmlFragment = syncedDoc.getXmlFragment('prosemirror');
        if (xmlFragment.length === 0) {
          markdownToYDoc(initialContent, syncedDoc);
        }
        setContentInitialized(true);
      }
      setIsEditorReady(true);
      onSync?.(syncedDoc);
    },
    onSave: (version, timestamp) => {
      onSave?.(version, timestamp);
    },
  });

  const isEditable = permissions?.canEdit !== false && !readOnly;

  const handleCursorChange = useCallback((pos: number, anchor: number, head: number) => {
    if (awareness && isEditable) {
      const cursorPosition: CursorPosition = { pos, anchor, head };
      setLocalAwareness({ cursor: cursorPosition });

      if (activityTimeoutRef.current) {
        clearTimeout(activityTimeoutRef.current);
      }
      activityTimeoutRef.current = setTimeout(() => {
        const currentState = awareness.getLocalState() as AwarenessState;
        if (currentState) {
          awareness.setLocalState({
            ...currentState,
            lastActive: Date.now(),
          });
        }
      }, 1000);
    }
  }, [awareness, isEditable, setLocalAwareness]);

  const handleEditorUpdate = useCallback(() => {
    if (!isLocalChange && onContentChange && editorRef.current) {
      const html = editorRef.current.getHTML();
      const markdown = yDocToMarkdown(doc);
      onContentChange(html, markdown);
    }
  }, [doc, isLocalChange, onContentChange]);

  const collaborationExtensions = [
    Collaboration.configure({
      document: doc,
    }),
    CollaborationCursor.configure({
      provider: provider as any,
      user: {
        name: resolvedConfig?.userName ?? '用户',
        color: userColor.primary,
        avatar: resolvedConfig?.userAvatar,
      },
      render: (user: any) => {
        return user.name;
      },
    }),
  ];

  useEffect(() => {
    if (editorState.error) {
      onError?.(editorState.error);
    }
  }, [editorState.error, onError]);

  useEffect(() => {
    return () => {
      if (activityTimeoutRef.current) {
        clearTimeout(activityTimeoutRef.current);
      }
    };
  }, []);

  const getStatusIcon = () => {
    switch (status) {
      case 'connected':
        return <Wifi size={16} className="text-green-500" />;
      case 'connecting':
      case 'reconnecting':
        return <RefreshCw size={16} className="text-blue-500 animate-spin" />;
      case 'disconnected':
        return <WifiOff size={16} className="text-gray-400" />;
      case 'error':
        return <AlertCircle size={16} className="text-red-500" />;
      default:
        return <WifiOff size={16} className="text-gray-400" />;
    }
  };

  const getStatusText = () => {
    switch (status) {
      case 'connected': return '已连接';
      case 'connecting': return '正在连接...';
      case 'reconnecting': return '正在重连...';
      case 'disconnected': return '已断开';
      case 'error': return '连接错误';
      case 'syncing': return '正在同步...';
      default: return status;
    }
  };

  const handleReconnect = () => {
    if (status === 'disconnected' || status === 'error') {
      connect();
    }
  };

  return (
    <div ref={containerRef} className={`relative ${className}`}>
      {showStatusBar && (
        <div className="flex items-center justify-between px-4 py-2 bg-gray-50 border-b border-gray-200 rounded-t-lg">
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2">
              {getStatusIcon()}
              <span className="text-sm text-gray-600">{getStatusText()}</span>
              {(status === 'disconnected' || status === 'error') && (
                <button
                  onClick={handleReconnect}
                  className="text-sm text-blue-600 hover:text-blue-700 underline"
                >
                  重新连接
                </button>
              )}
            </div>

            {editorState.isSynced && (
              <SaveStatusIndicator
                isConnected={editorState.isConnected}
                isSynced={editorState.isSynced}
                isSaving={editorState.isSaving}
                lastSaved={editorState.lastSaved}
                error={editorState.error}
              />
            )}
          </div>

          <div className="flex items-center gap-4">
            {onlineUsers.length > 0 && (
              <div className="flex items-center gap-1">
                {onlineUsers.slice(0, 3).map((user, index) => (
                  <UserActivityIndicator
                    key={user.id}
                    user={user}
                    isTyping={false}
                  />
                ))}
              </div>
            )}

            {showUserPresence && resolvedConfig && (
              <CursorPresence
                users={onlineUsers}
                currentUserId={resolvedConfig.userId}
                editorContainerRef={containerRef}
                awareness={awareness}
                showCursors={true}
              />
            )}

            {!isEditable && (
              <span className="px-2 py-1 text-xs bg-gray-200 text-gray-600 rounded">
                只读模式
              </span>
            )}
          </div>
        </div>
      )}

      {!editorState.isSynced && (
        <div className="absolute inset-0 z-10 flex items-center justify-center bg-white/80 backdrop-blur-sm">
          <div className="flex flex-col items-center gap-3">
            <RefreshCw size={32} className="text-blue-500 animate-spin" />
            <div className="text-gray-600">正在同步文档...</div>
          </div>
        </div>
      )}

      <TiptapEditor
        editorRef={editorRef}
        placeholder={placeholder}
        editable={isEditable && editorState.isSynced}
        showToolbar={showToolbar}
        extensions={collaborationExtensions}
        onUpdate={handleEditorUpdate}
        onCursorChange={handleCursorChange}
        className={showStatusBar ? 'rounded-t-none' : ''}
        contentClassName="min-h-[400px]"
      />

      {editorState.error && (
        <div className="absolute bottom-0 left-0 right-0 p-3 bg-red-50 border-t border-red-200 text-red-700 text-sm">
          <div className="flex items-center gap-2">
            <AlertCircle size={16} />
            <span>{editorState.error}</span>
            <button
              onClick={() => window.location.reload()}
              className="ml-auto text-red-600 hover:text-red-700 underline"
            >
              刷新页面
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

interface CollaborativeEditorStandaloneProps extends Omit<CollaborativeEditorProps, 'config'> {
  documentId: string;
  userId: string;
  userName: string;
  userAvatar?: string;
  token: string;
  roomName?: string;
}

export function CollaborativeEditorStandalone(props: CollaborativeEditorStandaloneProps) {
  const {
    documentId,
    userId,
    userName,
    userAvatar,
    token,
    roomName,
    ...rest
  } = props;

  const config: CollabConnectionConfig = {
    documentId,
    userId,
    userName,
    userAvatar,
    token,
    roomName,
  };

  return <CollaborativeEditor config={config} {...rest} />;
}

export { useYjsProvider } from './useYjsProvider';
export { TiptapEditor } from './TiptapEditor';
export { EditorToolbar } from './EditorToolbar';
export { CursorPresence, SaveStatusIndicator, UserActivityIndicator } from './CursorPresence';
