import React from 'react';
import { useBoardStore } from '../../stores/useBoardStore';
import type { User } from '../../types';

interface UserCursorProps {
  user: User;
}

const UserCursor: React.FC<UserCursorProps> = ({ user }) => {
  const viewport = useBoardStore((state) => state.viewport);

  if (!user.cursor) return null;

  const screenX = user.cursor.x * viewport.zoom + viewport.x;
  const screenY = user.cursor.y * viewport.zoom + viewport.y;

  const cursorStyle: React.CSSProperties = {
    position: 'absolute',
    left: screenX,
    top: screenY,
    pointerEvents: 'none',
    zIndex: 1000,
    transition: 'left 0.05s linear, top 0.05s linear',
  };

  return (
    <div style={cursorStyle}>
      <svg
        width="24"
        height="24"
        viewBox="0 0 24 24"
        fill={user.color}
        stroke="white"
        strokeWidth="1"
        style={{ filter: 'drop-shadow(0 1px 3px rgba(0,0,0,0.3)' }}
      >
        <path d="M5.5 3.21V20.8l4.5 17.36l-2.05 2.05z" fill={user.color} />
      </svg>
      <div
        style={{
          position: 'absolute',
          left: 18,
          top: 14,
          backgroundColor: user.color,
          color: 'white',
          padding: '2px 8px',
          borderRadius: 4,
          fontSize: 11,
          fontWeight: 500,
          whiteSpace: 'nowrap',
          boxShadow: '0 1px 3px rgba(0, 0, 0, 0.2)',
        }}
      >
        {user.name}
      </div>
    </div>
  );
};

export default UserCursor;
