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

const CHANNELS = {
  MEDIA: (mediaId) => `media:${mediaId}`,
  UPLOAD: (fileId) => `upload:${fileId}`,
  JOB: (jobId) => `job:${jobId}`,
  REVIEW: (reviewId) => `review:${reviewId}`,
  GLOBAL: 'global'
};

class WebSocketService {
  constructor() {
    this.ws = null;
    this.clientId = null;
    this.isConnected = false;
    this.isReconnecting = false;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 10;
    this.reconnectDelay = 3000;
    this.heartbeatInterval = 30000;
    this.heartbeatTimer = null;
    
    this.eventHandlers = new Map();
    this.subscriptions = new Set();
    
    this.serverUrl = this.getWebSocketUrl();
  }

  getWebSocketUrl() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    return `${protocol}//${host}/ws`;
  }

  connect() {
    return new Promise((resolve, reject) => {
      if (this.isConnected && this.ws && this.ws.readyState === WebSocket.OPEN) {
        resolve(this.clientId);
        return;
      }

      try {
        console.log('[WebSocketService] Connecting to:', this.serverUrl);
        this.ws = new WebSocket(this.serverUrl);
        
        this.ws.onopen = (event) => {
          console.log('[WebSocketService] Connection opened');
          this.isConnected = true;
          this.isReconnecting = false;
          this.reconnectAttempts = 0;
          this.startHeartbeat();
        };
        
        this.ws.onmessage = (event) => {
          try {
            const data = JSON.parse(event.data);
            this.handleMessage(data);
          } catch (error) {
            console.error('[WebSocketService] Failed to parse message:', error);
          }
        };
        
        this.ws.onclose = (event) => {
          console.log('[WebSocketService] Connection closed', event.code, event.reason);
          this.isConnected = false;
          this.stopHeartbeat();
          
          if (!this.isReconnecting && event.code !== 1000) {
            this.attemptReconnect();
          }
          
          this.emit('disconnected', { code: event.code, reason: event.reason });
        };
        
        this.ws.onerror = (error) => {
          console.error('[WebSocketService] Connection error:', error);
          this.emit('error', error);
        };
        
        this.once('connected', (data) => {
          this.clientId = data.clientId;
          this.subscriptions.forEach(channel => {
            this.subscribe(channel);
          });
          resolve(data.clientId);
        });
        
        this.once('error', (error) => {
          reject(error);
        });
        
      } catch (error) {
        console.error('[WebSocketService] Failed to create WebSocket:', error);
        reject(error);
      }
    });
  }

  disconnect() {
    this.stopHeartbeat();
    this.isReconnecting = false;
    
    if (this.ws) {
      if (this.ws.readyState === WebSocket.OPEN) {
        this.ws.close(1000, 'User initiated disconnect');
      }
      this.ws = null;
    }
    
    this.isConnected = false;
    console.log('[WebSocketService] Disconnected');
  }

  attemptReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.error('[WebSocketService] Max reconnect attempts reached');
      this.emit('reconnect_failed', { attempts: this.reconnectAttempts });
      return;
    }
    
    this.isReconnecting = true;
    this.reconnectAttempts++;
    
    const delay = this.reconnectDelay * Math.pow(1.5, this.reconnectAttempts - 1);
    
    console.log(`[WebSocketService] Attempting reconnect ${this.reconnectAttempts}/${this.maxReconnectAttempts} in ${delay}ms`);
    this.emit('reconnecting', { attempt: this.reconnectAttempts, delay });
    
    setTimeout(() => {
      if (this.isReconnecting) {
        this.connect()
          .then(() => {
            console.log('[WebSocketService] Reconnected successfully');
            this.emit('reconnected', { clientId: this.clientId });
          })
          .catch((error) => {
            console.error('[WebSocketService] Reconnect failed:', error);
            this.attemptReconnect();
          });
      }
    }, delay);
  }

  startHeartbeat() {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      if (this.isConnected && this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.send({
          type: MESSAGE_TYPES.HEARTBEAT,
          timestamp: Date.now()
        });
      }
    }, this.heartbeatInterval);
  }

  stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  send(message) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn('[WebSocketService] Cannot send message: WebSocket not open');
      return false;
    }
    
    try {
      const serialized = typeof message === 'string' ? message : JSON.stringify(message);
      this.ws.send(serialized);
      return true;
    } catch (error) {
      console.error('[WebSocketService] Failed to send message:', error);
      return false;
    }
  }

  subscribe(channel) {
    if (!this.subscriptions.has(channel)) {
      this.subscriptions.add(channel);
      
      if (this.isConnected && this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.send({
          type: MESSAGE_TYPES.SUBSCRIBE,
          channel: channel
        });
      }
      
      console.log('[WebSocketService] Subscribed to channel:', channel);
    }
    return this;
  }

  unsubscribe(channel) {
    if (this.subscriptions.has(channel)) {
      this.subscriptions.delete(channel);
      
      if (this.isConnected && this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.send({
          type: MESSAGE_TYPES.UNSUBSCRIBE,
          channel: channel
        });
      }
      
      console.log('[WebSocketService] Unsubscribed from channel:', channel);
    }
    return this;
  }

  handleMessage(data) {
    const { type, data: payload, channel, timestamp } = data;
    
    switch (type) {
      case MESSAGE_TYPES.CONNECTION:
        this.emit('connected', payload);
        break;
        
      case MESSAGE_TYPES.HEARTBEAT:
        this.emit('heartbeat', payload);
        break;
        
      case MESSAGE_TYPES.NOTIFICATION:
        this.emit('notification', { channel, data: payload, timestamp });
        break;
        
      case MESSAGE_TYPES.MEDIA_PROGRESS:
        this.emit('media:progress', payload);
        if (payload.mediaId) {
          this.emit(`media:${payload.mediaId}:progress`, payload);
        }
        break;
        
      case MESSAGE_TYPES.MEDIA_COMPLETED:
        this.emit('media:completed', payload);
        if (payload.mediaId) {
          this.emit(`media:${payload.mediaId}:completed`, payload);
        }
        break;
        
      case MESSAGE_TYPES.MEDIA_FAILED:
        this.emit('media:failed', payload);
        if (payload.mediaId) {
          this.emit(`media:${payload.mediaId}:failed`, payload);
        }
        break;
        
      case MESSAGE_TYPES.JOB_PROGRESS:
        this.emit('job:progress', payload);
        if (payload.jobId) {
          this.emit(`job:${payload.jobId}:progress`, payload);
        }
        break;
        
      case MESSAGE_TYPES.JOB_COMPLETED:
        this.emit('job:completed', payload);
        if (payload.jobId) {
          this.emit(`job:${payload.jobId}:completed`, payload);
        }
        break;
        
      case MESSAGE_TYPES.JOB_FAILED:
        this.emit('job:failed', payload);
        if (payload.jobId) {
          this.emit(`job:${payload.jobId}:failed`, payload);
        }
        break;
        
      case MESSAGE_TYPES.UPLOAD_PROGRESS:
        this.emit('upload:progress', payload);
        if (payload.fileId) {
          this.emit(`upload:${payload.fileId}:progress`, payload);
        }
        break;
        
      case MESSAGE_TYPES.UPLOAD_COMPLETED:
        this.emit('upload:completed', payload);
        if (payload.fileId) {
          this.emit(`upload:${payload.fileId}:completed`, payload);
        }
        break;
        
      case MESSAGE_TYPES.REVIEW_STATUS_UPDATE:
        this.emit('review:status_update', payload);
        if (payload.reviewId) {
          this.emit(`review:${payload.reviewId}:status_update`, payload);
        }
        if (payload.mediaId) {
          this.emit(`media:${payload.mediaId}:review_update`, payload);
        }
        break;
        
      default:
        console.log('[WebSocketService] Unknown message type:', type);
    }
  }

  on(event, handler) {
    if (!this.eventHandlers.has(event)) {
      this.eventHandlers.set(event, []);
    }
    this.eventHandlers.get(event).push(handler);
    return this;
  }

  off(event, handler) {
    if (this.eventHandlers.has(event)) {
      const handlers = this.eventHandlers.get(event);
      const index = handlers.indexOf(handler);
      if (index > -1) {
        handlers.splice(index, 1);
      }
    }
    return this;
  }

  once(event, handler) {
    const onceHandler = (...args) => {
      handler(...args);
      this.off(event, onceHandler);
    };
    this.on(event, onceHandler);
    return this;
  }

  emit(event, ...args) {
    if (this.eventHandlers.has(event)) {
      const handlers = [...this.eventHandlers.get(event)];
      handlers.forEach(handler => {
        try {
          handler(...args);
        } catch (error) {
          console.error(`[WebSocketService] Error in handler for event '${event}':`, error);
        }
      });
    }
    return this;
  }

  subscribeToMedia(mediaId) {
    return this.subscribe(CHANNELS.MEDIA(mediaId));
  }

  unsubscribeFromMedia(mediaId) {
    return this.unsubscribe(CHANNELS.MEDIA(mediaId));
  }

  subscribeToUpload(fileId) {
    return this.subscribe(CHANNELS.UPLOAD(fileId));
  }

  unsubscribeFromUpload(fileId) {
    return this.unsubscribe(CHANNELS.UPLOAD(fileId));
  }

  subscribeToJob(jobId) {
    return this.subscribe(CHANNELS.JOB(jobId));
  }

  unsubscribeFromJob(jobId) {
    return this.unsubscribe(CHANNELS.JOB(jobId));
  }

  subscribeToReview(reviewId) {
    return this.subscribe(CHANNELS.REVIEW(reviewId));
  }

  unsubscribeFromReview(reviewId) {
    return this.unsubscribe(CHANNELS.REVIEW(reviewId));
  }

  onMediaProgress(mediaId, handler) {
    return this.on(`media:${mediaId}:progress`, handler);
  }

  onMediaCompleted(mediaId, handler) {
    return this.on(`media:${mediaId}:completed`, handler);
  }

  onMediaFailed(mediaId, handler) {
    return this.on(`media:${mediaId}:failed`, handler);
  }

  onUploadProgress(fileId, handler) {
    return this.on(`upload:${fileId}:progress`, handler);
  }

  onUploadCompleted(fileId, handler) {
    return this.on(`upload:${fileId}:completed`, handler);
  }

  onJobProgress(jobId, handler) {
    return this.on(`job:${jobId}:progress`, handler);
  }

  onJobCompleted(jobId, handler) {
    return this.on(`job:${jobId}:completed`, handler);
  }

  onJobFailed(jobId, handler) {
    return this.on(`job:${jobId}:failed`, handler);
  }
}

const websocketService = new WebSocketService();

const initializeWebSocket = async () => {
  try {
    await websocketService.connect();
    console.log('[WebSocket] Service initialized successfully');
    return websocketService;
  } catch (error) {
    console.error('[WebSocket] Failed to initialize:', error);
    return null;
  }
};

module.exports = {
  websocketService,
  WebSocketService,
  MESSAGE_TYPES,
  CHANNELS,
  initializeWebSocket
};
