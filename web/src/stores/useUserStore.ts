import { create } from 'zustand';
import type { User, Permission, PermissionRole } from '../types';

interface UserState {
  currentUser: User | null;
  remoteUsers: User[];
  permissions: Permission[];
}

interface UserActions {
  setCurrentUser: (user: User) => void;
  updateCurrentUser: (updates: Partial<User>) => void;
  addRemoteUser: (user: User) => void;
  updateRemoteUser: (id: string, updates: Partial<User>) => void;
  removeRemoteUser: (id: string) => void;
  setRemoteUsers: (users: User[]) => void;
  updateUserCursor: (id: string, x: number, y: number) => void;
  addPermission: (permission: Permission) => void;
  updatePermission: (userId: string, updates: Partial<Permission>) => void;
  removePermission: (userId: string) => void;
  hasPermission: (action: 'edit' | 'comment' | 'export' | 'share') => boolean;
  getUserRole: (userId: string) => PermissionRole | null;
}

const generateColor = (): string => {
  const colors = [
    '#ef4444', '#f97316', '#eab308', '#22c55e', '#14b8a6',
    '#06b6d4', '#3b82f6', '#6366f1', '#8b5cf6', '#a855f7',
    '#d946ef', '#ec4899', '#f43f5e',
  ];
  return colors[Math.floor(Math.random() * colors.length)];
};

const defaultUser: User = {
  id: `user-${Date.now()}`,
  name: '访客用户',
  color: generateColor(),
  isOnline: true,
  lastActive: Date.now(),
};

export const useUserStore = create<UserState & UserActions>((set, get) => ({
  currentUser: defaultUser,
  remoteUsers: [],
  permissions: [],

  setCurrentUser: (user) =>
    set({ currentUser: user }),

  updateCurrentUser: (updates) =>
    set((state) => ({
      currentUser: state.currentUser
        ? { ...state.currentUser, ...updates, lastActive: Date.now() }
        : null,
    })),

  addRemoteUser: (user) =>
    set((state) => ({
      remoteUsers: state.remoteUsers.some((u) => u.id === user.id)
        ? state.remoteUsers.map((u) => (u.id === user.id ? user : u))
        : [...state.remoteUsers, user],
    })),

  updateRemoteUser: (id, updates) =>
    set((state) => ({
      remoteUsers: state.remoteUsers.map((u) =>
        u.id === id ? { ...u, ...updates, lastActive: Date.now() } : u
      ),
    })),

  removeRemoteUser: (id) =>
    set((state) => ({
      remoteUsers: state.remoteUsers.filter((u) => u.id !== id),
    })),

  setRemoteUsers: (users) =>
    set({ remoteUsers: users }),

  updateUserCursor: (id, x, y) =>
    set((state) => {
      if (state.currentUser?.id === id) {
        return {
          currentUser: {
            ...state.currentUser,
            cursor: { x, y },
            lastActive: Date.now(),
          },
        };
      }
      return {
        remoteUsers: state.remoteUsers.map((u) =>
          u.id === id
            ? { ...u, cursor: { x, y }, lastActive: Date.now() }
            : u
        ),
      };
    }),

  addPermission: (permission) =>
    set((state) => ({
      permissions: state.permissions.some((p) => p.userId === permission.userId)
        ? state.permissions.map((p) =>
            p.userId === permission.userId ? permission : p
          )
        : [...state.permissions, permission],
    })),

  updatePermission: (userId, updates) =>
    set((state) => ({
      permissions: state.permissions.map((p) =>
        p.userId === userId ? { ...p, ...updates } : p
      ),
    })),

  removePermission: (userId) =>
    set((state) => ({
      permissions: state.permissions.filter((p) => p.userId !== userId),
    })),

  hasPermission: (action) => {
    const { currentUser, permissions } = get();
    if (!currentUser) return false;

    const permission = permissions.find((p) => p.userId === currentUser.id);
    
    switch (action) {
      case 'edit':
        return permission?.canEdit ?? false;
      case 'comment':
        return permission?.canComment ?? false;
      case 'export':
        return permission?.canExport ?? false;
      case 'share':
        return permission?.canShare ?? false;
      default:
        return false;
    }
  },

  getUserRole: (userId) => {
    const { permissions } = get();
    const permission = permissions.find((p) => p.userId === userId);
    return permission?.role ?? null;
  },
}));
