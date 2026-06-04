export interface CollabUser {
  id: string;
  name: string;
  avatar?: string;
  color: string;
}

export interface CursorPosition {
  pos: number;
  anchor: number;
  head: number;
}

export interface AwarenessState {
  user: CollabUser;
  cursor?: CursorPosition;
  selection?: {
    from: number;
    to: number;
  };
  lastActive: number;
}

export interface EditorState {
  isConnected: boolean;
  isSynced: boolean;
  isSaving: boolean;
  lastSaved: Date | null;
  error: string | null;
}

export interface AwarenessUser extends CollabUser {
  cursor?: CursorPosition;
  lastActive: number;
}

export interface DocumentPermissions {
  canView: boolean;
  canEdit: boolean;
  canComment: boolean;
  isOwner: boolean;
}

export interface CollabConnectionConfig {
  documentId: string;
  userId: string;
  userName: string;
  userAvatar?: string;
  token: string;
  roomName?: string;
}

export type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'reconnecting' | 'error' | 'syncing';

export interface CollabRoom {
  documentId: string;
  name: string;
  users: Map<string, AwarenessUser>;
  createdAt: Date;
  lastActivity: Date;
}

export interface SaveOptions {
  debounceMs?: number;
  force?: boolean;
  version?: number;
}

export interface YDocumentState {
  state: Uint8Array;
  version: number;
  updatedAt: Date;
  updatedBy: string;
}

export interface MarkdownConvertOptions {
  ignoreEmpty?: boolean;
  preserveWhitespace?: boolean;
}

export interface UserColor {
  primary: string;
  light: string;
  dark: string;
}

export interface RoomInfo {
  documentId: string;
  name: string;
  createdAt: string;
  lastActivity: string;
  users: CollabUser[];
  userCount: number;
  isActive: boolean;
}
