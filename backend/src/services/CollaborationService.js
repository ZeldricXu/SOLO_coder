const Operation = require('../models/Operation');
const Document = require('../models/Document');
const { v4: uuidv4 } = require('uuid');
const _ = require('lodash');

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
        const overlapStart = Math.max(deleteStart, concurrentStart);
        const overlapEnd = Math.min(deleteEnd, concurrentEnd);
        const overlapLength = overlapEnd - overlapStart;

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

class CollaborationService {
  constructor() {
    this.activeSessions = new Map();
    this.docVersions = new Map();
    this.ot = new OperationalTransform();
    this.pendingOperations = new Map();
    this.conflictResolution = new Map();
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

  async handleJoinDoc(socket, { doc_id, user_id, user_name }) {
    const roomId = `doc_${doc_id}`;
    
    socket.join(roomId);
    
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
    
    const doc = await Document.findOne({ doc_id });
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

    const operation = new Operation({
      op_id: finalOp.op_id,
      doc_id: finalOp.doc_id,
      user_id: finalOp.user_id,
      op_type: finalOp.op_type,
      op_data: finalOp.op_data,
      op_time: finalOp.op_time,
      original_op_data: op_data
    });
    
    await operation.save();

    pendingOps.push(finalOp);
    this.pendingOperations.set(doc_id, pendingOps);

    const oldContent = doc.content;
    doc.content = this.ot.applyOperation(doc.content, finalOp);
    
    if (finalOp.op_type === 'replace' && finalOp.op_data.title) {
      doc.title = finalOp.op_data.title;
    }
    
    doc.last_edited_by = user_id;
    doc.last_edited_at = new Date();
    await doc.save();
    
    const roomId = `doc_${doc_id}`;
    
    this.io.to(roomId).emit('sync_broadcast', {
      op_id: finalOp.op_id,
      client_op_id,
      doc_id,
      user_id,
      op_type: finalOp.op_type,
      op_data: finalOp.op_data,
      original_op_data: op_data,
      op_time: operation.op_time.toISOString(),
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
    const doc = await Document.findOne({ doc_id });
    
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
    } else if (resolution === 'manual' && resolution.content) {
      resolvedContent = resolution.content;
    }

    doc.content = resolvedContent;
    doc.last_edited_by = user_id;
    doc.last_edited_at = new Date();
    await doc.save();

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
    const query = { doc_id };
    if (last_sync_time) {
      query.op_time = { $gt: new Date(last_sync_time) };
    }
    
    const operations = await Operation.find(query)
      .sort({ op_time: 1 })
      .exec();
    
    const doc = await Document.findOne({ doc_id });
    
    const conflicts = this.conflictResolution.get(doc_id) || [];
    
    socket.emit('sync_response', {
      doc_id,
      current_content: doc?.content || '',
      current_title: doc?.title || '',
      current_version: doc?.current_version || 1,
      operations: operations.map(op => ({
        op_id: op.op_id,
        user_id: op.user_id,
        op_type: op.op_type,
        op_data: op.op_data,
        original_op_data: op.original_op_data,
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

module.exports = new CollaborationService();
