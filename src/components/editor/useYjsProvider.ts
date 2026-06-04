'use client';

import { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import * as Y from 'yjs';
import { WebsocketProvider } from 'y-websocket';
import { Awareness } from 'y-protocols/awareness';
import type { ConnectionStatus, CollabUser, EditorState, AwarenessState } from '@/lib/collab/types';
import { generateUserColor } from '@/lib/collab/utils';

interface UseYjsProviderOptions {
  documentId: string;
  userId: string;
  userName: string;
  userAvatar?: string;
  token: string;
  roomName?: string;
  autoConnect?: boolean;
  onStatusChange?: (status: ConnectionStatus) => void;
  onSync?: (doc: Y.Doc) => void;
  onSave?: (version: number, timestamp: Date) => void;
}

interface UseYjsProviderResult {
  doc: Y.Doc;
  provider: WebsocketProvider | null;
  awareness: Awareness | null;
  status: ConnectionStatus;
  editorState: EditorState;
  onlineUsers: CollabUser[];
  connect: () => void;
  disconnect: () => void;
  forceSync: () => void;
  setLocalAwareness: (state: Partial<AwarenessState>) => void;
}

export function useYjsProvider(options: UseYjsProviderOptions): UseYjsProviderResult {
  const {
    documentId,
    userId,
    userName,
    userAvatar,
    token,
    roomName,
    autoConnect = true,
    onStatusChange,
    onSync,
    onSave,
  } = options;

  const [status, setStatus] = useState<ConnectionStatus>('disconnected');
  const [editorState, setEditorState] = useState<EditorState>({
    isConnected: false,
    isSynced: false,
    isSaving: false,
    lastSaved: null,
    error: null,
  });
  const [onlineUsers, setOnlineUsers] = useState<CollabUser[]>([]);

  const docRef = useRef<Y.Doc | null>(null);
  const providerRef = useRef<WebsocketProvider | null>(null);
  const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const reconnectAttemptsRef = useRef(0);
  const maxReconnectAttempts = 10;
  const statusRef = useRef(status);
  const pendingUpdatesRef = useRef<Array<{ update: Uint8Array; origin: any }>>([]);
  const isProcessingRef = useRef(false);
  const awarenessRef = useRef<Awareness | null>(null);

  useEffect(() => {
    statusRef.current = status;
  }, [status]);

  const userColor = useMemo(() => generateUserColor(userId), [userId]);

  const currentUser: CollabUser = useMemo(() => ({
    id: userId,
    name: userName,
    avatar: userAvatar,
    color: userColor.primary,
  }), [userId, userName, userAvatar, userColor.primary]);

  const getWebsocketUrl = useCallback(() => {
    const protocol = typeof window !== 'undefined' && window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = typeof window !== 'undefined' ? window.location.host : 'localhost:3000';
    const baseUrl = `${protocol}//${host}/api/collab`;
    const params = new URLSearchParams({
      documentId,
      token,
    });
    if (roomName) {
      params.set('room', roomName);
    }
    return `${baseUrl}?${params.toString()}`;
  }, [documentId, token, roomName]);

  const updateOnlineUsers = useCallback((awareness: Awareness) => {
    const states = awareness.getStates();
    const users: CollabUser[] = [];
    states.forEach((state: any) => {
      if (state && state.user && state.user.id !== userId) {
        users.push(state.user);
      }
    });
    setOnlineUsers(users);
  }, [userId]);

  const processPendingUpdates = useCallback(() => {
    if (isProcessingRef.current || pendingUpdatesRef.current.length === 0 || !docRef.current) return;
    
    isProcessingRef.current = true;
    
    const { update, origin } = pendingUpdatesRef.current.shift()!;
    
    try {
      Y.applyUpdate(docRef.current, update, origin);
    } catch (e) {
      console.error('[useYjsProvider] Failed to apply update:', e);
    }
    
    requestAnimationFrame(() => {
      isProcessingRef.current = false;
      processPendingUpdates();
    });
  }, []);

  const createProvider = useCallback(() => {
    if (!docRef.current) {
      docRef.current = new Y.Doc();
    }

    const doc = docRef.current;
    const wsUrl = getWebsocketUrl();

    const originalOn = doc.on.bind(doc) as unknown as (eventName: string, handler: (...args: any[]) => void) => unknown;
    const customOn = (eventName: string, handler: (...args: any[]) => void) => {
      if (eventName === 'update') {
        const wrappedHandler = (update: Uint8Array, origin: any) => {
          const isLocal = origin === 'local' || origin === doc.clientID || 
                         (typeof origin === 'object' && origin?.isLocal === true);
          
          if (isLocal) {
            handler(update, origin);
          } else {
            pendingUpdatesRef.current.push({ update, origin });
            processPendingUpdates();
            
            if (awarenessRef.current) {
              const localState = awarenessRef.current.getLocalState() as AwarenessState;
              if (localState) {
                awarenessRef.current.setLocalState({
                  ...localState,
                  lastActive: Date.now(),
                });
              }
            }
            
            handler(update, origin);
          }
        };
        return originalOn(eventName, wrappedHandler);
      }
      return originalOn(eventName, handler);
    };
    (doc as any).on = customOn;

    const provider = new WebsocketProvider(
      '',
      documentId,
      doc,
      {
        connect: false,
        WebSocketPolyfill: typeof window !== 'undefined' ? window.WebSocket : undefined,
        params: {
          documentId,
          token,
        },
      }
    );

    provider.bcChannel = `${documentId}`;
    (provider as any)._wsUrl = wsUrl;

    const originalConnect = provider.connect.bind(provider);
    provider.connect = () => {
      (provider as any).ws = new (typeof window !== 'undefined' ? window.WebSocket : WebSocket)(wsUrl);
      originalConnect();
    };

    const awareness = provider.awareness;
    awarenessRef.current = awareness;

    provider.on('status', (event: { status: string }) => {
      const newStatus = event.status as ConnectionStatus;
      setStatus(newStatus);
      onStatusChange?.(newStatus);

      if (newStatus === 'connected') {
        reconnectAttemptsRef.current = 0;
        setEditorState(prev => ({
          ...prev,
          isConnected: true,
          error: null,
        }));

        const awarenessState: AwarenessState = {
          user: currentUser,
          lastActive: Date.now(),
        };
        awareness.setLocalState(awarenessState);
      } else if (newStatus === 'disconnected') {
        setEditorState(prev => ({
          ...prev,
          isConnected: false,
          isSynced: false,
        }));
      }
    });

    provider.on('sync', (isSynced: boolean) => {
      setEditorState(prev => ({
        ...prev,
        isSynced,
      }));
      if (isSynced && docRef.current) {
        onSync?.(docRef.current);
      }
    });

    provider.on('connection-close', () => {
      if (reconnectAttemptsRef.current < maxReconnectAttempts) {
        const delay = Math.min(1000 * Math.pow(2, reconnectAttemptsRef.current), 30000);
        reconnectAttemptsRef.current++;
        
        if (reconnectTimeoutRef.current) {
          clearTimeout(reconnectTimeoutRef.current);
        }
        
        reconnectTimeoutRef.current = setTimeout(() => {
          if (statusRef.current !== 'connected') {
            setStatus('reconnecting');
            provider.connect();
          }
        }, delay);
      } else {
        setEditorState(prev => ({
          ...prev,
          error: 'Failed to reconnect after multiple attempts',
        }));
      }
    });

    provider.on('connection-error', (error: Error) => {
      console.error('[useYjsProvider] Connection error:', error);
      setEditorState(prev => ({
        ...prev,
        error: error.message,
      }));
    });

    awareness.on('update', () => {
      updateOnlineUsers(awareness);
    });

    const messageListener = (event: MessageEvent) => {
      try {
        const message = JSON.parse(event.data);
        if (message.type === 'saved' && onSave) {
          setEditorState(prev => ({
            ...prev,
            isSaving: false,
            lastSaved: new Date(message.timestamp),
          }));
          onSave(message.version, new Date(message.timestamp));
        }
      } catch {
      }
    };

    (provider as any).ws?.addEventListener?.('message', messageListener);

    providerRef.current = provider;

    return { provider, awareness, doc };
  }, [documentId, token, getWebsocketUrl, currentUser, onStatusChange, onSync, onSave, updateOnlineUsers, processPendingUpdates]);

  const connect = useCallback(() => {
    if (!providerRef.current) {
      createProvider();
    }

    if (providerRef.current && (status === 'disconnected' || status === 'error')) {
      setStatus('connecting');
      providerRef.current?.connect();
    }
  }, [createProvider, status]);

  const disconnect = useCallback(() => {
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }

    if (providerRef.current) {
      providerRef.current.awareness.setLocalState(null);
      providerRef.current.disconnect();
    }

    setStatus('disconnected');
    setEditorState({
      isConnected: false,
      isSynced: false,
      isSaving: false,
      lastSaved: null,
      error: null,
    });
    setOnlineUsers([]);
  }, []);

  const forceSync = useCallback(() => {
    if (providerRef.current && status === 'connected') {
      try {
        (providerRef.current as any).ws?.send?.(
          JSON.stringify({ action: 'forceSync' })
        );
      } catch (error) {
        console.error('[useYjsProvider] Force sync error:', error);
      }
    }
  }, [status]);

  const setLocalAwareness = useCallback((state: Partial<AwarenessState>) => {
    if (providerRef.current) {
      const currentState = providerRef.current.awareness.getLocalState() as AwarenessState;
      providerRef.current.awareness.setLocalState({
        ...currentState,
        ...state,
        lastActive: Date.now(),
      });
    }
  }, []);

  useEffect(() => {
    if (autoConnect) {
      createProvider();
      connect();
    }

    return () => {
      disconnect();
      if (docRef.current) {
        docRef.current.destroy();
      }
      docRef.current = null;
      providerRef.current = null;
    };
  }, []);

  useEffect(() => {
    return () => {
      if (reconnectTimeoutRef.current) {
        clearTimeout(reconnectTimeoutRef.current);
      }
    };
  }, []);

  const doc = docRef.current || (() => {
    if (!docRef.current) {
      docRef.current = new Y.Doc();
    }
    return docRef.current;
  })();

  return {
    doc,
    provider: providerRef.current,
    awareness: providerRef.current?.awareness || null,
    status,
    editorState,
    onlineUsers,
    connect,
    disconnect,
    forceSync,
    setLocalAwareness,
  };
}
