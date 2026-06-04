import React, { useEffect, useRef, useState, useCallback } from 'react';
import type { PluginManifest, ExtensionPointType } from '@shared/types';

interface PluginSandboxProps {
  pluginId: string;
  pluginUrl: string;
  extensionPoint?: ExtensionPointType;
  onMessage?: (message: any) => void;
  onReady?: () => void;
  onError?: (error: Error) => void;
  className?: string;
  style?: React.CSSProperties;
}

export const PluginSandbox: React.FC<PluginSandboxProps> = ({
  pluginId,
  pluginUrl,
  extensionPoint,
  onMessage,
  onReady,
  onError,
  className = '',
  style = {},
}) => {
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const [isReady, setIsReady] = useState(false);
  const [hasError, setHasError] = useState(false);
  const messageQueueRef = useRef<any[]>([]);

  const sendMessage = useCallback(
    (message: any) => {
      if (iframeRef.current?.contentWindow && isReady) {
        iframeRef.current.contentWindow.postMessage(
          {
            type: 'knowledgeforge-api',
            pluginId,
            extensionPoint,
            ...message,
          },
          '*'
        );
      } else {
        messageQueueRef.current.push(message);
      }
    },
    [pluginId, extensionPoint, isReady]
  );

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      if (!iframeRef.current) return;
      if (event.source !== iframeRef.current.contentWindow) return;

      const data = event.data;
      if (!data || data.type !== 'knowledgeforge-plugin') return;

      if (data.action === 'ready') {
        setIsReady(true);
        while (messageQueueRef.current.length > 0) {
          const message = messageQueueRef.current.shift();
          sendMessage(message);
        }
        onReady?.();
      } else if (data.action === 'error') {
        setHasError(true);
        onError?.(new Error(data.message || 'Plugin error'));
      } else if (onMessage) {
        onMessage(data);
      }
    };

    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, [pluginId, onMessage, onReady, onError, sendMessage]);

  useEffect(() => {
    return () => {
      if (iframeRef.current?.contentWindow) {
        iframeRef.current.contentWindow.postMessage(
          { type: 'knowledgeforge-api', action: 'unload' },
          '*'
        );
      }
    };
  }, []);

  const handleLoad = () => {
    setHasError(false);
  };

  if (hasError) {
    return (
      <div className={`p-4 bg-red-50 dark:bg-red-900/20 rounded-lg ${className}`}>
        <div className="text-red-500 flex items-center gap-2">
          <span>⚠️</span>
          <span>插件加载失败</span>
        </div>
      </div>
    );
  }

  return (
    <iframe
      ref={iframeRef}
      src={pluginUrl}
      sandbox="allow-scripts allow-same-origin allow-forms"
      className={`border-0 w-full h-full ${className}`}
      style={style}
      onLoad={handleLoad}
      title={`Plugin: ${pluginId}`}
    />
  );
};

export interface PluginAPI {
  getDocument: (docId: string) => Promise<any>;
  listDocuments: () => Promise<any[]>;
  search: (query: string) => Promise<any[]>;
  showToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
  openDocument: (docId: string) => void;
  getSettings: () => Promise<Record<string, any>>;
  setSettings: (settings: Record<string, any>) => Promise<void>;
  on: (event: string, handler: (...args: any[]) => void) => void;
  off: (event: string, handler: (...args: any[]) => void) => void;
  send: (message: any) => void;
}

export function usePluginSandbox(pluginId: string, pluginUrl: string) {
  const [isReady, setIsReady] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const messageHandlersRef = useRef<Map<string, Set<(...args: any[]) => void>>>(new Map());
  const iframeRef = useRef<HTMLIFrameElement | null>(null);

  const send = useCallback(
    (message: any) => {
      if (iframeRef.current?.contentWindow && isReady) {
        iframeRef.current.contentWindow.postMessage(
          { type: 'knowledgeforge-api', pluginId, ...message },
          '*'
        );
      }
    },
    [pluginId, isReady]
  );

  const api: PluginAPI = {
    getDocument: async (docId: string) => {
      return window.electron.ipc.invoke('document:get', docId);
    },
    listDocuments: async () => {
      return window.electron.ipc.invoke('document:list');
    },
    search: async (query: string) => {
      return window.electron.ipc.invoke('search:query', query);
    },
    showToast: (message: string, type: 'info' | 'success' | 'warning' | 'error' = 'info') => {
      send({ action: 'toast', message, type });
    },
    openDocument: (docId: string) => {
      send({ action: 'openDocument', docId });
    },
    getSettings: async () => {
      return window.electron.ipc.invoke('plugin:settings:get', pluginId);
    },
    setSettings: async (settings: Record<string, any>) => {
      return window.electron.ipc.invoke('plugin:settings:set', pluginId, settings);
    },
    on: (event: string, handler: (...args: any[]) => void) => {
      if (!messageHandlersRef.current.has(event)) {
        messageHandlersRef.current.set(event, new Set());
      }
      messageHandlersRef.current.get(event)!.add(handler);
    },
    off: (event: string, handler: (...args: any[]) => void) => {
      messageHandlersRef.current.get(event)?.delete(handler);
    },
    send,
  };

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      if (!iframeRef.current) return;
      if (event.source !== iframeRef.current.contentWindow) return;

      const data = event.data;
      if (!data || data.type !== 'knowledgeforge-plugin') return;

      if (data.action === 'ready') {
        setIsReady(true);
        setIsLoading(false);
      } else if (data.action === 'error') {
        setError(new Error(data.message || 'Plugin error'));
        setIsLoading(false);
      } else if (data.event) {
        const handlers = messageHandlersRef.current.get(data.event);
        handlers?.forEach((handler) => handler(...(data.args || [])));
      }
    };

    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, [pluginId]);

  const SandboxComponent: React.FC<{ className?: string; style?: React.CSSProperties }> = ({
    className,
    style,
  }) => (
    <PluginSandbox
      pluginId={pluginId}
      pluginUrl={pluginUrl}
      onReady={() => setIsReady(true)}
      onError={setError}
      className={className}
      style={style}
    />
  );

  return {
    Sandbox: SandboxComponent,
    api,
    isReady,
    isLoading,
    error,
    send,
  };
}

export function createPluginAPIBridge(pluginId: string): PluginAPI {
  const send = (message: any) => {
    window.parent.postMessage(
      { type: 'knowledgeforge-plugin', pluginId, ...message },
      '*'
    );
  };

  let requestId = 0;
  const pendingRequests = new Map<number, { resolve: Function; reject: Function }>();

  const handleMessage = (event: MessageEvent) => {
    const data = event.data;
    if (!data || data.type !== 'knowledgeforge-api') return;
    if (data.requestId !== undefined && pendingRequests.has(data.requestId)) {
      const { resolve, reject } = pendingRequests.get(data.requestId)!;
      if (data.error) {
        reject(new Error(data.error));
      } else {
        resolve(data.result);
      }
      pendingRequests.delete(data.requestId);
    }
  };

  window.addEventListener('message', handleMessage);

  const request = (method: string, ...args: any[]) => {
    return new Promise((resolve, reject) => {
      const id = requestId++;
      pendingRequests.set(id, { resolve, reject });
      send({ action: 'request', requestId: id, method, args });
    });
  };

  return {
    getDocument: (docId: string) => request('getDocument', docId) as Promise<any>,
    listDocuments: () => request('listDocuments') as Promise<any[]>,
    search: (query: string) => request('search', query) as Promise<any[]>,
    showToast: (message: string, type: 'info' | 'success' | 'warning' | 'error' = 'info') => {
      send({ action: 'toast', message, type });
    },
    openDocument: (docId: string) => {
      send({ action: 'openDocument', docId });
    },
    getSettings: () => request('getSettings') as Promise<Record<string, any>>,
    setSettings: (settings: Record<string, any>) =>
      request('setSettings', settings) as Promise<void>,
    on: (event: string, handler: (...args: any[]) => void) => {
      send({ action: 'on', event });
    },
    off: (event: string, handler: (...args: any[]) => void) => {
      send({ action: 'off', event });
    },
    send,
  };
}
