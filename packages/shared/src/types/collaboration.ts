import { Vec3 } from './vectors';

export interface Collaborator {
  id: string;
  name: string;
  color: string;
  cursor: {
    position: Vec3;
    screenPosition: { x: number; y: number };
  };
  selectedObjectId?: string;
  isOnline: boolean;
  lastActive: number;
}

export interface CollaborationSession {
  id: string;
  sceneId: string;
  collaborators: Map<string, Collaborator>;
  isActive: boolean;
  createdAt: number;
  hostId: string;
}

export interface CollabMessage {
  type: 'cursor' | 'select' | 'scene-update' | 'chat' | 'join' | 'leave';
  senderId: string;
  timestamp: number;
  data: unknown;
}

export interface CursorMessage extends CollabMessage {
  type: 'cursor';
  data: {
    position: Vec3;
    screenPosition: { x: number; y: number };
  };
}

export interface SelectMessage extends CollabMessage {
  type: 'select';
  data: {
    objectId?: string;
  };
}

export interface ChatMessage extends CollabMessage {
  type: 'chat';
  data: {
    message: string;
  };
}
