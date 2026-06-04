'use client';

import * as React from 'react';
import { useState, useEffect, useRef } from 'react';
import type { CollabUser, AwarenessUser } from '@/lib/collab/types';
import { Users, Circle } from 'lucide-react';

interface CursorPresenceProps {
  users: CollabUser[];
  currentUserId?: string;
  maxVisibleAvatars?: number;
  showCursors?: boolean;
  editorContainerRef?: React.RefObject<HTMLDivElement>;
  awareness?: any;
}

interface CursorPosition {
  x: number;
  y: number;
  anchor: number;
  head: number;
}

interface CursorInfo {
  user: CollabUser;
  position: CursorPosition | null;
  element: HTMLDivElement | null;
  lastUpdate: number;
}

export function CursorPresence({
  users,
  currentUserId,
  maxVisibleAvatars = 5,
  showCursors = true,
  editorContainerRef,
  awareness,
}: CursorPresenceProps) {
  const [hoveredUser, setHoveredUser] = useState<string | null>(null);
  const [cursors, setCursors] = useState<Map<string, CursorInfo>>(new Map());
  const cursorsRef = useRef<Map<string, HTMLDivElement>>(new Map());

  useEffect(() => {
    if (!awareness || !showCursors) return;

    const handleAwarenessUpdate = () => {
      const states = awareness.getStates();
      const newCursors = new Map<string, CursorInfo>();

      states.forEach((state: any, clientId: number) => {
        if (state && state.user && state.user.id !== currentUserId) {
          const cursorInfo: CursorInfo = {
            user: state.user,
            position: state.cursor || null,
            element: cursorsRef.current.get(clientId.toString()) || null,
            lastUpdate: state.lastActive || Date.now(),
          };
          newCursors.set(clientId.toString(), cursorInfo);
        }
      });

      setCursors(newCursors);
    };

    awareness.on('update', handleAwarenessUpdate);
    handleAwarenessUpdate();

    return () => {
      awareness.off('update', handleAwarenessUpdate);
    };
  }, [awareness, currentUserId, showCursors]);

  useEffect(() => {
    if (!editorContainerRef?.current || !showCursors) return;

    const container = editorContainerRef.current;

    cursors.forEach((cursorInfo, clientId) => {
      let cursorEl = cursorsRef.current.get(clientId);

      if (!cursorEl) {
        cursorEl = document.createElement('div');
        cursorEl.className = 'absolute pointer-events-none z-50 transition-all duration-75';
        cursorEl.style.opacity = '0';
        container.appendChild(cursorEl);
        cursorsRef.current.set(clientId, cursorEl);
      }

      if (cursorInfo.position) {
        const { x, y } = cursorInfo.position;
        cursorEl.style.transform = `translate(${x}px, ${y}px)`;
        cursorEl.style.opacity = '1';

        if (!cursorEl.querySelector('.cursor-content')) {
          const color = cursorInfo.user.color;
          cursorEl.innerHTML = `
            <div class="cursor-content" style="position: relative;">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" style="filter: drop-shadow(0 2px 4px rgba(0,0,0,0.2));">
                <path d="M5.5 3.5L9 19.5L13 14L16 17L19 16L5.5 3.5Z" fill="${color}" stroke="white" stroke-width="1.5"/>
              </svg>
              <div class="absolute top-5 left-3 px-2 py-0.5 rounded text-xs text-white whitespace-nowrap" style="background-color: ${color};">
                ${cursorInfo.user.name}
              </div>
            </div>
          `;
        }
      } else {
        cursorEl.style.opacity = '0';
      }
    });

    cursorsRef.current.forEach((cursorEl, clientId) => {
      if (!cursors.has(clientId)) {
        cursorEl.remove();
        cursorsRef.current.delete(clientId);
      }
    });

    return () => {
      cursorsRef.current.forEach((cursorEl) => {
        cursorEl.remove();
      });
      cursorsRef.current.clear();
    };
  }, [cursors, editorContainerRef, showCursors]);

  const otherUsers = users.filter(user => user.id !== currentUserId);
  const visibleUsers = otherUsers.slice(0, maxVisibleAvatars);
  const hiddenCount = Math.max(0, otherUsers.length - maxVisibleAvatars);

  const getInitials = (name: string) => {
    return name
      .split(' ')
      .map(word => word[0])
      .join('')
      .toUpperCase()
      .slice(0, 2);
  };

  return (
    <div className="flex items-center gap-2">
      <div className="flex items-center gap-1 text-sm text-gray-500 mr-2">
        <Users size={16} />
        <span>{otherUsers.length + (currentUserId ? 1 : 0)} 在线</span>
      </div>

      <div className="flex -space-x-2">
        {currentUserId && (
          <div
            className="relative w-8 h-8 rounded-full border-2 border-white bg-blue-500 flex items-center justify-center text-white text-xs font-medium"
            title="你"
          >
            我
          </div>
        )}

        {visibleUsers.map((user) => (
          <div
            key={user.id}
            className="relative group"
            onMouseEnter={() => setHoveredUser(user.id)}
            onMouseLeave={() => setHoveredUser(null)}
          >
            {user.avatar ? (
              <img
                src={user.avatar}
                alt={user.name}
                className="w-8 h-8 rounded-full border-2 border-white object-cover"
              />
            ) : (
              <div
                className="w-8 h-8 rounded-full border-2 border-white flex items-center justify-center text-white text-xs font-medium"
                style={{ backgroundColor: user.color }}
              >
                {getInitials(user.name)}
              </div>
            )}
            <div
              className="absolute -bottom-0.5 -right-0.5 w-3 h-3 rounded-full border-2 border-white bg-green-500"
            />

            {hoveredUser === user.id && (
              <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 px-3 py-1.5 bg-gray-900 text-white text-xs rounded-lg whitespace-nowrap z-50 shadow-xl">
                <div className="font-medium">{user.name}</div>
                <div className="flex items-center gap-1 mt-0.5 opacity-80">
                  <Circle size={8} fill={user.color} color={user.color} />
                  <span>正在编辑</span>
                </div>
                <div className="absolute top-full left-1/2 -translate-x-1/2 -mt-1 border-4 border-transparent border-t-gray-900" />
              </div>
            )}
          </div>
        ))}

        {hiddenCount > 0 && (
          <div
            className="w-8 h-8 rounded-full border-2 border-white bg-gray-300 flex items-center justify-center text-gray-700 text-xs font-medium"
            title={`还有 ${hiddenCount} 人`}
          >
            +{hiddenCount}
          </div>
        )}
      </div>
    </div>
  );
}

interface UserActivityIndicatorProps {
  user: CollabUser;
  isTyping?: boolean;
  lastActive?: number;
}

export function UserActivityIndicator({ user, isTyping, lastActive }: UserActivityIndicatorProps) {
  const [show, setShow] = useState(false);

  useEffect(() => {
    if (isTyping) {
      setShow(true);
      const timer = setTimeout(() => setShow(false), 3000);
      return () => clearTimeout(timer);
    }
  }, [isTyping]);

  if (!show) return null;

  return (
    <div className="flex items-center gap-2 px-3 py-1.5 bg-gray-100 rounded-full text-sm text-gray-600">
      <div className="flex gap-0.5">
        <span className="w-1.5 h-1.5 rounded-full bg-gray-400 animate-bounce" style={{ animationDelay: '0ms' }} />
        <span className="w-1.5 h-1.5 rounded-full bg-gray-400 animate-bounce" style={{ animationDelay: '150ms' }} />
        <span className="w-1.5 h-1.5 rounded-full bg-gray-400 animate-bounce" style={{ animationDelay: '300ms' }} />
      </div>
      <span style={{ color: user.color }}>{user.name}</span>
      <span>正在输入</span>
    </div>
  );
}

interface SaveStatusIndicatorProps {
  isConnected: boolean;
  isSynced: boolean;
  isSaving: boolean;
  lastSaved: Date | null;
  error: string | null;
}

export function SaveStatusIndicator({
  isConnected,
  isSynced,
  isSaving,
  lastSaved,
  error,
}: SaveStatusIndicatorProps) {
  const formatTime = (date: Date) => {
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    const minutes = Math.floor(diff / 60000);
    
    if (minutes < 1) return '刚刚';
    if (minutes < 60) return `${minutes} 分钟前`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} 小时前`;
    return date.toLocaleDateString();
  };

  const getStatus = () => {
    if (error) {
      return { icon: '❌', text: error, color: 'text-red-500' };
    }
    if (!isConnected) {
      return { icon: '⚠️', text: '已断开连接', color: 'text-yellow-600' };
    }
    if (!isSynced) {
      return { icon: '🔄', text: '正在同步...', color: 'text-blue-500' };
    }
    if (isSaving) {
      return { icon: '💾', text: '正在保存...', color: 'text-blue-500' };
    }
    if (lastSaved) {
      return { icon: '✓', text: `已保存于 ${formatTime(lastSaved)}`, color: 'text-green-600' };
    }
    return { icon: '✓', text: '已连接', color: 'text-green-600' };
  };

  const status = getStatus();

  return (
    <div className={`flex items-center gap-1.5 text-sm ${status.color}`}>
      <span>{status.icon}</span>
      <span>{status.text}</span>
    </div>
  );
}
