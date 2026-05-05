import { io } from 'socket.io-client';
import { v4 as uuidv4 } from 'uuid';

class CollaborationClient {
  constructor() {
    this.socket = null;
    this.docId = null;
    this.userId = null;
    this.userName = null;
    this.isConnected = false;
    this.isJoined = false;
    
    this.pendingOps = [];
    this.opAcks = new Map();
    
    this.callbacks = {
      onConnected: [],
      onDisconnected: [],
      onJoin: [],
      onLeave: [],
      onSync: [],
      onCollaboratorJoin: [],
      onCollaboratorLeave: [],
      onError: [],
      onOperationAck: []
    };
    
    this.collaborators = [];
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 10;
  }

  connect() {
    if (this.socket && this.socket.connected) {
      return;
    }
    
    this.socket = io({
      path: '/socket.io',
      transports: ['websocket', 'polling'],
      reconnection: true,
      reconnectionAttempts: this.maxReconnectAttempts,
      reconnectionDelay: 1000,
      reconnectionDelayMax: 5000
    });
    
    this.socket.on('connect', () => {
      console.log('Socket connected');
      this.isConnected = true;
      this.reconnectAttempts = 0;
      this._emit('onConnected');
      
      if (this.docId && this.userId) {
        this.joinDocument(this.docId, this.userId, this.userName);
      }
    });
    
    this.socket.on('disconnect', (reason) => {
      console.log('Socket disconnected:', reason);
      this.isConnected = false;
      this.isJoined = false;
      this._emit('onDisconnected', { reason });
    });
    
    this.socket.on('connect_error', (error) => {
      console.error('Socket connection error:', error);
      this.reconnectAttempts++;
      this._emit('onError', { error: error.message });
    });
    
    this.socket.on('doc_joined', (data) => {
      console.log('Joined document:', data);
      this.isJoined = true;
      this.collaborators = data.collaborators || [];
      this._emit('onJoin', data);
    });
    
    this.socket.on('collaborator_joined', (data) => {
      console.log('Collaborator joined:', data);
      this.collaborators = data.collaborators || [];
      this._emit('onCollaboratorJoin', data);
    });
    
    this.socket.on('collaborator_left', (data) => {
      console.log('Collaborator left:', data);
      this.collaborators = data.collaborators || [];
      this._emit('onCollaboratorLeave', data);
    });
    
    this.socket.on('sync_broadcast', (data) => {
      console.log('Received sync broadcast:', data);
      
      if (data.user_id !== this.userId) {
        this._emit('onSync', data);
      }
    });
    
    this.socket.on('operation_ack', (data) => {
      console.log('Operation ack:', data);
      this.opAcks.set(data.client_op_id, {
        status: data.status,
        op_id: data.op_id
      });
      
      this._emit('onOperationAck', data);
      
      if (this.pendingOps.length > 0) {
        const index = this.pendingOps.findIndex(op => op.client_op_id === data.client_op_id);
        if (index > -1) {
          this.pendingOps.splice(index, 1);
        }
      }
    });
    
    this.socket.on('sync_response', (data) => {
      console.log('Sync response:', data);
      this._emit('onSync', data);
    });
    
    this.socket.on('error', (data) => {
      console.error('Socket error:', data);
      this._emit('onError', data);
    });
  }

  disconnect() {
    if (this.socket) {
      if (this.isJoined && this.docId) {
        this.leaveDocument();
      }
      this.socket.disconnect();
      this.socket = null;
    }
    
    this.isConnected = false;
    this.isJoined = false;
    this.collaborators = [];
  }

  joinDocument(docId, userId, userName = 'Anonymous') {
    this.docId = docId;
    this.userId = userId;
    this.userName = userName;
    
    if (!this.socket || !this.socket.connected) {
      this.connect();
      return;
    }
    
    console.log('Joining document:', docId);
    this.socket.emit('join_doc', {
      doc_id: docId,
      user_id: userId,
      user_name: userName
    });
  }

  leaveDocument() {
    if (!this.socket || !this.isJoined) {
      return;
    }
    
    console.log('Leaving document:', this.docId);
    this.socket.emit('leave_doc', {
      doc_id: this.docId,
      user_id: this.userId
    });
    
    this.isJoined = false;
    this.docId = null;
    this.collaborators = [];
    this.pendingOps = [];
  }

  sendOperation(opType, opData) {
    if (!this.socket || !this.isJoined) {
      console.warn('Not connected or not in a document, queuing operation');
      
      const pendingOp = {
        client_op_id: uuidv4(),
        doc_id: this.docId,
        user_id: this.userId,
        op_type: opType,
        op_data: opData,
        timestamp: Date.now()
      };
      this.pendingOps.push(pendingOp);
      
      return pendingOp.client_op_id;
    }
    
    const clientOpId = uuidv4();
    
    const message = {
      doc_id: this.docId,
      user_id: this.userId,
      op_type: opType,
      op_data: opData,
      client_op_id: clientOpId
    };
    
    console.log('Sending operation:', message);
    this.socket.emit('edit_operation', message);
    
    return clientOpId;
  }

  sendInsert(position, text) {
    return this.sendOperation('insert', {
      position,
      text
    });
  }

  sendDelete(start, end) {
    return this.sendOperation('delete', {
      start,
      end
    });
  }

  sendReplace(content, title) {
    return this.sendOperation('replace', {
      content,
      title
    });
  }

  sendFormatChange(formatData) {
    return this.sendOperation('format', formatData);
  }

  requestSync(lastSyncTime) {
    if (!this.socket || !this.isJoined) {
      return;
    }
    
    this.socket.emit('sync_request', {
      doc_id: this.docId,
      last_sync_time: lastSyncTime,
      pending_ops: this.pendingOps
    });
  }

  sendPendingOps() {
    if (!this.socket || !this.isJoined || this.pendingOps.length === 0) {
      return;
    }
    
    console.log('Sending pending operations:', this.pendingOps.length);
    
    const opsToSend = [...this.pendingOps];
    this.pendingOps = [];
    
    for (const op of opsToSend) {
      this.socket.emit('edit_operation', op);
    }
  }

  on(event, callback) {
    if (!this.callbacks[event]) {
      console.warn(`Unknown event: ${event}`);
      return () => {};
    }
    
    this.callbacks[event].push(callback);
    
    return () => {
      const index = this.callbacks[event].indexOf(callback);
      if (index > -1) {
        this.callbacks[event].splice(index, 1);
      }
    };
  }

  _emit(event, data) {
    if (this.callbacks[event]) {
      this.callbacks[event].forEach(callback => {
        try {
          callback(data);
        } catch (error) {
          console.error(`Error in ${event} callback:`, error);
        }
      });
    }
  }

  getCollaborators() {
    return [...this.collaborators];
  }

  getConnectionStatus() {
    return {
      connected: this.isConnected,
      joined: this.isJoined,
      docId: this.docId,
      userId: this.userId,
      userName: this.userName,
      pendingOps: this.pendingOps.length
    };
  }

  setUserId(userId, userName = 'Anonymous') {
    this.userId = userId;
    this.userName = userName;
  }
}

const collaborationClient = new CollaborationClient();

export default collaborationClient;
