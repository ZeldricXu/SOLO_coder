import { v4 as uuidv4 } from 'uuid';
import {
  GameEvent,
  SDKConfig,
  SDKOptions,
  EventType,
  CachedEvent,
  EventReportRequest,
  EventReportResponse,
  HeartbeatPayload,
  EventConfigItem,
  RemoteSDKConfig,
  ConfigChangedCallback,
  ConfigStatus,
  ConfigFetchResult,
  CachedConfigMetadata,
  ConfigFetchErrorCallback,
  ConfigRecoveryCallback
} from './types';

type ConfigErrorType = 'network' | 'server' | 'timeout' | 'parse' | 'unknown';

class GameStatsSDK {
  private config: SDKConfig;
  private playerId: string | null = null;
  private eventCache: CachedEvent[] = [];
  private flushTimer: number | null = null;
  private heartbeatTimer: number | null = null;
  private configRefreshTimer: number | null = null;
  private configRetryTimer: number | null = null;
  private isInitialized: boolean = false;
  private isOnline: boolean = true;

  private remoteConfig: RemoteSDKConfig | null = null;
  private enabledEventTypes: Set<string> = new Set();
  private configCallbacks: ConfigChangedCallback[] = [];
  private configErrorCallbacks: ConfigFetchErrorCallback[] = [];
  private configRecoveryCallbacks: ConfigRecoveryCallback[] = [];

  private consecutiveConfigFailures: number = 0;
  private lastConfigFetchSuccess: boolean = true;
  private lastConfigFetchError: string | null = null;
  private isUsingCachedConfig: boolean = false;
  private isUsingDefaultConfig: boolean = false;

  private configFetchTimeoutMs: number = 10000;
  private configRetryBaseDelayMs: number = 1000;
  private configMaxRetryDelayMs: number = 60000;
  private configMaxRetries: number = -1;

  private static instance: GameStatsSDK | null = null;

  private constructor(options: SDKOptions) {
    this.config = {
      game_id: options.game_id,
      server_id: options.server_id,
      endpoint: options.endpoint || 'http://localhost:8080/api/v1/events/report',
      config_endpoint: options.config_endpoint || this.getDefaultConfigEndpoint(options.endpoint),
      batch_size: options.batch_size || 50,
      flush_interval: options.flush_interval || 5000,
      max_retries: options.max_retries || 3,
      heartbeat_interval: options.heartbeat_interval || 30000,
      enable_heartbeat: options.enable_heartbeat !== false,
      debug: options.debug || false,
      enable_dynamic_config: options.enable_dynamic_config !== false,
      config_refresh_interval: options.config_refresh_interval || 300000,
      config_fetch_timeout_ms: options.config_fetch_timeout_ms || 10000,
      config_retry_base_delay_ms: options.config_retry_base_delay_ms || 1000,
      config_max_retry_delay_ms: options.config_max_retry_delay_ms || 60000,
      config_max_retries: options.config_max_retries !== undefined ? options.config_max_retries : -1
    };

    this.configFetchTimeoutMs = this.config.config_fetch_timeout_ms || 10000;
    this.configRetryBaseDelayMs = this.config.config_retry_base_delay_ms || 1000;
    this.configMaxRetryDelayMs = this.config.config_max_retry_delay_ms || 60000;
    this.configMaxRetries = this.config.config_max_retries !== undefined ? this.config.config_max_retries : -1;

    this.loadCachedEvents();
    this.loadCachedConfigWithFallback();
    this.setupNetworkListener();
  }

  private getDefaultConfigEndpoint(endpoint?: string): string {
    if (endpoint) {
      return endpoint.replace('/events/report', '/config/sdk');
    }
    return 'http://localhost:8080/api/v1/config/sdk';
  }

  public static getInstance(options?: SDKOptions): GameStatsSDK {
    if (!GameStatsSDK.instance && options) {
      GameStatsSDK.instance = new GameStatsSDK(options);
    }
    
    if (!GameStatsSDK.instance) {
      throw new Error('SDK not initialized. Please call initialize() first.');
    }
    
    return GameStatsSDK.instance;
  }

  public static initialize(options: SDKOptions): GameStatsSDK {
    const sdk = GameStatsSDK.getInstance(options);
    sdk.init();
    return sdk;
  }

  private async init(): Promise<void> {
    if (this.isInitialized) {
      this.log('SDK already initialized');
      return;
    }

    if (this.config.enable_dynamic_config) {
      if (!this.remoteConfig) {
        this.log('No cached config found, loading default config first');
        this.applyDefaultConfig();
        this.isUsingDefaultConfig = true;
      }

      const initialFetchResult = await this.attemptConfigFetchWithRetry();
      
      if (initialFetchResult.success && initialFetchResult.config) {
        this.log('Initial config fetch successful');
        this.remoteConfig = initialFetchResult.config;
        this.applyRemoteConfig(this.remoteConfig);
        this.saveCachedConfig(this.remoteConfig);
        this.isUsingCachedConfig = false;
        this.isUsingDefaultConfig = false;
        this.consecutiveConfigFailures = 0;
        this.lastConfigFetchSuccess = true;
        this.lastConfigFetchError = null;
      } else {
        this.log('Initial config fetch failed, using cached/default config');
        if (!this.remoteConfig) {
          this.applyDefaultConfig();
          this.isUsingDefaultConfig = true;
        }
      }
      
      this.startConfigRefreshTimer();
    }

    this.startFlushTimer();
    
    if (this.config.enable_heartbeat) {
      this.startHeartbeat();
    }

    this.isInitialized = true;
    this.log('SDK initialized successfully');
    this.log(`Config status: usingCached=${this.isUsingCachedConfig}, usingDefault=${this.isUsingDefaultConfig}`);
  }

  private log(message: string, data?: any): void {
    if (this.config.debug) {
      console.log(`[GameStatsSDK] ${new Date().toISOString()} - ${message}`, data || '');
    }
  }

  public setPlayerId(playerId: string): void {
    this.playerId = playerId;
    this.log(`Player ID set: ${playerId}`);
  }

  public getPlayerId(): string | null {
    return this.playerId;
  }

  public getEnabledEventTypes(): string[] {
    return Array.from(this.enabledEventTypes);
  }

  public isEventTypeEnabled(eventType: string): boolean {
    if (!this.config.enable_dynamic_config) {
      return true;
    }
    return this.enabledEventTypes.has(eventType);
  }

  public getRemoteConfig(): RemoteSDKConfig | null {
    return this.remoteConfig;
  }

  public getConfigStatus(): ConfigStatus {
    return {
      currentConfig: this.remoteConfig ? { ...this.remoteConfig } : null,
      metadata: this.getConfigMetadata(),
      isUsingCached: this.isUsingCachedConfig,
      isUsingDefault: this.isUsingDefaultConfig,
      lastFetchSuccess: this.lastConfigFetchSuccess,
      lastFetchError: this.lastConfigFetchError || undefined,
      consecutiveFailures: this.consecutiveConfigFailures
    };
  }

  private getConfigMetadata(): CachedConfigMetadata | null {
    if (!this.remoteConfig) {
      return null;
    }
    
    return {
      version: this.remoteConfig.version,
      config_hash: this.remoteConfig.config_hash,
      cached_at: Date.now(),
      expires_at: Date.now() + (this.remoteConfig.ttl_seconds || 3600) * 1000,
      source: this.isUsingDefaultConfig ? 'default' : (this.isUsingCachedConfig ? 'cached' : 'remote'),
      fetch_attempts: this.consecutiveConfigFailures,
      last_fetch_error: this.lastConfigFetchError || undefined
    };
  }

  public onConfigChanged(callback: ConfigChangedCallback): void {
    this.configCallbacks.push(callback);
  }

  public onConfigFetchError(callback: ConfigFetchErrorCallback): void {
    this.configErrorCallbacks.push(callback);
  }

  public onConfigRecovered(callback: ConfigRecoveryCallback): void {
    this.configRecoveryCallbacks.push(callback);
  }

  public async refreshConfig(): Promise<RemoteSDKConfig> {
    const oldConfig = this.remoteConfig;
    const result = await this.attemptConfigFetchWithRetry();
    
    if (result.success && result.config) {
      const newConfig = result.config;
      
      const wasRecovering = this.consecutiveConfigFailures > 0;
      const previousFailures = this.consecutiveConfigFailures;
      
      this.remoteConfig = newConfig;
      this.applyRemoteConfig(newConfig);
      this.saveCachedConfig(newConfig);
      this.isUsingCachedConfig = false;
      this.isUsingDefaultConfig = false;
      this.consecutiveConfigFailures = 0;
      this.lastConfigFetchSuccess = true;
      this.lastConfigFetchError = null;
      
      if (oldConfig && oldConfig.config_hash !== newConfig.config_hash) {
        this.log('Config changed, notifying callbacks');
        this.configCallbacks.forEach(callback => {
          try {
            callback(oldConfig, newConfig);
          } catch (error) {
            console.error('Config callback error:', error);
          }
        });
      }
      
      if (wasRecovering) {
        this.log(`Config recovered after ${previousFailures} failures`);
        this.configRecoveryCallbacks.forEach(callback => {
          try {
            callback(newConfig, previousFailures);
          } catch (error) {
            console.error('Config recovery callback error:', error);
          }
        });
      }
      
      return newConfig;
    } else {
      this.log('Config refresh failed, continuing with existing config');
      if (!this.remoteConfig) {
        this.applyDefaultConfig();
        this.isUsingDefaultConfig = true;
      }
      return this.remoteConfig!;
    }
  }

  private async loadRemoteConfig(): Promise<RemoteSDKConfig> {
    return this.refreshConfig();
  }

  private getDefaultConfig(): RemoteSDKConfig {
    return {
      version: '1.0.0',
      game_id: this.config.game_id,
      config_hash: 'default-' + Date.now(),
      last_updated: new Date().toISOString(),
      event_configs: [
        { event_type: 'login', event_name: '玩家登录', enabled: true, required_fields: {}, optional_fields: {} },
        { event_type: 'logout', event_name: '玩家登出', enabled: true, required_fields: {}, optional_fields: {} },
        { event_type: 'payment', event_name: '支付事件', enabled: true, required_fields: {}, optional_fields: {} },
        { event_type: 'heartbeat', event_name: '心跳事件', enabled: true, required_fields: {}, optional_fields: {} },
        { event_type: 'level_up', event_name: '等级提升', enabled: true, required_fields: {}, optional_fields: {} },
        { event_type: 'quest_complete', event_name: '任务完成', enabled: true, required_fields: {}, optional_fields: {} },
        { event_type: 'item_purchase', event_name: '物品购买', enabled: true, required_fields: {}, optional_fields: {} },
        { event_type: 'social_interaction', event_name: '社交互动', enabled: true, required_fields: {}, optional_fields: {} },
        { event_type: 'game_start', event_name: '游戏开始', enabled: true, required_fields: {}, optional_fields: {} },
        { event_type: 'game_end', event_name: '游戏结束', enabled: true, required_fields: {}, optional_fields: {} }
      ],
      sdk_settings: {
        batch_size: this.config.batch_size,
        flush_interval_ms: this.config.flush_interval,
        max_retries: this.config.max_retries,
        heartbeat_interval_ms: this.config.heartbeat_interval,
        enable_heartbeat: this.config.enable_heartbeat,
        enable_local_cache: true,
        enable_config_fallback: true
      },
      ttl_seconds: 3600
    };
  }

  private applyDefaultConfig(): void {
    const defaultConfig = this.getDefaultConfig();
    this.remoteConfig = defaultConfig;
    this.applyRemoteConfig(defaultConfig);
    this.log('Applied default config');
  }

  private applyRemoteConfig(config: RemoteSDKConfig): void {
    this.enabledEventTypes.clear();
    
    if (config.event_configs) {
      config.event_configs.forEach(eventConfig => {
        if (eventConfig.enabled) {
          this.enabledEventTypes.add(eventConfig.event_type);
          this.enabledEventTypes.add(eventConfig.event_type.toLowerCase());
        }
      });
    }

    if (config.sdk_settings) {
      if (config.sdk_settings.batch_size && config.sdk_settings.batch_size > 0) {
        this.config.batch_size = config.sdk_settings.batch_size;
      }
      if (config.sdk_settings.flush_interval_ms && config.sdk_settings.flush_interval_ms > 0) {
        this.config.flush_interval = config.sdk_settings.flush_interval_ms;
      }
      if (config.sdk_settings.max_retries && config.sdk_settings.max_retries >= 0) {
        this.config.max_retries = config.sdk_settings.max_retries;
      }
      if (config.sdk_settings.heartbeat_interval_ms && config.sdk_settings.heartbeat_interval_ms > 0) {
        this.config.heartbeat_interval = config.sdk_settings.heartbeat_interval_ms;
      }
      if (config.sdk_settings.enable_heartbeat !== undefined) {
        this.config.enable_heartbeat = config.sdk_settings.enable_heartbeat;
      }
    }

    this.log(`Applied remote config. Enabled events: ${Array.from(this.enabledEventTypes).join(', ')}`);
  }

  private saveCachedConfig(config: RemoteSDKConfig): void {
    try {
      const storageKey = `gamestats_config_${this.config.game_id}_${this.config.server_id}`;
      localStorage.setItem(storageKey, JSON.stringify(config));
      
      const metadataKey = `gamestats_config_meta_${this.config.game_id}_${this.config.server_id}`;
      const metadata: CachedConfigMetadata = {
        version: config.version,
        config_hash: config.config_hash,
        cached_at: Date.now(),
        expires_at: Date.now() + (config.ttl_seconds || 3600) * 1000,
        source: 'remote',
        fetch_attempts: 0
      };
      localStorage.setItem(metadataKey, JSON.stringify(metadata));
      
      this.log('Saved config to localStorage');
    } catch (error) {
      this.log(`Failed to save config to localStorage: ${(error as Error).message}`);
    }
  }

  private loadCachedConfigWithFallback(): void {
    try {
      const storedConfig = localStorage.getItem(
        `gamestats_config_${this.config.game_id}_${this.config.server_id}`
      );
      
      if (storedConfig) {
        const config: RemoteSDKConfig = JSON.parse(storedConfig);
        
        const metadataKey = `gamestats_config_meta_${this.config.game_id}_${this.config.server_id}`;
        const storedMeta = localStorage.getItem(metadataKey);
        
        let isExpired = false;
        if (storedMeta) {
          const metadata: CachedConfigMetadata = JSON.parse(storedMeta);
          isExpired = Date.now() > metadata.expires_at;
        }
        
        if (isExpired) {
          this.log('Cached config is expired, but will be used as fallback');
        } else {
          this.log('Loaded valid cached config from localStorage');
        }
        
        this.remoteConfig = config;
        this.applyRemoteConfig(config);
        this.isUsingCachedConfig = true;
        this.isUsingDefaultConfig = false;
        
      } else {
        this.log('No cached config found in localStorage');
        this.applyDefaultConfig();
        this.isUsingDefaultConfig = true;
      }
    } catch (error) {
      this.log(`Failed to load config from localStorage: ${(error as Error).message}`);
      this.applyDefaultConfig();
      this.isUsingDefaultConfig = true;
    }
  }

  private loadCachedConfig(): void {
    this.loadCachedConfigWithFallback();
  }

  private async fetchConfigOnce(): Promise<ConfigFetchResult> {
    const configUrl = `${this.config.config_endpoint}?game_id=${encodeURIComponent(this.config.game_id)}`;
    
    this.log(`Fetching remote config from: ${configUrl}`);

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), this.configFetchTimeoutMs);
      
      try {
        const response = await fetch(configUrl, {
          method: 'GET',
          headers: {
            'Content-Type': 'application/json'
          },
          signal: controller.signal
        });

        clearTimeout(timeoutId);

        if (!response.ok) {
          const errorType: ConfigErrorType = response.status >= 500 ? 'server' : 
            (response.status === 408 || response.status === 504 ? 'timeout' : 'network');
          
          this.log(`Config fetch failed with status: ${response.status}`);
          
          return {
            success: false,
            error: `HTTP error! status: ${response.status}`,
            errorType: errorType,
            responseStatus: response.status,
            timestamp: Date.now()
          };
        }

        const result = await response.json();
        const config: RemoteSDKConfig = result.code === 200 ? result.data : result;

        this.log('Remote config loaded successfully', {
          version: config.version,
          configHash: config.config_hash,
          eventCount: config.event_configs?.length || 0
        });

        return {
          success: true,
          config: config,
          timestamp: Date.now()
        };
      } finally {
        clearTimeout(timeoutId);
      }

    } catch (error: any) {
      let errorType: ConfigErrorType = 'unknown';
      let errorMessage = 'Unknown error';
      
      if (error.name === 'AbortError') {
        errorType = 'timeout';
        errorMessage = `Request timeout after ${this.configFetchTimeoutMs}ms`;
      } else if (error.name === 'TypeError' && error.message.includes('fetch')) {
        errorType = 'network';
        errorMessage = 'Network error - unable to connect to server';
      } else if (error instanceof SyntaxError) {
        errorType = 'parse';
        errorMessage = 'Failed to parse server response';
      } else {
        errorMessage = error.message || String(error);
      }
      
      this.log(`Config fetch failed: ${errorMessage}, type: ${errorType}`);
      
      return {
        success: false,
        error: errorMessage,
        errorType: errorType,
        timestamp: Date.now()
      };
    }
  }

  private calculateRetryDelay(attempt: number): number {
    const exponentialDelay = this.configRetryBaseDelayMs * Math.pow(2, attempt);
    const jitter = exponentialDelay * 0.1 * (Math.random() * 2 - 1);
    const delayWithJitter = Math.max(this.configRetryBaseDelayMs, Math.min(
      this.configMaxRetryDelayMs,
      exponentialDelay + jitter
    ));
    
    return Math.floor(delayWithJitter);
  }

  private async attemptConfigFetchWithRetry(): Promise<ConfigFetchResult> {
    let attempt = 0;
    
    while (true) {
      const result = await this.fetchConfigOnce();
      
      if (result.success) {
        return result;
      }
      
      this.consecutiveConfigFailures++;
      this.lastConfigFetchSuccess = false;
      this.lastConfigFetchError = result.error || 'Unknown error';
      
      this.log(`Config fetch attempt ${this.consecutiveConfigFailures} failed: ${result.error}`);
      
      this.configErrorCallbacks.forEach(callback => {
        try {
          callback(result.error || 'Unknown error', result.errorType || 'unknown', this.consecutiveConfigFailures);
        } catch (error) {
          console.error('Config error callback error:', error);
        }
      });
      
      if (this.configMaxRetries >= 0 && this.consecutiveConfigFailures >= this.configMaxRetries) {
        this.log(`Max retries (${this.configMaxRetries}) reached, stopping fetch attempts`);
        return result;
      }
      
      const delay = this.calculateRetryDelay(this.consecutiveConfigFailures - 1);
      this.log(`Waiting ${delay}ms before next config fetch attempt...`);
      
      await this.delay(delay);
    }
  }

  private delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  public validateEvent(eventType: string, eventData: Record<string, any>): { valid: boolean; errors: string[] } {
    const errors: string[] = [];
    
    if (this.config.enable_dynamic_config && !this.isEventTypeEnabled(eventType)) {
      errors.push(`Event type '${eventType}' is disabled in configuration`);
      return { valid: false, errors };
    }

    if (!this.remoteConfig || !this.remoteConfig.event_configs) {
      return { valid: true, errors };
    }

    const eventConfig = this.remoteConfig.event_configs.find(
      ec => ec.event_type === eventType || ec.event_type.toLowerCase() === eventType.toLowerCase()
    );

    if (!eventConfig) {
      return { valid: true, errors };
    }

    if (eventConfig.required_fields) {
      for (const field in eventConfig.required_fields) {
        if (eventData[field] === undefined || eventData[field] === null) {
          errors.push(`Required field '${field}' (${eventConfig.required_fields[field]}) is missing`);
        }
      }
    }

    return { valid: errors.length === 0, errors };
  }

  public trackEvent(
    eventType: EventType | string,
    eventData: Record<string, any> = {},
    customEventTime?: string
  ): string | null {
    if (!this.playerId) {
      this.log('Warning: Player ID not set. Please call setPlayerId() before tracking events.');
    }

    const validation = this.validateEvent(eventType, eventData);
    
    if (this.config.enable_dynamic_config && !validation.valid) {
      this.log(`Event '${eventType}' is disabled or invalid: ${validation.errors.join(', ')}`);
      return null;
    }

    if (validation.errors.length > 0) {
      this.log(`Event '${eventType}' has validation warnings: ${validation.errors.join(', ')}`);
    }

    const event: GameEvent = {
      event_id: uuidv4(),
      player_id: this.playerId || '',
      game_id: this.config.game_id,
      server_id: this.config.server_id,
      event_type: eventType,
      event_time: customEventTime || new Date().toISOString(),
      event_data: eventData
    };

    const cachedEvent: CachedEvent = {
      event,
      retry_count: 0
    };

    this.eventCache.push(cachedEvent);
    this.saveCachedEvents();
    this.log(`Event tracked: ${eventType}`, event);

    if (this.eventCache.length >= this.config.batch_size) {
      this.flush().catch(() => {});
    }

    return event.event_id;
  }

  public flush(): Promise<void> {
    return new Promise((resolve, reject) => {
      if (this.eventCache.length === 0) {
        this.log('No events to flush');
        resolve();
        return;
      }

      const eventsToSend = [...this.eventCache];
      this.eventCache = [];
      this.saveCachedEvents();

      this.sendEvents(eventsToSend)
        .then(() => {
          this.log(`Successfully flushed ${eventsToSend.length} events`);
          resolve();
        })
        .catch((error) => {
          this.log(`Failed to flush events: ${error.message}`, error);
          eventsToSend.forEach(event => {
            if (event.retry_count < this.config.max_retries) {
              event.retry_count++;
              event.last_retry_time = Date.now();
              this.eventCache.push(event);
            } else {
              this.log(`Event dropped after max retries: ${event.event.event_id}`);
            }
          });
          this.saveCachedEvents();
          reject(error);
        });
    });
  }

  private async sendEvents(cachedEvents: CachedEvent[]): Promise<void> {
    const events = cachedEvents.map(cached => cached.event);
    const request: EventReportRequest = { events };

    this.log(`Sending ${events.length} events to ${this.config.endpoint}`);

    try {
      const response = await fetch(this.config.endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(request),
        credentials: 'include'
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data: EventReportResponse = await response.json();
      
      if (data.code !== 200) {
        throw new Error(data.message || 'Server returned error');
      }

      this.log(`Events sent successfully. Received count: ${data.data.received_count}`);
    } catch (error) {
      this.log(`Network error when sending events: ${(error as Error).message}`);
      this.isOnline = false;
      throw error;
    }
  }

  private startFlushTimer(): void {
    if (this.flushTimer) {
      clearInterval(this.flushTimer);
    }

    this.flushTimer = window.setInterval(() => {
      if (this.isOnline && this.eventCache.length > 0) {
        this.flush().catch(() => {});
      }
    }, this.config.flush_interval);

    this.log(`Flush timer started with interval: ${this.config.flush_interval}ms`);
  }

  private stopFlushTimer(): void {
    if (this.flushTimer) {
      clearInterval(this.flushTimer);
      this.flushTimer = null;
      this.log('Flush timer stopped');
    }
  }

  private startHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
    }

    this.heartbeatTimer = window.setInterval(() => {
      if (this.playerId) {
        this.sendHeartbeat();
        if (this.config.enable_dynamic_config && this.isEventTypeEnabled('heartbeat')) {
          this.trackEvent('heartbeat', {});
        }
      }
    }, this.config.heartbeat_interval);

    this.log(`Heartbeat timer started with interval: ${this.config.heartbeat_interval}ms`);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
      this.log('Heartbeat timer stopped');
    }
  }

  private startConfigRefreshTimer(): void {
    if (this.configRefreshTimer) {
      clearInterval(this.configRefreshTimer);
    }

    this.configRefreshTimer = window.setInterval(() => {
      if (this.isOnline) {
        this.refreshConfig().catch((error) => {
          this.log(`Config refresh failed: ${error.message}`);
        });
      }
    }, this.config.config_refresh_interval);

    this.log(`Config refresh timer started with interval: ${this.config.config_refresh_interval}ms`);
  }

  private stopConfigRefreshTimer(): void {
    if (this.configRefreshTimer) {
      clearInterval(this.configRefreshTimer);
      this.configRefreshTimer = null;
      this.log('Config refresh timer stopped');
    }
  }

  private async sendHeartbeat(): Promise<void> {
    if (!this.playerId) {
      return;
    }

    const payload: HeartbeatPayload = {
      player_id: this.playerId,
      game_id: this.config.game_id,
      server_id: this.config.server_id,
      timestamp: new Date().toISOString()
    };

    this.log('Sending heartbeat', payload);

    try {
      const response = await fetch(`${this.config.endpoint.replace('/events/report', '/heartbeat')}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        throw new Error(`Heartbeat failed with status: ${response.status}`);
      }

      this.log('Heartbeat successful');
    } catch (error) {
      this.log(`Heartbeat failed: ${(error as Error).message}`);
    }
  }

  private setupNetworkListener(): void {
    window.addEventListener('online', () => {
      this.log('Network connection restored');
      this.isOnline = true;
      if (this.eventCache.length > 0) {
        this.flush().catch(() => {});
      }
      if (this.config.enable_dynamic_config) {
        this.refreshConfig().catch(() => {});
      }
    });

    window.addEventListener('offline', () => {
      this.log('Network connection lost');
      this.isOnline = false;
    });
  }

  private saveCachedEvents(): void {
    try {
      const settings = this.remoteConfig?.sdk_settings;
      if (settings && !settings.enable_local_cache) {
        return;
      }
      
      localStorage.setItem(
        `gamestats_events_${this.config.game_id}_${this.config.server_id}`,
        JSON.stringify(this.eventCache)
      );
    } catch (error) {
      this.log(`Failed to save events to localStorage: ${(error as Error).message}`);
    }
  }

  private loadCachedEvents(): void {
    try {
      const stored = localStorage.getItem(
        `gamestats_events_${this.config.game_id}_${this.config.server_id}`
      );
      
      if (stored) {
        this.eventCache = JSON.parse(stored);
        this.log(`Loaded ${this.eventCache.length} cached events from localStorage`);
      }
    } catch (error) {
      this.log(`Failed to load events from localStorage: ${(error as Error).message}`);
      this.eventCache = [];
    }
  }

  public getCacheSize(): number {
    return this.eventCache.length;
  }

  public getConfig(): SDKConfig {
    return { ...this.config };
  }

  public async shutdown(): Promise<void> {
    this.log('Shutting down SDK...');
    
    this.stopFlushTimer();
    this.stopHeartbeat();
    this.stopConfigRefreshTimer();
    
    if (this.configRetryTimer) {
      clearTimeout(this.configRetryTimer);
      this.configRetryTimer = null;
    }
    
    if (this.eventCache.length > 0) {
      await this.flush().catch(() => {});
    }
    
    this.isInitialized = false;
    this.log('SDK shut down successfully');
  }

  public trackLogin(loginData: { login_method: string; device_type: string; ip_region?: string }): string | null {
    return this.trackEvent('login', loginData);
  }

  public trackLogout(logoutData: { session_duration?: number; reason?: string }): string | null {
    return this.trackEvent('logout', logoutData);
  }

  public trackPayment(paymentData: { amount: number; currency: string; item_id: string; payment_method?: string }): string | null {
    return this.trackEvent('payment', paymentData);
  }

  public trackLevelUp(levelUpData: { new_level: number; previous_level: number }): string | null {
    return this.trackEvent('level_up', levelUpData);
  }

  public trackQuestComplete(questData: { quest_id: string; quest_name: string; reward?: Record<string, any> }): string | null {
    return this.trackEvent('quest_complete', questData);
  }

  public trackItemPurchase(purchaseData: { item_id: string; item_name: string; price: number; quantity?: number }): string | null {
    return this.trackEvent('item_purchase', purchaseData);
  }

  public trackSocialInteraction(interactionData: { interaction_type: string; target_player_id: string }): string | null {
    return this.trackEvent('social_interaction', interactionData);
  }

  public trackGameStart(gameStartData: { game_mode?: string } = {}): string | null {
    return this.trackEvent('game_start', gameStartData);
  }

  public trackGameEnd(gameEndData: { game_result?: string; duration?: number } = {}): string | null {
    return this.trackEvent('game_end', gameEndData);
  }
}

export { GameStatsSDK };
export default GameStatsSDK;
