const { v4: uuidv4 } = require('uuid');

class OperationalTransform {
  constructor() {
    this.operationQueue = new Map();
    this.versionMap = new Map();
  }

  transformInsert(insertOp, concurrentOp) {
    if (concurrentOp.op_type === 'insert') {
      if (concurrentOp.op_data.position < insertOp.op_data.position) {
        return {
          ...insertOp,
          op_data: {
            ...insertOp.op_data,
            position: insertOp.op_data.position + concurrentOp.op_data.text.length
          }
        };
      } else if (concurrentOp.op_data.position === insertOp.op_data.position) {
        if (insertOp.user_id < concurrentOp.user_id) {
          return insertOp;
        } else {
          return {
            ...insertOp,
            op_data: {
              ...insertOp.op_data,
              position: insertOp.op_data.position + concurrentOp.op_data.text.length
            }
          };
        }
      }
      return insertOp;
    }

    if (concurrentOp.op_type === 'delete') {
      const concurrentStart = concurrentOp.op_data.start || 0;
      const concurrentEnd = concurrentOp.op_data.end || (concurrentStart + (concurrentOp.op_data.length || 0));

      if (insertOp.op_data.position <= concurrentStart) {
        return insertOp;
      } else if (insertOp.op_data.position >= concurrentEnd) {
        return {
          ...insertOp,
          op_data: {
            ...insertOp.op_data,
            position: insertOp.op_data.position - (concurrentEnd - concurrentStart)
          }
        };
      } else {
        return {
          ...insertOp,
          op_data: {
            ...insertOp.op_data,
            position: concurrentStart
          }
        };
      }
    }

    return insertOp;
  }

  transformDelete(deleteOp, concurrentOp) {
    const deleteStart = deleteOp.op_data.start || 0;
    const deleteEnd = deleteOp.op_data.end || (deleteStart + (deleteOp.op_data.length || 0));
    const deleteLength = deleteEnd - deleteStart;

    if (concurrentOp.op_type === 'insert') {
      const insertPos = concurrentOp.op_data.position;
      const insertLength = concurrentOp.op_data.text.length;

      if (insertPos <= deleteStart) {
        return {
          ...deleteOp,
          op_data: {
            ...deleteOp.op_data,
            start: deleteStart + insertLength,
            end: deleteEnd + insertLength,
            length: deleteLength
          }
        };
      } else if (insertPos >= deleteEnd) {
        return deleteOp;
      } else {
        return {
          ...deleteOp,
          op_data: {
            ...deleteOp.op_data,
            start: deleteStart,
            end: deleteEnd + insertLength,
            length: deleteLength + insertLength
          }
        };
      }
    }

    if (concurrentOp.op_type === 'delete') {
      const concurrentStart = concurrentOp.op_data.start || 0;
      const concurrentEnd = concurrentOp.op_data.end || (concurrentStart + (concurrentOp.op_data.length || 0));
      const concurrentLength = concurrentEnd - concurrentStart;

      if (concurrentEnd <= deleteStart) {
        return {
          ...deleteOp,
          op_data: {
            ...deleteOp.op_data,
            start: deleteStart - concurrentLength,
            end: deleteEnd - concurrentLength,
            length: deleteLength
          }
        };
      } else if (concurrentStart >= deleteEnd) {
        return deleteOp;
      } else {
        const newStart = Math.min(deleteStart, concurrentStart);
        const newEnd = Math.max(deleteEnd, concurrentEnd);
        const newLength = newEnd - newStart;

        return {
          ...deleteOp,
          op_data: {
            ...deleteOp.op_data,
            start: newStart,
            end: newEnd,
            length: newLength,
            is_merged: true
          }
        };
      }
    }

    return deleteOp;
  }

  transform(op, concurrentOp) {
    if (!op || !concurrentOp) return op;
    if (op.op_id === concurrentOp.op_id) return op;

    let transformedOp = { ...op, op_data: { ...op.op_data } };

    if (op.op_type === 'insert') {
      transformedOp = this.transformInsert(transformedOp, concurrentOp);
    } else if (op.op_type === 'delete') {
      transformedOp = this.transformDelete(transformedOp, concurrentOp);
    }

    return transformedOp;
  }

  applyOperation(content, op) {
    if (!content) content = '';

    if (op.op_type === 'insert') {
      const position = Math.min(op.op_data.position || 0, content.length);
      const text = op.op_data.text || '';
      return content.slice(0, position) + text + content.slice(position);
    } else if (op.op_type === 'delete') {
      const start = Math.max(0, op.op_data.start || 0);
      const end = Math.min(content.length, op.op_data.end || (start + (op.op_data.length || 0)));
      return content.slice(0, start) + content.slice(end);
    } else if (op.op_type === 'replace') {
      return op.op_data.content !== undefined ? op.op_data.content : content;
    }

    return content;
  }

  detectConflict(op1, op2) {
    if (!op1 || !op2) return false;
    if (op1.op_type === 'replace' || op2.op_type === 'replace') return true;

    if (op1.op_type === 'insert' && op2.op_type === 'insert') {
      const pos1 = op1.op_data.position || 0;
      const pos2 = op2.op_data.position || 0;
      return pos1 === pos2;
    }

    if (op1.op_type === 'delete' && op2.op_type === 'delete') {
      const start1 = op1.op_data.start || 0;
      const end1 = op1.op_data.end || (start1 + (op1.op_data.length || 0));
      const start2 = op2.op_data.start || 0;
      const end2 = op2.op_data.end || (start2 + (op2.op_data.length || 0));
      
      return !(end1 <= start2 || end2 <= start1);
    }

    return false;
  }

  createConflictMarker(op1, op2, originalContent) {
    return {
      op_type: 'conflict',
      op_data: {
        operations: [op1, op2],
        original_content: originalContent,
        resolved: false
      }
    };
  }
}

class MockSocket {
  constructor(id, mockIo) {
    this.id = id;
    this.mockIo = mockIo;
    this.rooms = new Set();
    this.eventHandlers = new Map();
    this.emittedEvents = [];
    this.user_id = null;
    this.user_name = null;
  }

  join(room) {
    this.rooms.add(room);
    if (this.mockIo.rooms) {
      if (!this.mockIo.rooms.has(room)) {
        this.mockIo.rooms.set(room, new Set());
      }
      this.mockIo.rooms.get(room).add(this);
    }
  }

  leave(room) {
    this.rooms.delete(room);
    if (this.mockIo.rooms && this.mockIo.rooms.has(room)) {
      this.mockIo.rooms.get(room).delete(this);
    }
  }

  emit(event, data) {
    this.emittedEvents.push({ event, data, timestamp: Date.now() });
    if (this.mockIo.eventListener) {
      this.mockIo.eventListener(this.id, event, data);
    }
  }

  on(event, handler) {
    if (!this.eventHandlers.has(event)) {
      this.eventHandlers.set(event, []);
    }
    this.eventHandlers.get(event).push(handler);
  }

  triggerEvent(event, data) {
    const handlers = this.eventHandlers.get(event) || [];
    for (const handler of handlers) {
      handler(data);
    }
  }
}

class MockIo {
  constructor() {
    this.sockets = new Map();
    this.rooms = new Map();
    this.eventListener = null;
    this.connectionHandlers = [];
    this.emittedEvents = [];
  }

  on(event, handler) {
    if (event === 'connection') {
      this.connectionHandlers.push(handler);
    }
  }

  emit(event, data) {
    this.emittedEvents.push({ event, data, timestamp: Date.now() });
  }

  to(room) {
    return {
      emit: (event, data) => {
        const roomSockets = this.rooms.get(room) || new Set();
        for (const socket of roomSockets) {
          socket.emit(event, data);
        }
        this.emittedEvents.push({ event, data, room, timestamp: Date.now() });
      }
    };
  }

  createSocket(id) {
    const socket = new MockSocket(id, this);
    this.sockets.set(id, socket);
    
    for (const handler of this.connectionHandlers) {
      handler(socket);
    }
    
    return socket;
  }

  getSocket(id) {
    return this.sockets.get(id);
  }

  disconnectSocket(id) {
    const socket = this.sockets.get(id);
    if (socket) {
      for (const room of socket.rooms) {
        const roomSockets = this.rooms.get(room);
        if (roomSockets) {
          roomSockets.delete(socket);
        }
      }
      this.sockets.delete(id);
    }
  }
}

class CollaborationServiceTestable {
  constructor() {
    this.activeSessions = new Map();
    this.docVersions = new Map();
    this.ot = new OperationalTransform();
    this.pendingOperations = new Map();
    this.conflictResolution = new Map();
    this.documents = new Map();
    this.io = null;
  }

  init(io) {
    this.io = io;
    
    io.on('connection', (socket) => {
      console.log('New client connected:', socket.id);
      
      socket.on('join_doc', async (data) => {
        try {
          await this.handleJoinDoc(socket, data);
        } catch (error) {
          socket.emit('error', { message: error.message });
        }
      });
      
      socket.on('leave_doc', (data) => {
        this.handleLeaveDoc(socket, data);
      });
      
      socket.on('edit_operation', async (data) => {
        try {
          await this.handleEditOperation(socket, data);
        } catch (error) {
          socket.emit('error', { message: error.message });
        }
      });
      
      socket.on('sync_request', async (data) => {
        try {
          await this.handleSyncRequest(socket, data);
        } catch (error) {
          socket.emit('error', { message: error.message });
        }
      });

      socket.on('resolve_conflict', async (data) => {
        try {
          await this.handleResolveConflict(socket, data);
        } catch (error) {
          socket.emit('error', { message: error.message });
        }
      });
      
      socket.on('disconnect', () => {
        this.handleDisconnect(socket);
      });
    });
  }

  createDocument(doc_id, title, content, created_by) {
    this.documents.set(doc_id, {
      doc_id,
      title,
      content,
      created_by,
      last_edited_by: created_by,
      last_edited_at: new Date(),
      current_version: 1
    });
  }

  getDocument(doc_id) {
    return this.documents.get(doc_id);
  }

  async handleJoinDoc(socket, { doc_id, user_id, user_name }) {
    const roomId = `doc_${doc_id}`;
    
    socket.join(roomId);
    socket.user_id = user_id;
    socket.user_name = user_name;
    
    if (!this.activeSessions.has(doc_id)) {
      this.activeSessions.set(doc_id, new Map());
    }
    
    if (!this.pendingOperations.has(doc_id)) {
      this.pendingOperations.set(doc_id, []);
    }
    
    if (!this.conflictResolution.has(doc_id)) {
      this.conflictResolution.set(doc_id, []);
    }
    
    const docSessions = this.activeSessions.get(doc_id);
    docSessions.set(socket.id, { user_id, user_name, socket });
    
    const collaborators = Array.from(docSessions.values()).map(s => ({
      user_id: s.user_id,
      user_name: s.user_name
    }));
    
    this.io.to(roomId).emit('collaborator_joined', {
      user_id,
      user_name,
      collaborators
    });
    
    const pendingOps = this.pendingOperations.get(doc_id) || [];
    const conflicts = this.conflictResolution.get(doc_id) || [];
    
    socket.emit('doc_joined', {
      doc_id,
      collaborators,
      pending_operations: pendingOps.length,
      pending_conflicts: conflicts.length,
      timestamp: new Date().toISOString()
    });
    
    console.log(`User ${user_name} joined document ${doc_id}`);
  }

  handleLeaveDoc(socket, { doc_id, user_id }) {
    const roomId = `doc_${doc_id}`;
    socket.leave(roomId);
    
    if (this.activeSessions.has(doc_id)) {
      const docSessions = this.activeSessions.get(doc_id);
      const session = docSessions.get(socket.id);
      const userName = session?.user_name;
      
      docSessions.delete(socket.id);
      
      if (docSessions.size === 0) {
        this.activeSessions.delete(doc_id);
        this.pendingOperations.delete(doc_id);
        this.conflictResolution.delete(doc_id);
      } else {
        const collaborators = Array.from(docSessions.values()).map(s => ({
          user_id: s.user_id,
          user_name: s.user_name
        }));
        
        this.io.to(roomId).emit('collaborator_left', {
          user_id,
          user_name: userName,
          collaborators
        });
      }
    }
    
    console.log(`User ${user_id} left document ${doc_id}`);
  }

  async handleEditOperation(socket, { doc_id, op_type, op_data, user_id, client_op_id, version }) {
    const opId = uuidv4();
    const docSessions = this.activeSessions.get(doc_id);
    
    if (!docSessions) {
      throw new Error('No active session for this document');
    }
    
    const doc = this.getDocument(doc_id);
    if (!doc) {
      throw new Error('Document not found');
    }

    const incomingOp = {
      op_id: opId,
      doc_id,
      user_id,
      op_type,
      op_data: { ...op_data },
      op_time: new Date(),
      client_op_id,
      version
    };

    const pendingOps = this.pendingOperations.get(doc_id) || [];
    let finalOp = { ...incomingOp };
    let conflictDetected = false;
    let conflictInfo = null;

    for (const pendingOp of pendingOps) {
      if (this.ot.detectConflict(finalOp, pendingOp)) {
        conflictDetected = true;
        conflictInfo = this.ot.createConflictMarker(finalOp, pendingOp, doc.content);
        
        const conflicts = this.conflictResolution.get(doc_id) || [];
        conflicts.push({
          conflict_id: uuidv4(),
          op1: finalOp,
          op2: pendingOp,
          original_content: doc.content,
          created_at: new Date()
        });
        this.conflictResolution.set(doc_id, conflicts);
        
        this.io.to(`doc_${doc_id}`).emit('conflict_detected', {
          doc_id,
          conflict_id: conflicts[conflicts.length - 1].conflict_id,
          operations: [finalOp, pendingOp],
          message: '编辑冲突检测，请手动合并或选择保留版本'
        });
        
        console.warn(`Conflict detected in document ${doc_id} between users ${finalOp.user_id} and ${pendingOp.user_id}`);
      }
      
      finalOp = this.ot.transform(finalOp, pendingOp);
    }

    if (conflictDetected) {
      socket.emit('operation_conflict', {
        op_id: opId,
        client_op_id,
        conflict_id: conflictInfo?.conflict_id,
        status: 'conflict_pending_resolution'
      });
      return;
    }

    pendingOps.push(finalOp);
    this.pendingOperations.set(doc_id, pendingOps);

    const oldContent = doc.content;
    doc.content = this.ot.applyOperation(doc.content, finalOp);
    
    if (finalOp.op_type === 'replace' && finalOp.op_data.title) {
      doc.title = finalOp.op_data.title;
    }
    
    doc.last_edited_by = user_id;
    doc.last_edited_at = new Date();
    
    const roomId = `doc_${doc_id}`;
    
    this.io.to(roomId).emit('sync_broadcast', {
      op_id: finalOp.op_id,
      client_op_id,
      doc_id,
      user_id,
      op_type: finalOp.op_type,
      op_data: finalOp.op_data,
      original_op_data: op_data,
      op_time: finalOp.op_time.toISOString(),
      sync_status: 'success',
      was_transformed: finalOp.op_id !== incomingOp.op_id || 
        JSON.stringify(finalOp.op_data) !== JSON.stringify(incomingOp.op_data)
    });
    
    socket.emit('operation_ack', {
      op_id: finalOp.op_id,
      client_op_id,
      status: 'success',
      applied_content: doc.content
    });

    if (pendingOps.length > 100) {
      this.pendingOperations.set(doc_id, pendingOps.slice(-50));
    }
  }

  async handleResolveConflict(socket, { doc_id, conflict_id, resolution, user_id }) {
    const conflicts = this.conflictResolution.get(doc_id) || [];
    const conflictIndex = conflicts.findIndex(c => c.conflict_id === conflict_id);
    
    if (conflictIndex === -1) {
      throw new Error('Conflict not found');
    }

    const conflict = conflicts[conflictIndex];
    const doc = this.getDocument(doc_id);
    
    if (!doc) {
      throw new Error('Document not found');
    }

    let resolvedContent = conflict.original_content;
    
    if (resolution === 'keep_first') {
      resolvedContent = this.ot.applyOperation(conflict.original_content, conflict.op1);
    } else if (resolution === 'keep_second') {
      resolvedContent = this.ot.applyOperation(conflict.original_content, conflict.op2);
    } else if (resolution === 'keep_both') {
      let temp = this.ot.applyOperation(conflict.original_content, conflict.op1);
      resolvedContent = this.ot.applyOperation(temp, conflict.op2);
    } else if (typeof resolution === 'object' && resolution.content) {
      resolvedContent = resolution.content;
    }

    doc.content = resolvedContent;
    doc.last_edited_by = user_id;
    doc.last_edited_at = new Date();

    conflicts.splice(conflictIndex, 1);
    this.conflictResolution.set(doc_id, conflicts);

    const roomId = `doc_${doc_id}`;
    
    this.io.to(roomId).emit('conflict_resolved', {
      doc_id,
      conflict_id,
      resolution,
      resolved_by: user_id,
      resolved_content: resolvedContent,
      resolved_at: new Date().toISOString()
    });

    socket.emit('conflict_resolve_ack', {
      conflict_id,
      status: 'success'
    });

    console.log(`Conflict ${conflict_id} resolved in document ${doc_id} by user ${user_id}`);
  }

  async handleSyncRequest(socket, { doc_id, last_sync_time, pending_ops, client_version }) {
    const doc = this.getDocument(doc_id);
    
    const conflicts = this.conflictResolution.get(doc_id) || [];
    const pendingOps = this.pendingOperations.get(doc_id) || [];
    
    let filteredOps = pendingOps;
    if (last_sync_time) {
      const syncDate = new Date(last_sync_time);
      filteredOps = pendingOps.filter(op => op.op_time > syncDate);
    }
    
    socket.emit('sync_response', {
      doc_id,
      current_content: doc?.content || '',
      current_title: doc?.title || '',
      current_version: doc?.current_version || 1,
      operations: filteredOps.map(op => ({
        op_id: op.op_id,
        user_id: op.user_id,
        op_type: op.op_type,
        op_data: op.op_data,
        op_time: op.op_time.toISOString()
      })),
      pending_conflicts: conflicts.map(c => ({
        conflict_id: c.conflict_id,
        op1: c.op1,
        op2: c.op2,
        created_at: c.created_at.toISOString()
      })),
      sync_time: new Date().toISOString()
    });
  }

  handleDisconnect(socket) {
    console.log('Client disconnected:', socket.id);
    
    for (const [docId, sessions] of this.activeSessions.entries()) {
      if (sessions.has(socket.id)) {
        const session = sessions.get(socket.id);
        sessions.delete(socket.id);
        
        const roomId = `doc_${docId}`;
        
        if (sessions.size === 0) {
          this.activeSessions.delete(docId);
          this.pendingOperations.delete(docId);
        } else {
          const collaborators = Array.from(sessions.values()).map(s => ({
            user_id: s.user_id,
            user_name: s.user_name
          }));
          
          this.io.to(roomId).emit('collaborator_left', {
            user_id: session.user_id,
            user_name: session.user_name,
            collaborators
          });
        }
      }
    }
  }

  getActiveCollaborators(doc_id) {
    const sessions = this.activeSessions.get(doc_id);
    if (!sessions) return [];
    
    return Array.from(sessions.values()).map(s => ({
      user_id: s.user_id,
      user_name: s.user_name
    }));
  }

  getPendingOperations(doc_id) {
    return this.pendingOperations.get(doc_id) || [];
  }

  getActiveConflicts(doc_id) {
    return this.conflictResolution.get(doc_id) || [];
  }
}

describe('Collaboration Sync Flow (End-to-End)', () => {
  let mockIo;
  let collaborationService;
  const DOC_ID = 'collab-test-doc-001';
  const USER_ID_1 = 'user-alice';
  const USER_ID_2 = 'user-bob';
  const USER_NAME_1 = 'Alice';
  const USER_NAME_2 = 'Bob';

  beforeEach(() => {
    mockIo = new MockIo();
    collaborationService = new CollaborationServiceTestable();
    collaborationService.init(mockIo);
    
    collaborationService.createDocument(
      DOC_ID,
      'Collaborative Document',
      'Initial content for collaboration testing',
      USER_ID_1
    );
  });

  describe('Multi-user Collaboration Setup', () => {
    test('should allow multiple users to join the same document', () => {
      const socket1 = mockIo.createSocket('socket-alice');
      const socket2 = mockIo.createSocket('socket-bob');

      socket1.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      socket2.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_2,
        user_name: USER_NAME_2
      });

      const collaborators = collaborationService.getActiveCollaborators(DOC_ID);
      expect(collaborators.length).toBe(2);
      expect(collaborators.map(c => c.user_id)).toContain(USER_ID_1);
      expect(collaborators.map(c => c.user_id)).toContain(USER_ID_2);
    });

    test('should notify all users when a collaborator joins', () => {
      const socket1 = mockIo.createSocket('socket-alice');
      
      socket1.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      const socket2 = mockIo.createSocket('socket-bob');
      
      socket2.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_2,
        user_name: USER_NAME_2
      });

      const joinEvents = socket1.emittedEvents.filter(e => e.event === 'collaborator_joined');
      expect(joinEvents.length).toBeGreaterThan(0);
      
      const lastJoinEvent = joinEvents[joinEvents.length - 1];
      expect(lastJoinEvent.data.user_id).toBe(USER_ID_2);
      expect(lastJoinEvent.data.collaborators.length).toBe(2);
    });

    test('should notify users when a collaborator leaves', () => {
      const socket1 = mockIo.createSocket('socket-alice');
      const socket2 = mockIo.createSocket('socket-bob');

      socket1.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      socket2.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_2,
        user_name: USER_NAME_2
      });

      socket2.triggerEvent('leave_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_2
      });

      const leaveEvents = socket1.emittedEvents.filter(e => e.event === 'collaborator_left');
      expect(leaveEvents.length).toBeGreaterThan(0);
      
      const collaborators = collaborationService.getActiveCollaborators(DOC_ID);
      expect(collaborators.length).toBe(1);
    });
  });

  describe('Operation Broadcast and Acknowledgment', () => {
    test('should broadcast edit operation to all collaborators', () => {
      const socket1 = mockIo.createSocket('socket-alice');
      const socket2 = mockIo.createSocket('socket-bob');

      socket1.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      socket2.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_2,
        user_name: USER_NAME_2
      });

      socket1.triggerEvent('edit_operation', {
        doc_id: DOC_ID,
        op_type: 'insert',
        op_data: { position: 0, text: 'Hello ' },
        user_id: USER_ID_1,
        client_op_id: uuidv4(),
        version: 1
      });

      const broadcastEvents = socket2.emittedEvents.filter(e => e.event === 'sync_broadcast');
      expect(broadcastEvents.length).toBe(1);
      
      const broadcast = broadcastEvents[0];
      expect(broadcast.data.user_id).toBe(USER_ID_1);
      expect(broadcast.data.op_type).toBe('insert');
      expect(broadcast.data.op_data.text).toBe('Hello ');
    });

    test('should send operation acknowledgment to sender', () => {
      const socket1 = mockIo.createSocket('socket-alice');

      socket1.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      const clientOpId = uuidv4();
      socket1.triggerEvent('edit_operation', {
        doc_id: DOC_ID,
        op_type: 'insert',
        op_data: { position: 0, text: 'Test' },
        user_id: USER_ID_1,
        client_op_id: clientOpId,
        version: 1
      });

      const ackEvents = socket1.emittedEvents.filter(e => e.event === 'operation_ack');
      expect(ackEvents.length).toBe(1);
      
      const ack = ackEvents[0];
      expect(ack.data.client_op_id).toBe(clientOpId);
      expect(ack.data.status).toBe('success');
      expect(ack.data.applied_content).toContain('Test');
    });

    test('should apply operation to document content', () => {
      const socket1 = mockIo.createSocket('socket-alice');

      socket1.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      const initialContent = collaborationService.getDocument(DOC_ID).content;

      socket1.triggerEvent('edit_operation', {
        doc_id: DOC_ID,
        op_type: 'insert',
        op_data: { position: 0, text: 'Prefix: ' },
        user_id: USER_ID_1,
        client_op_id: uuidv4(),
        version: 1
      });

      const updatedContent = collaborationService.getDocument(DOC_ID).content;
      expect(updatedContent).toBe('Prefix: ' + initialContent);
    });

    test('should handle delete operation correctly', () => {
      const socket1 = mockIo.createSocket('socket-alice');

      collaborationService.createDocument(
        'delete-test-doc',
        'Delete Test',
        'Hello World',
        USER_ID_1
      );

      socket1.triggerEvent('join_doc', {
        doc_id: 'delete-test-doc',
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      socket1.triggerEvent('edit_operation', {
        doc_id: 'delete-test-doc',
        op_type: 'delete',
        op_data: { start: 0, end: 6 },
        user_id: USER_ID_1,
        client_op_id: uuidv4(),
        version: 1
      });

      const doc = collaborationService.getDocument('delete-test-doc');
      expect(doc.content).toBe('World');
    });
  });

  describe('Conflict Detection and Resolution', () => {
    test('should detect conflict when two users insert at same position', () => {
      const socket1 = mockIo.createSocket('socket-alice');
      const socket2 = mockIo.createSocket('socket-bob');

      socket1.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      socket2.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_2,
        user_name: USER_NAME_2
      });

      socket1.triggerEvent('edit_operation', {
        doc_id: DOC_ID,
        op_type: 'insert',
        op_data: { position: 0, text: 'Alice says: ' },
        user_id: USER_ID_1,
        client_op_id: uuidv4(),
        version: 1
      });

      socket2.triggerEvent('edit_operation', {
        doc_id: DOC_ID,
        op_type: 'insert',
        op_data: { position: 0, text: 'Bob says: ' },
        user_id: USER_ID_2,
        client_op_id: uuidv4(),
        version: 1
      });

      const conflictEvents = mockIo.emittedEvents.filter(e => e.event === 'conflict_detected');
      expect(conflictEvents.length).toBeGreaterThan(0);
      
      const conflicts = collaborationService.getActiveConflicts(DOC_ID);
      expect(conflicts.length).toBe(1);
    });

    test('should allow resolving conflict by keeping first operation', () => {
      const socket1 = mockIo.createSocket('socket-alice');
      const socket2 = mockIo.createSocket('socket-bob');

      socket1.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      socket2.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_2,
        user_name: USER_NAME_2
      });

      socket1.triggerEvent('edit_operation', {
        doc_id: DOC_ID,
        op_type: 'insert',
        op_data: { position: 0, text: 'A' },
        user_id: USER_ID_1,
        client_op_id: uuidv4(),
        version: 1
      });

      socket2.triggerEvent('edit_operation', {
        doc_id: DOC_ID,
        op_type: 'insert',
        op_data: { position: 0, text: 'B' },
        user_id: USER_ID_2,
        client_op_id: uuidv4(),
        version: 1
      });

      const conflicts = collaborationService.getActiveConflicts(DOC_ID);
      expect(conflicts.length).toBe(1);
      
      const conflictId = conflicts[0].conflict_id;

      socket1.triggerEvent('resolve_conflict', {
        doc_id: DOC_ID,
        conflict_id: conflictId,
        resolution: 'keep_first',
        user_id: USER_ID_1
      });

      const remainingConflicts = collaborationService.getActiveConflicts(DOC_ID);
      expect(remainingConflicts.length).toBe(0);

      const resolvedEvents = mockIo.emittedEvents.filter(e => e.event === 'conflict_resolved');
      expect(resolvedEvents.length).toBeGreaterThan(0);
    });

    test('should allow resolving conflict by keeping both operations', () => {
      const socket1 = mockIo.createSocket('socket-alice');
      const socket2 = mockIo.createSocket('socket-bob');

      collaborationService.createDocument(
        'keep-both-doc',
        'Keep Both Test',
        'Base',
        USER_ID_1
      );

      socket1.triggerEvent('join_doc', {
        doc_id: 'keep-both-doc',
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      socket2.triggerEvent('join_doc', {
        doc_id: 'keep-both-doc',
        user_id: USER_ID_2,
        user_name: USER_NAME_2
      });

      socket1.triggerEvent('edit_operation', {
        doc_id: 'keep-both-doc',
        op_type: 'insert',
        op_data: { position: 0, text: 'A' },
        user_id: USER_ID_1,
        client_op_id: uuidv4(),
        version: 1
      });

      socket2.triggerEvent('edit_operation', {
        doc_id: 'keep-both-doc',
        op_type: 'insert',
        op_data: { position: 0, text: 'B' },
        user_id: USER_ID_2,
        client_op_id: uuidv4(),
        version: 1
      });

      const conflicts = collaborationService.getActiveConflicts('keep-both-doc');
      expect(conflicts.length).toBe(1);
      
      const conflictId = conflicts[0].conflict_id;

      socket1.triggerEvent('resolve_conflict', {
        doc_id: 'keep-both-doc',
        conflict_id: conflictId,
        resolution: 'keep_both',
        user_id: USER_ID_1
      });

      const doc = collaborationService.getDocument('keep-both-doc');
      expect(doc.content).toContain('A');
      expect(doc.content).toContain('B');
    });
  });

  describe('Offline Cache and Reconnection Sync', () => {
    test('should provide sync response with current document state', () => {
      const socket1 = mockIo.createSocket('socket-alice');

      socket1.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      socket1.triggerEvent('edit_operation', {
        doc_id: DOC_ID,
        op_type: 'insert',
        op_data: { position: 0, text: 'Updated: ' },
        user_id: USER_ID_1,
        client_op_id: uuidv4(),
        version: 1
      });

      socket1.triggerEvent('sync_request', {
        doc_id: DOC_ID,
        last_sync_time: null,
        client_version: 1
      });

      const syncResponses = socket1.emittedEvents.filter(e => e.event === 'sync_response');
      expect(syncResponses.length).toBeGreaterThan(0);
      
      const syncResponse = syncResponses[syncResponses.length - 1];
      expect(syncResponse.data.current_content).toContain('Updated:');
      expect(syncResponse.data.operations.length).toBeGreaterThan(0);
    });

    test('should handle reconnection with pending operations', () => {
      const socket1 = mockIo.createSocket('socket-alice');

      socket1.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      for (let i = 0; i < 3; i++) {
        socket1.triggerEvent('edit_operation', {
          doc_id: DOC_ID,
          op_type: 'insert',
          op_data: { position: 0, text: `${i}-` },
          user_id: USER_ID_1,
          client_op_id: uuidv4(),
          version: 1
        });
      }

      const pendingOps = collaborationService.getPendingOperations(DOC_ID);
      expect(pendingOps.length).toBe(3);

      mockIo.disconnectSocket('socket-alice');
      
      const socketReconnected = mockIo.createSocket('socket-alice-reconnected');
      socketReconnected.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      socketReconnected.triggerEvent('sync_request', {
        doc_id: DOC_ID,
        last_sync_time: null,
        client_version: 1
      });

      const syncResponses = socketReconnected.emittedEvents.filter(e => e.event === 'sync_response');
      expect(syncResponses.length).toBeGreaterThan(0);
      
      const syncResponse = syncResponses[0];
      expect(syncResponse.data.current_content).toBeDefined();
    });
  });

  describe('Operation Transformation in Multi-user Scenarios', () => {
    test('should transform operations when users edit different positions', () => {
      const socket1 = mockIo.createSocket('socket-alice');
      const socket2 = mockIo.createSocket('socket-bob');

      collaborationService.createDocument(
        'transform-test-doc',
        'Transform Test',
        'Hello World',
        USER_ID_1
      );

      socket1.triggerEvent('join_doc', {
        doc_id: 'transform-test-doc',
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      socket2.triggerEvent('join_doc', {
        doc_id: 'transform-test-doc',
        user_id: USER_ID_2,
        user_name: USER_NAME_2
      });

      socket1.triggerEvent('edit_operation', {
        doc_id: 'transform-test-doc',
        op_type: 'insert',
        op_data: { position: 0, text: 'A' },
        user_id: USER_ID_1,
        client_op_id: uuidv4(),
        version: 1
      });

      socket2.triggerEvent('edit_operation', {
        doc_id: 'transform-test-doc',
        op_type: 'insert',
        op_data: { position: 12, text: 'B' },
        user_id: USER_ID_2,
        client_op_id: uuidv4(),
        version: 1
      });

      const doc = collaborationService.getDocument('transform-test-doc');
      expect(doc.content).toContain('A');
      expect(doc.content).toContain('B');
      
      const conflicts = collaborationService.getActiveConflicts('transform-test-doc');
      expect(conflicts.length).toBe(0);
    });

    test('should maintain document consistency after multiple operations', () => {
      const socket1 = mockIo.createSocket('socket-alice');

      collaborationService.createDocument(
        'consistency-test-doc',
        'Consistency Test',
        '',
        USER_ID_1
      );

      socket1.triggerEvent('join_doc', {
        doc_id: 'consistency-test-doc',
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      const operations = [
        { type: 'insert', pos: 0, text: 'H' },
        { type: 'insert', pos: 1, text: 'e' },
        { type: 'insert', pos: 2, text: 'l' },
        { type: 'insert', pos: 3, text: 'l' },
        { type: 'insert', pos: 4, text: 'o' },
      ];

      for (const op of operations) {
        socket1.triggerEvent('edit_operation', {
          doc_id: 'consistency-test-doc',
          op_type: op.type,
          op_data: { position: op.pos, text: op.text },
          user_id: USER_ID_1,
          client_op_id: uuidv4(),
          version: 1
        });
      }

      const doc = collaborationService.getDocument('consistency-test-doc');
      expect(doc.content).toBe('Hello');
    });
  });

  describe('Sync Confirmation and Error Handling', () => {
    test('should emit error when editing without joining', () => {
      const socket1 = mockIo.createSocket('socket-alice');

      socket1.triggerEvent('edit_operation', {
        doc_id: DOC_ID,
        op_type: 'insert',
        op_data: { position: 0, text: 'Test' },
        user_id: USER_ID_1,
        client_op_id: uuidv4(),
        version: 1
      });

      const errorEvents = socket1.emittedEvents.filter(e => e.event === 'error');
      expect(errorEvents.length).toBeGreaterThan(0);
    });

    test('should emit error when document not found', () => {
      const socket1 = mockIo.createSocket('socket-alice');

      socket1.triggerEvent('join_doc', {
        doc_id: 'non-existent-doc',
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      socket1.triggerEvent('edit_operation', {
        doc_id: 'non-existent-doc',
        op_type: 'insert',
        op_data: { position: 0, text: 'Test' },
        user_id: USER_ID_1,
        client_op_id: uuidv4(),
        version: 1
      });

      const errorEvents = socket1.emittedEvents.filter(e => e.event === 'error');
      expect(errorEvents.length).toBeGreaterThan(0);
    });

    test('should track operation timestamps for sync ordering', async () => {
      const socket1 = mockIo.createSocket('socket-alice');

      socket1.triggerEvent('join_doc', {
        doc_id: DOC_ID,
        user_id: USER_ID_1,
        user_name: USER_NAME_1
      });

      const startTime = Date.now();
      
      socket1.triggerEvent('edit_operation', {
        doc_id: DOC_ID,
        op_type: 'insert',
        op_data: { position: 0, text: 'First' },
        user_id: USER_ID_1,
        client_op_id: uuidv4(),
        version: 1
      });

      await new Promise(resolve => setTimeout(resolve, 10));

      socket1.triggerEvent('edit_operation', {
        doc_id: DOC_ID,
        op_type: 'insert',
        op_data: { position: 0, text: 'Second' },
        user_id: USER_ID_1,
        client_op_id: uuidv4(),
        version: 1
      });

      const pendingOps = collaborationService.getPendingOperations(DOC_ID);
      expect(pendingOps.length).toBe(2);
      
      const firstOpTime = new Date(pendingOps[0].op_time).getTime();
      const secondOpTime = new Date(pendingOps[1].op_time).getTime();
      
      expect(secondOpTime).toBeGreaterThanOrEqual(firstOpTime);
    });
  });
});
