import { useState, useEffect, useCallback, useRef } from 'react';
import { useCollabStore } from '../stores/useCollabStore';
import { useUserStore } from '../stores/useUserStore';
import { useBoardStore } from '../stores/useBoardStore';
import { CollabClient } from '../utils/collab';
import type { User, CRDTOperation, Point } from '../types';

interface UseCollaborationResult {
  isConnected: boolean;
  isConnecting: boolean;
  users: User[];
  error: Error | null;
  connect: (roomId: string, userId?: string) => Promise<void>;
  disconnect: () => void;
  sendOperation: (operation: CRDTOperation) => void;
  sendCursor: (position: Point) => void;
  sendMessage: (message: Record<string, unknown>) => void;
}

export function useCollaboration(
  serverUrl: string = 'ws://localhost:8080'
): UseCollaborationResult {
  const [isConnecting, setIsConnecting] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const clientRef = useRef<CollabClient | null>(null);

  const isConnected = useCollabStore((state) => state.isConnected);
  const { connect: storeConnect, disconnect: storeDisconnect, addOperation } = useCollabStore();
  const { currentUser, addRemoteUser, removeRemoteUser, setRemoteUsers, updateUserCursor } = useUserStore();
  const { addStroke, updateStroke, removeStroke, addShape, updateShape, removeShape } = useBoardStore();

  const handleOperation = useCallback((operation: CRDTOperation) => {
    addOperation(operation);

    switch (operation.type) {
      case 'insert':
        if (operation.objectType === 'stroke') {
          addStroke(operation.payload as unknown as Parameters<typeof addStroke>[0]);
        } else if (operation.objectType === 'shape') {
          addShape(operation.payload as unknown as Parameters<typeof addShape>[0]);
        }
        break;
      case 'update':
        if (operation.objectType === 'stroke') {
          const id = operation.objectId;
          updateStroke(id, operation.payload as Partial<Parameters<typeof updateStroke>[1]>);
        } else if (operation.objectType === 'shape') {
          const id = operation.objectId;
          updateShape(id, operation.payload as Partial<Parameters<typeof updateShape>[1]>);
        }
        break;
      case 'delete':
        if (operation.objectType === 'stroke') {
          removeStroke(operation.objectId);
        } else if (operation.objectType === 'shape') {
          removeShape(operation.objectId);
        }
        break;
    }
  }, [addOperation, addStroke, updateStroke, removeStroke, addShape, updateShape, removeShape]);

  const handleUserJoin = useCallback((user: User) => {
    if (user.id !== currentUser?.id) {
      addRemoteUser(user);
    }
  }, [currentUser?.id, addRemoteUser]);

  const handleUserLeave = useCallback((userId: string) => {
    removeRemoteUser(userId);
  }, [removeRemoteUser]);

  const handleUsersList = useCallback((users: User[]) => {
    const filtered = users.filter((u) => u.id !== currentUser?.id);
    setRemoteUsers(filtered);
  }, [currentUser?.id, setRemoteUsers]);

  const handleCursorUpdate = useCallback((userId: string, position: Point) => {
    updateUserCursor(userId, position.x, position.y);
  }, [updateUserCursor]);

  const connect = useCallback(async (roomId: string, userId?: string) => {
    if (!currentUser) return;

    setIsConnecting(true);
    setError(null);

    try {
      const client = new CollabClient(serverUrl, {
        roomId,
        userId: userId ?? currentUser.id,
        userName: currentUser.name,
        userColor: currentUser.color,
        onOperation: handleOperation,
        onUserJoin: handleUserJoin,
        onUserLeave: handleUserLeave,
        onUsersList: handleUsersList,
        onCursorUpdate: handleCursorUpdate,
        onError: (err) => setError(err),
        onConnect: () => {
          storeConnect(roomId);
          setIsConnecting(false);
        },
        onDisconnect: () => {
          storeDisconnect();
          setIsConnecting(false);
        },
      });

      clientRef.current = client;
      await client.connect();
    } catch (err) {
      setError(err instanceof Error ? err : new Error('Failed to connect'));
      setIsConnecting(false);
    }
  }, [currentUser, serverUrl, handleOperation, handleUserJoin, handleUserLeave, handleUsersList, handleCursorUpdate, storeConnect, storeDisconnect]);

  const disconnect = useCallback(() => {
    if (clientRef.current) {
      clientRef.current.disconnect();
      clientRef.current = null;
    }
  }, []);

  const sendOperation = useCallback((operation: CRDTOperation) => {
    if (clientRef.current && isConnected) {
      clientRef.current.sendOperation(operation);
    }
  }, [isConnected]);

  const sendCursor = useCallback((position: Point) => {
    if (clientRef.current && isConnected) {
      clientRef.current.sendCursor(position);
    }
  }, [isConnected]);

  const sendMessage = useCallback((message: Record<string, unknown>) => {
    if (clientRef.current && isConnected) {
      clientRef.current.sendMessage(message);
    }
  }, [isConnected]);

  useEffect(() => {
    return () => {
      disconnect();
    };
  }, [disconnect]);

  const users = useUserStore((state) => {
    const result: User[] = [];
    if (state.currentUser) result.push(state.currentUser);
    return [...result, ...state.remoteUsers];
  });

  return {
    isConnected,
    isConnecting,
    users,
    error,
    connect,
    disconnect,
    sendOperation,
    sendCursor,
    sendMessage,
  };
}
