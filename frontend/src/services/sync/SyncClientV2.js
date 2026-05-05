import { io } from 'socket.io-client';
import useStore from '../../store';
import { v4 as uuidv4 } from 'uuid';
import messageCompressor from './MessageCompressor';
import operationProcessor from './OperationProcessor';

class SyncClientV2 {
  constructor() {
    this.socket = null;
    this.sceneId = null;
    this.userId = null;
    this.connected = false;
    this.operationQueue = [];
    this.isProcessingQueue = false;
    this.operationCallbacks = new Map();
    this.pendingOperations = [];
    this.lastLocalVersions = new Map();
    this.compressionEnabled = true;
    this.ackTimeout = 5000;
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
        reconnectionAttempts: 10,
        reconnectionDelay: 1000,
        timeout: 10000
      });
      
      this.socket.on('connect', () => {
        this.connected = true;
        useStore.getState().setConnectionStatus('connected');
        console.log('WebSocket connected (V2)');
        resolve(this.socket);
      });
      
      this.socket.on('disconnect', (reason) => {
        this.connected = false;
        useStore.getState().setConnectionStatus('disconnected');
        console.log('WebSocket disconnected:', reason);
        
        if (reason === 'io server disconnect' || reason === 'io client disconnect') {
          return;
        }
        
        console.log('Will attempt to reconnect...');
      });
      
      this.socket.on('connect_error', (error) => {
        console.error('WebSocket connection error:', error);
        useStore.getState().setConnectionStatus('error');
        reject(error);
      });
      
      this.socket.on('reconnect', (attemptNumber) => {
        console.log(`Reconnected after ${attemptNumber} attempts`);
        this.connected = true;
        useStore.getState().setConnectionStatus('connected');
        
        if (this.sceneId) {
          this.rejoinScene();
        }
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
      
      if (objects && Array.isArray(objects)) {
        store.setObjects(objects);
        
        objects.forEach(obj => {
          if (obj.object_id && obj.version) {
            this.lastLocalVersions.set(obj.object_id, obj.version);
          }
        });
      }
      
      if (users && Array.isArray(users)) {
        store.setUsers(users);
      }
      
      store.setConnectionStatus('connected');
      this.sceneId = scene_id;
      
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
      this.handleIncomingOperation(data);
    });
    
    this.socket.on('operation_ack', (data) => {
      this.handleOperationAck(data);
    });
    
    this.socket.on('error', (data) => {
      console.error('Server error:', data?.message || data);
    });
    
    this.socket.on('snapshot_update', (data) => {
      this.handleSnapshotUpdate(data);
    });
  }

  handleIncomingOperation(data) {
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
    
    if (user_id === store.userId) {
      this.processOperationAck(operation_id, version, true);
      return;
    }
    
    const decompressedParams = this.compressionEnabled 
      ? messageCompressor.decompress(parameters)
      : parameters;
    
    const operation = {
      operation_type,
      object_id,
      object_type,
      parameters: decompressedParams,
      version
    };
    
    const success = operationProcessor.process(operation);
    
    if (success) {
      store.setCurrentVersion(version);
      if (object_id && version) {
        this.lastLocalVersions.set(object_id, version);
      }
    }
    
    console.log(`Applied remote operation: ${operation_type}`, success);
  }

  handleOperationAck(data) {
    const { operation_id, version, success, error } = data;
    
    this.processOperationAck(operation_id, version, success, error);
  }

  processOperationAck(operationId, version, success, error = null) {
    if (success && version) {
      useStore.getState().setCurrentVersion(version);
    }
    
    const callback = this.operationCallbacks.get(operationId);
    if (callback) {
      callback({ success, version, error });
      this.operationCallbacks.delete(operationId);
    }
    
    useStore.getState().removePendingOperation(operationId);
    
    const index = this.pendingOperations.findIndex(op => op.operation_id === operationId);
    if (index !== -1) {
      this.pendingOperations.splice(index, 1);
    }
  }

  handleSnapshotUpdate(data) {
    const { version, objects, full } = data;
    const store = useStore.getState();
    
    if (full) {
      console.log('Applying full snapshot, version:', version);
      store.setObjects(objects);
      store.setCurrentVersion(version);
    } else if (objects && Array.isArray(objects)) {
      console.log('Applying incremental snapshot, version:', version);
      objects.forEach(obj => {
        const existing = store.getObject(obj.object_id);
        if (!existing || (existing.version && obj.version > existing.version)) {
          if (obj.is_deleted) {
            store.deleteObject(obj.object_id);
          } else {
            store.addObject(obj);
          }
        }
      });
      store.setCurrentVersion(version);
    }
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
      user_name: store.userName,
      last_known_version: store.currentVersion
    });
    
    store.setConnectionStatus('joining');
  }

  rejoinScene() {
    if (this.sceneId) {
      console.log('Rejoining scene:', this.sceneId);
      this.joinScene(this.sceneId);
    }
  }

  leaveScene() {
    if (this.socket && this.socket.connected && this.sceneId) {
      this.socket.emit('leave_scene');
    }
    
    this.sceneId = null;
    this.lastLocalVersions.clear();
    this.pendingOperations = [];
    useStore.getState().resetScene();
  }

  sendOperation(operationData, callback = null) {
    const operationId = `op_${Date.now()}_${uuidv4().substr(0, 6)}`;
    
    const store = useStore.getState();
    
    const { operation_type, object_type, target_object, parameters } = operationData;
    
    const compressedParams = this.compressionEnabled
      ? messageCompressor.compress(parameters)
      : parameters;
    
    const message = {
      operation_type,
      object_type,
      target_object,
      parameters: compressedParams
    };
    
    const pendingOperation = {
      operation_id: operationId,
      ...message,
      timestamp: Date.now()
    };
    
    store.addPendingOperation(pendingOperation);
    this.pendingOperations.push(pendingOperation);
    
    if (callback) {
      this.operationCallbacks.set(operationId, callback);
    }
    
    if (this.connected && this.socket && this.socket.connected && this.sceneId) {
      this.socket.emit('scene_operation', message);
      
      setTimeout(() => {
        if (this.operationCallbacks.has(operationId)) {
          console.warn(`Operation ${operationId} timeout, retrying...`);
          this.operationQueue.push({ message, operationId });
        }
      }, this.ackTimeout);
    } else {
      this.operationQueue.push({ message, operationId });
      console.log('Operation queued, will send when connected');
    }
    
    return operationId;
  }

  sendCreateOperation(objectData, callback = null) {
    const operation = operationProcessor.createOperationCreate(objectData);
    return this.sendOperation(operation, callback);
  }

  sendTransformOperation(objectId, originalTransform, newTransform, callback = null) {
    const operation = operationProcessor.createOperationTransform(
      objectId,
      originalTransform,
      newTransform
    );
    
    if (!operation) {
      console.log('No transform changes to send');
      return null;
    }
    
    return this.sendOperation(operation, callback);
  }

  sendDeleteOperation(objectId, callback = null) {
    const operation = operationProcessor.createOperationDelete(objectId);
    return this.sendOperation(operation, callback);
  }

  sendMaterialOperation(objectId, materialId, callback = null) {
    const operation = operationProcessor.createOperationMaterial(objectId, materialId);
    return this.sendOperation(operation, callback);
  }

  processQueuedOperations() {
    if (this.isProcessingQueue || this.operationQueue.length === 0) {
      return;
    }
    
    this.isProcessingQueue = true;
    
    console.log(`Processing ${this.operationQueue.length} queued operations...`);
    
    while (this.operationQueue.length > 0) {
      const { message, operationId } = this.operationQueue.shift();
      
      if (this.connected && this.socket) {
        console.log('Sending queued operation:', message.operation_type);
        this.socket.emit('scene_operation', message);
      }
    }
    
    this.isProcessingQueue = false;
  }

  toggleCompression(enabled) {
    this.compressionEnabled = enabled;
    console.log('Message compression:', enabled ? 'enabled' : 'disabled');
  }

  getStats() {
    return {
      connected: this.connected,
      sceneId: this.sceneId,
      pendingOperations: this.pendingOperations.length,
      queueSize: this.operationQueue.length,
      compressionEnabled: this.compressionEnabled,
      trackedObjects: this.lastLocalVersions.size
    };
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

const syncClientV2 = new SyncClientV2();
export default syncClientV2;
