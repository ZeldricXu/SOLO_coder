const WebSocket = require('ws');
const { v4: uuidv4 } = require('uuid');

const MESSAGE_TYPES = {
  CONNECTION: 'connection',
  DISCONNECTION: 'disconnection',
  HEARTBEAT: 'heartbeat',
  SUBSCRIBE: 'subscribe',
  UNSUBSCRIBE: 'unsubscribe',
  NOTIFICATION: 'notification',
  MEDIA_PROGRESS: 'media_progress',
  MEDIA_COMPLETED: 'media_completed',
  MEDIA_FAILED: 'media_failed',
  JOB_PROGRESS: 'job_progress',
  JOB_COMPLETED: 'job_completed',
  JOB_FAILED: 'job_failed',
  UPLOAD_PROGRESS: 'upload_progress',
  UPLOAD_COMPLETED: 'upload_completed',
  REVIEW_STATUS_UPDATE: 'review_status_update'
};

class WebSocketService {
  constructor() {
    this.wss = null;
    this.clients = new Map();
    this.channels = new Map();
    this.heartbeatInterval = 30000;
    this.heartbeatTimer = null;
    this.isRunning = false;
  }

  initialize(server) {
    this.wss = new WebSocket.Server({ server, path: '/ws' });
    
    this.wss.on('connection', (ws, req) => {
      const clientId = this.generateClientId();
      
      const clientInfo = {
        id: clientId,
        ws: ws,
        subscriptions: new Set(),
        lastActive: Date.now(),
        isAlive: true
      };
      
      this.clients.set(clientId, clientInfo);
      
      console.log(`[WebSocket] Client connected: ${clientId}`);
      
      this.sendToClient(clientId, {
        type: MESSAGE_TYPES.CONNECTION,
        data: {
          clientId: clientId,
          connected: true,
          timestamp: Date.now()
        }
      });
      
      ws.on('message', (message) => {
        this.handleMessage(clientId, message);
      });
      
      ws.on('close', () => {
        console.log(`[WebSocket] Client disconnected: ${clientId}`);
        this.cleanupClient(clientId);
      });
      
      ws.on('error', (error) => {
        console.error(`[WebSocket] Client error: ${clientId}`, error);
        this.cleanupClient(clientId);
      });
      
      ws.on('pong', () => {
        const client = this.clients.get(clientId);
        if (client) {
          client.isAlive = true;
          client.lastActive = Date.now();
        }
      });
    });
    
    this.startHeartbeat();
    this.isRunning = true;
    console.log('[WebSocket] Service initialized');
    
    return this;
  }

  generateClientId() {
    return `client_${Date.now()}_${uuidv4().substring(0, 8)}`;
  }

  handleMessage(clientId, message) {
    try {
      const data = typeof message === 'string' ? JSON.parse(message) : message;
      const client = this.clients.get(clientId);
      
      if (!client) return;
      
      switch (data.type) {
        case MESSAGE_TYPES.SUBSCRIBE:
          this.subscribe(clientId, data.channel);
          break;
          
        case MESSAGE_TYPES.UNSUBSCRIBE:
          this.unsubscribe(clientId, data.channel);
          break;
          
        case MESSAGE_TYPES.HEARTBEAT:
          client.lastActive = Date.now();
          client.isAlive = true;
          this.sendToClient(clientId, {
            type: MESSAGE_TYPES.HEARTBEAT,
            data: { timestamp: Date.now() }
          });
          break;
          
        default:
          console.warn(`[WebSocket] Unknown message type: ${data.type}`);
      }
    } catch (error) {
      console.error('[WebSocket] Error handling message:', error);
    }
  }

  subscribe(clientId, channel) {
    const client = this.clients.get(clientId);
    if (!client) return false;
    
    if (!this.channels.has(channel)) {
      this.channels.set(channel, new Set());
    }
    
    const channelClients = this.channels.get(channel);
    channelClients.add(clientId);
    client.subscriptions.add(channel);
    
    console.log(`[WebSocket] Client ${clientId} subscribed to channel: ${channel}`);
    
    this.sendToClient(clientId, {
      type: MESSAGE_TYPES.SUBSCRIBE,
      data: { channel, subscribed: true, timestamp: Date.now() }
    });
    
    return true;
  }

  unsubscribe(clientId, channel) {
    const client = this.clients.get(clientId);
    if (!client) return false;
    
    const channelClients = this.channels.get(channel);
    if (channelClients) {
      channelClients.delete(clientId);
      if (channelClients.size === 0) {
        this.channels.delete(channel);
      }
    }
    
    client.subscriptions.delete(channel);
    
    console.log(`[WebSocket] Client ${clientId} unsubscribed from channel: ${channel}`);
    
    this.sendToClient(clientId, {
      type: MESSAGE_TYPES.UNSUBSCRIBE,
      data: { channel, unsubscribed: true, timestamp: Date.now() }
    });
    
    return true;
  }

  publish(channel, data) {
    const channelClients = this.channels.get(channel);
    if (!channelClients || channelClients.size === 0) {
      return 0;
    }
    
    const message = {
      type: MESSAGE_TYPES.NOTIFICATION,
      channel: channel,
      data: data,
      timestamp: Date.now()
    };
    
    let publishedCount = 0;
    channelClients.forEach((clientId) => {
      if (this.sendToClient(clientId, message)) {
        publishedCount++;
      }
    });
    
    console.log(`[WebSocket] Published to channel: ${channel}, clients: ${publishedCount}`);
    
    return publishedCount;
  }

  sendToClient(clientId, message) {
    const client = this.clients.get(clientId);
    if (!client || !client.ws || client.ws.readyState !== WebSocket.OPEN) {
      return false;
    }
    
    try {
      client.ws.send(JSON.stringify(message));
      return true;
    } catch (error) {
      console.error(`[WebSocket] Failed to send to client ${clientId}:`, error);
      return false;
    }
  }

  broadcast(message) {
    let sentCount = 0;
    this.clients.forEach((client, clientId) => {
      if (this.sendToClient(clientId, message)) {
        sentCount++;
      }
    });
    return sentCount;
  }

  notifyMediaProgress(mediaId, progress, status) {
    const channel = `media:${mediaId}`;
    return this.publish(channel, {
      type: MESSAGE_TYPES.MEDIA_PROGRESS,
      mediaId: mediaId,
      progress: progress,
      status: status,
      timestamp: Date.now()
    });
  }

  notifyMediaCompleted(mediaId, metadata, thumbnailUrl) {
    const channel = `media:${mediaId}`;
    return this.publish(channel, {
      type: MESSAGE_TYPES.MEDIA_COMPLETED,
      mediaId: mediaId,
      metadata: metadata,
      thumbnailUrl: thumbnailUrl,
      status: 'pending_review',
      timestamp: Date.now()
    });
  }

  notifyMediaFailed(mediaId, error) {
    const channel = `media:${mediaId}`;
    return this.publish(channel, {
      type: MESSAGE_TYPES.MEDIA_FAILED,
      mediaId: mediaId,
      error: error,
      status: 'failed',
      timestamp: Date.now()
    });
  }

  notifyUploadProgress(fileId, progress, uploadedChunks, totalChunks) {
    const channel = `upload:${fileId}`;
    return this.publish(channel, {
      type: MESSAGE_TYPES.UPLOAD_PROGRESS,
      fileId: fileId,
      progress: progress,
      uploadedChunks: uploadedChunks,
      totalChunks: totalChunks,
      timestamp: Date.now()
    });
  }

  notifyUploadCompleted(fileId, mediaId) {
    const channel = `upload:${fileId}`;
    return this.publish(channel, {
      type: MESSAGE_TYPES.UPLOAD_COMPLETED,
      fileId: fileId,
      mediaId: mediaId,
      status: 'processing',
      timestamp: Date.now()
    });
  }

  notifyReviewStatusUpdate(reviewId, mediaId, status, comment) {
    const channel = `review:${reviewId}`;
    this.publish(channel, {
      type: MESSAGE_TYPES.REVIEW_STATUS_UPDATE,
      reviewId: reviewId,
      mediaId: mediaId,
      status: status,
      comment: comment,
      timestamp: Date.now()
    });
    
    const mediaChannel = `media:${mediaId}`;
    return this.publish(mediaChannel, {
      type: MESSAGE_TYPES.REVIEW_STATUS_UPDATE,
      reviewId: reviewId,
      mediaId: mediaId,
      status: status,
      timestamp: Date.now()
    });
  }

  notifyJobProgress(jobId, jobType, progress, status) {
    const channel = `job:${jobId}`;
    return this.publish(channel, {
      type: MESSAGE_TYPES.JOB_PROGRESS,
      jobId: jobId,
      jobType: jobType,
      progress: progress,
      status: status,
      timestamp: Date.now()
    });
  }

  notifyJobCompleted(jobId, jobType, result) {
    const channel = `job:${jobId}`;
    return this.publish(channel, {
      type: MESSAGE_TYPES.JOB_COMPLETED,
      jobId: jobId,
      jobType: jobType,
      result: result,
      status: 'completed',
      timestamp: Date.now()
    });
  }

  notifyJobFailed(jobId, jobType, error) {
    const channel = `job:${jobId}`;
    return this.publish(channel, {
      type: MESSAGE_TYPES.JOB_FAILED,
      jobId: jobId,
      jobType: jobType,
      error: error,
      status: 'failed',
      timestamp: Date.now()
    });
  }

  startHeartbeat() {
    this.heartbeatTimer = setInterval(() => {
      this.clients.forEach((client, clientId) => {
        if (!client.isAlive) {
          console.log(`[WebSocket] Client ${clientId} heartbeat timeout, terminating`);
          this.cleanupClient(clientId);
          return;
        }
        
        client.isAlive = false;
        try {
          if (client.ws && client.ws.readyState === WebSocket.OPEN) {
            client.ws.ping();
          }
        } catch (error) {
          console.error(`[WebSocket] Heartbeat error for client ${clientId}:`, error);
          this.cleanupClient(clientId);
        }
      });
    }, this.heartbeatInterval);
  }

  stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  cleanupClient(clientId) {
    const client = this.clients.get(clientId);
    if (!client) return;
    
    client.subscriptions.forEach((channel) => {
      const channelClients = this.channels.get(channel);
      if (channelClients) {
        channelClients.delete(clientId);
        if (channelClients.size === 0) {
          this.channels.delete(channel);
        }
      }
    });
    
    try {
      if (client.ws) {
        client.ws.removeAllListeners();
        client.ws.terminate();
      }
    } catch (error) {
      console.error(`[WebSocket] Error cleaning up client ${clientId}:`, error);
    }
    
    this.clients.delete(clientId);
    console.log(`[WebSocket] Client cleaned up: ${clientId}`);
  }

  shutdown() {
    this.isRunning = false;
    this.stopHeartbeat();
    
    this.clients.forEach((client, clientId) => {
      this.cleanupClient(clientId);
    });
    
    if (this.wss) {
      this.wss.close(() => {
        console.log('[WebSocket] Service shut down');
      });
    }
    
    return this;
  }

  getStats() {
    const channelStats = {};
    this.channels.forEach((clients, channel) => {
      channelStats[channel] = clients.size;
    };
    
    return {
      connectedClients: this.clients.size,
      activeChannels: this.channels.size,
      channelStats: channelStats,
      isRunning: this.isRunning
    };
  }
}

const websocketService = new WebSocketService();

const getChannels = {
  MEDIA: (mediaId) => `media:${mediaId}`,
  UPLOAD: (fileId) => `upload:${fileId}`,
  JOB: (jobId) => `job:${jobId}`,
  REVIEW: (reviewId) => `review:${reviewId}`,
  GLOBAL: 'global'
};

module.exports = {
  websocketService,
  WebSocketService,
  MESSAGE_TYPES,
  getChannels
};
