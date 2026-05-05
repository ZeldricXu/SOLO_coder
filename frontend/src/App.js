import React, { useEffect, useRef, useState } from 'react';
import useStore from './store';
import syncClientV2 from './services/sync/SyncClientV2';
import assetService from './services/assetService';
import sceneManager from './services/renderer/SceneManager';
import Toolbar from './components/Toolbar';
import AssetPanel from './components/AssetPanel';
import PropertiesPanel from './components/PropertiesPanel';
import StatusBar from './components/StatusBar';
import ScenePanel from './components/ScenePanel';

const styles = {
  app: {
    display: 'flex',
    flexDirection: 'column',
    width: '100%',
    height: '100%',
    backgroundColor: '#1a1a2e',
    color: '#e0e0e0',
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif'
  },
  mainContent: {
    display: 'flex',
    flex: 1,
    overflow: 'hidden'
  },
  leftPanel: {
    width: '240px',
    backgroundColor: '#16213e',
    borderRight: '1px solid #0f3460',
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden'
  },
  canvasContainer: {
    flex: 1,
    position: 'relative',
    overflow: 'hidden'
  },
  rightPanel: {
    width: '280px',
    backgroundColor: '#16213e',
    borderLeft: '1px solid #0f3460',
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden'
  },
  canvasPlaceholder: {
    width: '100%',
    height: '100%',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#0d1b2a'
  },
  placeholderText: {
    color: '#666',
    fontSize: '14px'
  },
  modalOverlay: {
    position: 'fixed',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.7)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 1000
  },
  modal: {
    backgroundColor: '#16213e',
    borderRadius: '8px',
    padding: '24px',
    minWidth: '400px',
    border: '1px solid #0f3460'
  },
  modalTitle: {
    fontSize: '18px',
    fontWeight: 600,
    marginBottom: '20px',
    color: '#fff'
  },
  input: {
    width: '100%',
    padding: '10px 12px',
    backgroundColor: '#0d1b2a',
    border: '1px solid #0f3460',
    borderRadius: '4px',
    color: '#e0e0e0',
    fontSize: '14px',
    marginBottom: '16px'
  },
  buttonGroup: {
    display: 'flex',
    gap: '12px',
    justifyContent: 'flex-end'
  },
  button: {
    padding: '10px 20px',
    borderRadius: '4px',
    border: 'none',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: 500
  },
  primaryButton: {
    backgroundColor: '#e94560',
    color: '#fff'
  },
  secondaryButton: {
    backgroundColor: '#0f3460',
    color: '#e0e0e0'
  }
};

function App() {
  const canvasRef = useRef(null);
  const [showStartModal, setShowStartModal] = useState(true);
  const [sceneName, setSceneName] = useState('我的场景');
  const [userName, setUserName] = useState('');
  const [isConnecting, setIsConnecting] = useState(false);
  const { userId, setUserName: setStoreUserName, setSceneId, resetScene } = useStore();

  useEffect(() => {
    const storedName = localStorage.getItem('sceneforge_username');
    if (storedName) {
      setUserName(storedName);
    }
  }, []);

  useEffect(() => {
    if (canvasRef.current && !showStartModal) {
      sceneManager.initialize(canvasRef.current);
      
      const unsubscribe = useStore.subscribe(
        (state) => state.objects,
        (objects, previousObjects) => {
          const prevMap = previousObjects || new Map();
          
          objects.forEach((obj, id) => {
            if (!prevMap.has(id)) {
              sceneManager.createMeshForObject(obj);
            } else {
              const prevObj = prevMap.get(id);
              if (JSON.stringify(obj.transform) !== JSON.stringify(prevObj.transform)) {
                sceneManager.updateMeshTransform(id, obj.transform);
              }
            }
          });
          
          prevMap.forEach((_, id) => {
            if (!objects.has(id)) {
              sceneManager.removeMesh(id);
            }
          });
        }
      );
      
      return () => {
        unsubscribe();
        sceneManager.destroy();
      };
    }
  }, [showStartModal]);

  const handleConnect = async () => {
    if (!userName.trim()) {
      alert('请输入用户名');
      return;
    }
    
    setIsConnecting(true);
    setStoreUserName(userName.trim());
    localStorage.setItem('sceneforge_username', userName.trim());
    
    try {
      await syncClientV2.connect('http://localhost:8080');
      
      const sceneId = `scene_${Date.now()}`;
      setSceneId(sceneId);
      
      syncClientV2.joinScene(sceneId);
      
      setShowStartModal(false);
    } catch (error) {
      console.error('Failed to connect:', error);
      alert('连接服务器失败，请确保后端服务已启动');
    } finally {
      setIsConnecting(false);
    }
  };

  const handleDisconnect = () => {
    syncClientV2.disconnect();
    resetScene();
    setShowStartModal(true);
  };

  return (
    <div style={styles.app}>
      <Toolbar onDisconnect={handleDisconnect} />
      
      <div style={styles.mainContent}>
        <div style={styles.leftPanel}>
          <ScenePanel />
          <AssetPanel />
        </div>
        
        <div style={styles.canvasContainer}>
          {showStartModal ? (
            <div style={styles.canvasPlaceholder}>
              <div style={styles.modalOverlay}>
                <div style={styles.modal}>
                  <h2 style={styles.modalTitle}>SceneForge - 3D场景建模平台</h2>
                  
                  <div>
                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px' }}>
                      用户名
                    </label>
                    <input
                      type="text"
                      style={styles.input}
                      value={userName}
                      onChange={(e) => setUserName(e.target.value)}
                      placeholder="请输入您的用户名"
                    />
                  </div>
                  
                  <div>
                    <label style={{ display: 'block', marginBottom: '8px', fontSize: '14px' }}>
                      场景名称
                    </label>
                    <input
                      type="text"
                      style={styles.input}
                      value={sceneName}
                      onChange={(e) => setSceneName(e.target.value)}
                      placeholder="请输入场景名称"
                    />
                  </div>
                  
                  <div style={{ marginBottom: '20px', fontSize: '12px', color: '#666' }}>
                    用户ID: {userId}
                  </div>
                  
                  <div style={styles.buttonGroup}>
                    <button
                      style={{ ...styles.button, ...styles.primaryButton }}
                      onClick={handleConnect}
                      disabled={isConnecting}
                    >
                      {isConnecting ? '连接中...' : '开始创建'}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          ) : (
            <div ref={canvasRef} style={{ width: '100%', height: '100%' }} />
          )}
        </div>
        
        <div style={styles.rightPanel}>
          <PropertiesPanel />
        </div>
      </div>
      
      <StatusBar />
    </div>
  );
}

export default App;
