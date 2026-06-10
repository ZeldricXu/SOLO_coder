import React, { useState } from 'react';
import type { User } from '../../types';

interface UserAvatarProps {
  user: User;
  size?: 'sm' | 'md' | 'lg';
  showStatus?: boolean;
}

const sizeConfig = {
  sm: { size: 28, fontSize: 12 },
  md: { size: 36, fontSize: 14 },
  lg: { size: 48, fontSize: 18 },
};

const UserAvatar: React.FC<UserAvatarProps> = ({ user, size = 'md', showStatus = true }) => {
  const [showTooltip, setShowTooltip] = useState(false);
  const config = sizeConfig[size];

  const getInitials = (name: string) => {
    return name
      .split(' ')
      .map((n) => n[0])
      .join('')
      .toUpperCase()
      .slice(0, 2);
  };

  const containerStyle: React.CSSProperties = {
    position: 'relative',
    display: 'inline-flex',
    cursor: 'pointer',
  };

  const avatarStyle: React.CSSProperties = {
    width: config.size,
    height: config.size,
    borderRadius: '50%',
    backgroundColor: user.color,
    color: '#ffffff',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: config.fontSize,
    fontWeight: 600,
    border: user.avatar ? 'none' : '2px solid #ffffff',
    boxShadow: '0 1px 3px rgba(0, 0, 0, 0.1)',
    backgroundImage: user.avatar ? `url(${user.avatar})` : 'none',
    backgroundSize: 'cover',
    backgroundPosition: 'center',
  };

  const statusStyle: React.CSSProperties = {
    position: 'absolute',
    bottom: 0,
    right: 0,
    width: config.size / 4,
    height: config.size / 4,
    borderRadius: '50%',
    backgroundColor: user.isOnline ? '#22c55e' : '#9ca3af',
    border: '2px solid #ffffff',
  };

  const tooltipStyle: React.CSSProperties = {
    position: 'absolute',
    bottom: config.size + 8,
    left: '50%',
    transform: 'translateX(-50%)',
    backgroundColor: '#1f2937',
    color: '#ffffff',
    padding: '6px 12px',
    borderRadius: 6,
    fontSize: 12,
    whiteSpace: 'nowrap',
    pointerEvents: 'none',
    zIndex: 100,
    boxShadow: '0 2px 8px rgba(0, 0, 0, 0.15)',
  };

  return (
    <div
      style={containerStyle}
      onMouseEnter={() => setShowTooltip(true)}
      onMouseLeave={() => setShowTooltip(false)}
    >
      <div style={avatarStyle}>{!user.avatar && getInitials(user.name)}</div>
      {showStatus && <div style={statusStyle} />}
      {showTooltip && (
        <div style={tooltipStyle}>
          <div style={{ fontWeight: 600 }}>{user.name}</div>
          <div style={{ opacity: 0.8, fontSize: 11, marginTop: 2 }}>
            {user.isOnline ? '在线' : '离线'}
          </div>
        </div>
      )}
    </div>
  );
};

export default UserAvatar;
