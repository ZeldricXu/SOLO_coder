import { io } from 'socket.io-client';
import useStore from '../store';
import { v4 as uuidv4 } from 'uuid';

class SyncClient {
  constructor() {
    this.socket = null;
    this.sceneId = null;
    this.userId = null;
    this.connected = false;
    this.operationQueue = [];
    this.isProcessingQueue = false;
    this.operationCallbacks = new Map();
  }
  
  connect(serverUrl = 'http://localhost:8080') {
    return new Promise((resolve, reject) => {
      if (this.socket && this.socket.connected) {
        resolve(this.socket);
        return;
      }
      
      this.socket = io(serverUrl, {
        transports: ['websocket', 'polling'],
        autoConnect: true,
        reconnection: true,
        reconnectionAttempts: 5,
        reconnectionDelay: 1000
      });
      
      this.socket.on('connect', () => {
        this.connected = true;
        useStore.getState().setConnectionStatus('connected');
        console.log('WebSocket connected');
        resolve(this.socket);
      });
      
      this.socket.on('disconnect', (reason) => {
        this.connected = false;
        useStore.getState().setConnectionStatus('disconnected');
        console.log('WebSocket disconnected:', reason);
      });
      
      this.socket.on('connect_error', (error) => {
        console.error('WebSocket connection error:', error);
        useStore.getState().setConnectionStatus('error');
        reject(error);
      });
      
      this.setupEventListeners();
    });
  }
  
  setupEventListeners() {
    this.socket.on('join_success', (data) => {
      const { scene_id, current_version, objects, users } = data;
      const store = useStore.getState();
      
      store.setSceneId(scene_id);
      store.setCurrentVersion(current_version);
      store.setObjects(objects);
      store.setUsers(users);
      store.setConnectionStatus('connected');
      
      console.log('Joined scene:', scene_id, 'Version:', current_version);
      
      this.processQueuedOperations();
    });
    
    this.socket.on('user_joined', (data) => {
      const { user_id, user_name, users } = data;
      useStore.getState().setUsers(users);
      console.log('User joined:', user_name);
    });
    
    this.socket.on('user_left', (data) => {
      const { user_id, users } = data;
      useStore.getState().setUsers(users);
      console.log('User left:', user_id);
    });
    
    this.socket.on('operation_broadcast', (data) => {
      const {
        operation_id,
        version,
        object_id,
        operation_type,
        object_type,
        parameters,
        user_id,
        timestamp
      } = data;
      
      const store = useStore.getState();
      
      if (user_id !== store.userId) {
        this.applyRemoteOperation({
          operation_type,
          object_id,
          object_type,
          parameters,
          version
        });
      }
      
      store.setCurrentVersion(version);
      
      const callback = this.operationCallbacks.get(operation_id);
      if (callback) {
        callback({ success: true, data });
        this.operationCallbacks.delete(operation_id);
      }
    });
    
    this.socket.on('operation_ack', (data) => {
      const { operation_id, version, success, error } = data;
      
      if (success) {
        useStore.getState().setCurrentVersion(version);
      }
      
      const callback = this.operationCallbacks.get(operation_id);
      if (callback) {
        callback({ success, version, error });
        this.operationCallbacks.delete(operation_id);
      }
      
      useStore.getState().removePendingOperation(operation_id);
    });
    
    this.socket.on('error', (data) => {
      console.error('Server error:', data.message);
    });
  }
  
  joinScene(sceneId) {
    if (!this.socket || !this.socket.connected) {
      console.error('Socket not connected');
      return;
    }
    
    this.sceneId = sceneId;
    const store = useStore.getState();
    
    this.socket.emit('join_scene', {
      scene_id: sceneId,
      user_id: store.userId,
      user_name: store.userName
    });
    
    store.setConnectionStatus('joining');
  }
  
  leaveScene() {
    if (this.socket && this.socket.connected) {
      this.socket.emit('leave_scene');
    }
    
    this.sceneId = null;
    useStore.getState().resetScene();
  }
  
  sendOperation(operationData, callback = null) {
    const operationId = `op_${Date.now()}_${uuidv4().substr(0, 6)}`;
    
    const store = useStore.getState();
    
    const message = {
      operation_type: operationData.operation_type,
      object_type: operationData.object_type,
      target_object: operationData.target_object,
      parameters: operationData.parameters
    };
    
    const pendingOperation = {
      operation_id: operationId,
      ...message,
      timestamp: Date.now()
    };
    
    store.addPendingOperation(pendingOperation);
    
    if (callback) {
      this.operationCallbacks.set(operationId, callback);
    }
    
    if (this.connected && this.socket && this.socket.connected && this.sceneId) {
      this.socket.emit('scene_operation', message);
    } else {
      this.operationQueue.push({ message, operationId });
      console.log('Operation queued, will send when connected');
    }
    
    return operationId;
  }
  
  processQueuedOperations() {
    if (this.isProcessingQueue || this.operationQueue.length === 0) {
      return;
    }
    
    this.isProcessingQueue = true;
    
    while (this.operationQueue.length > 0) {
      const { message, operationId } = this.operationQueue.shift();
      
      if (this.connected && this.socket) {
        console.log('Sending queued operation:', message.operation_type);
        this.socket.emit('scene_operation', message);
      }
    }
    
    this.isProcessingQueue = false;
  }
  
  applyRemoteOperation(operation) {
    const { operation_type, object_id, object_type, parameters, version } = operation;
    const store = useStore.getState();
    
    switch (operation_type) {
      case 'object_create':
        store.addObject({
          object_id,
          object_type,
          transform: parameters.transform || {
            position: { x: 0, y: 0, z: 0 },
            rotation: { x: 0, y: 0, z: 0 },
            scale: { x: 1, y: 1, z: 1 }
          },
          material_id: parameters.material_id || 'mat_default_01',
          asset_id: parameters.asset_id,
          version
        });
        break;
        
      case 'transform_update':
        if (parameters.transform) {
          const existing = store.getObject(object_id);
          if (existing) {
            const updatedTransform = {
              ...existing.transform,
              ...parameters.transform
            };
            store.updateObject(object_id, {
              transform: updatedTransform,
              version
            });
          }
        }
        break;
        
      case 'object_delete':
        store.deleteObject(object_id);
        break;
        
      case 'material_update':
        store.updateObject(object_id, {
          material_id: parameters.material_id,
          version
        });
        break;
    }
  }
  
  disconnect() {
    if (this.socket) {
      this.leaveScene();
      this.socket.disconnect();
      this.socket = null;
    }
    this.connected = false;
  }
  
  isConnected() {
    return this.connected && this.socket && this.socket.connected;
  }
}

const syncClient = new SyncClient();
export default syncClient;
