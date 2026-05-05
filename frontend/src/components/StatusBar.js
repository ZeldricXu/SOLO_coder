import React, { useEffect, useState } from 'react';
import useStore from '../store';

const styles = {
  statusBar: {
    display: 'flex',
    alignItems: 'center',
    height: '32px',
    backgroundColor: '#0d1b2a',
    borderTop: '1px solid #0f3460',
    padding: '0 16px',
    fontSize: '12px',
    color: '#8892b0'
  },
  leftSection: {
    display: 'flex',
    alignItems: 'center',
    gap: '16px'
  },
  rightSection: {
    display: 'flex',
    alignItems: 'center',
    gap: '16px',
    marginLeft: 'auto'
  },
  statusItem: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px'
  },
  statusDot: {
    width: '8px',
    height: '8px',
    borderRadius: '50%',
    backgroundColor: '#666'
  },
  statusDotConnected: {
    backgroundColor: '#28a745'
  },
  statusDotDisconnected: {
    backgroundColor: '#dc3545'
  },
  statusDotConnecting: {
    backgroundColor: '#ffc107',
    animation: 'pulse 1.5s infinite'
  },
  separator: {
    width: '1px',
    height: '16px',
    backgroundColor: '#0f3460'
  },
  userBadge: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    padding: '4px 10px',
    backgroundColor: '#16213e',
    borderRadius: '12px'
  },
  userAvatar: {
    width: '16px',
    height: '16px',
    borderRadius: '50%',
    backgroundColor: '#e94560',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: '10px',
    color: '#fff'
  },
  userList: {
    display: 'flex',
    gap: '8px'
  },
  coordinate: {
    fontFamily: 'monospace',
    fontSize: '11px',
    color: '#666'
  },
  fpsCounter: {
    fontFamily: 'monospace',
    fontSize: '11px',
    color: '#8892b0'
  }
};

function StatusBar() {
  const { 
    connectionStatus, 
    userName, 
    userId, 
    users, 
    sceneId,
    currentVersion,
    objects
  } = useStore();
  
  const [fps, setFps] = useState(60);
  const [lastTime, setLastTime] = useState(performance.now());
  const [frameCount, setFrameCount] = useState(0);

  useEffect(() => {
    const animate = () => {
      const currentTime = performance.now();
      setFrameCount(prev => prev + 1);
      
      if (currentTime - lastTime >= 1000) {
        setFps(frameCount);
        setFrameCount(0);
        setLastTime(currentTime);
      }
      
      requestAnimationFrame(animate);
    };
    
    const animationId = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(animationId);
  }, [lastTime, frameCount]);

  const getConnectionStatusText = () => {
    switch (connectionStatus) {
      case 'connected':
        return '已连接';
      case 'connecting':
      case 'joining':
        return '连接中...';
      case 'disconnected':
        return '未连接';
      case 'error':
        return '连接错误';
      default:
        return '未知';
    }
  };

  const getConnectionDotStyle = () => {
    switch (connectionStatus) {
      case 'connected':
        return styles.statusDotConnected;
      case 'connecting':
      case 'joining':
        return styles.statusDotConnecting;
      case 'disconnected':
      case 'error':
        return styles.statusDotDisconnected;
      default:
        return styles.statusDot;
    }
  };

  const getUserInitial = (name) => {
    return name ? name.charAt(0).toUpperCase() : '?';
  };

  const getColorForUser = (userId) => {
    const colors = [
      '#e94560', '#28a745', '#007bff', '#ffc107',
      '#17a2b8', '#6f42c1', '#fd7e14', '#20c997'
    ];
    const hash = userId.split('').reduce((acc, char) => {
      return char.charCodeAt(0) + ((acc << 5) - acc);
    }, 0);
    return colors[Math.abs(hash) % colors.length];
  };

  return (
    <div style={styles.statusBar}>
      <div style={styles.leftSection}>
        <div style={styles.statusItem}>
          <div style={{ ...styles.statusDot, ...getConnectionDotStyle() }} />
          <span>{getConnectionStatusText()}</span>
        </div>
        
        <div style={styles.separator} />
        
        {sceneId && (
          <>
            <div style={styles.statusItem}>
              <span>场景:</span>
              <span style={{ fontFamily: 'monospace', color: '#e94560' }}>
                {sceneId.slice(0, 12)}...
              </span>
            </div>
            
            <div style={styles.separator} />
            
            <div style={styles.statusItem}>
              <span>版本:</span>
              <span style={{ fontFamily: 'monospace' }}>v{currentVersion}</span>
            </div>
            
            <div style={styles.separator} />
            
            <div style={styles.statusItem}>
              <span>对象:</span>
              <span>{objects.size}</span>
            </div>
          </>
        )}
      </div>
      
      <div style={styles.rightSection}>
        {users.length > 0 && (
          <>
            <div style={styles.statusItem}>
              <span>在线用户:</span>
            </div>
            
            <div style={styles.userList}>
              {users.map((user, index) => (
                <div
                  key={user.user_id || index}
                  style={styles.userBadge}
                  title={user.user_name}
                >
                  <div style={{
                    ...styles.userAvatar,
                    backgroundColor: getColorForUser(user.user_id)
                  }}>
                    {getUserInitial(user.user_name)}
                  </div>
                  <span>{user.user_name}</span>
                </div>
              ))}
            </div>
            
            <div style={styles.separator} />
          </>
        )}
        
        <div style={styles.fpsCounter}>
          {fps} FPS
        </div>
        
        <div style={styles.separator} />
        
        <div style={styles.statusItem}>
          <div style={{
            ...styles.userAvatar,
            backgroundColor: '#e94560'
          }}>
            {getUserInitial(userName)}
          </div>
          <span>{userName}</span>
        </div>
      </div>
      
      <style>{`
        @keyframes pulse {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.5; }
        }
      `}</style>
    </div>
  );
}

export default StatusBar;
