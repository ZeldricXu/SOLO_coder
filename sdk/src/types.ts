export interface GameEvent {
  event_id: string;
  player_id: string;
  game_id: string;
  server_id: string;
  event_type: string;
  event_time: string;
  event_data: Record<string, any>;
}

export interface SDKConfig {
  game_id: string;
  server_id: string;
  endpoint: string;
  config_endpoint: string;
  batch_size: number;
  flush_interval: number;
  max_retries: number;
  heartbeat_interval: number;
  enable_heartbeat: boolean;
  debug: boolean;
  enable_dynamic_config: boolean;
  config_refresh_interval: number;
  config_fetch_timeout_ms?: number;
  config_retry_base_delay_ms?: number;
  config_max_retry_delay_ms?: number;
  config_max_retries?: number;
}

export interface EventReportRequest {
  events: GameEvent[];
}

export interface EventReportResponse {
  code: number;
  data: {
    received_count: number;
  };
  message?: string;
}

export interface HeartbeatPayload {
  player_id: string;
  game_id: string;
  server_id: string;
  timestamp: string;
}

export interface CachedEvent {
  event: GameEvent;
  retry_count: number;
  last_retry_time?: number;
}

export type EventType = 
  | 'login' 
  | 'logout' 
  | 'payment' 
  | 'level_up' 
  | 'quest_complete' 
  | 'item_purchase' 
  | 'social_interaction' 
  | 'game_start' 
  | 'game_end'
  | 'heartbeat'
  | string;

export interface SDKOptions {
  game_id: string;
  server_id: string;
  endpoint?: string;
  config_endpoint?: string;
  batch_size?: number;
  flush_interval?: number;
  max_retries?: number;
  heartbeat_interval?: number;
  enable_heartbeat?: boolean;
  debug?: boolean;
  enable_dynamic_config?: boolean;
  config_refresh_interval?: number;
  config_fetch_timeout_ms?: number;
  config_retry_base_delay_ms?: number;
  config_max_retry_delay_ms?: number;
  config_max_retries?: number;
}

export interface EventConfigItem {
  event_type: string;
  event_name: string;
  description?: string;
  enabled: boolean;
  required_fields?: Record<string, string>;
  optional_fields?: Record<string, string>;
}

export interface SDKSettings {
  batch_size: number;
  flush_interval_ms: number;
  max_retries: number;
  heartbeat_interval_ms: number;
  enable_heartbeat: boolean;
  enable_local_cache: boolean;
  enable_config_fallback?: boolean;
}

export interface RemoteSDKConfig {
  version: string;
  game_id: string;
  config_hash: string;
  last_updated: string;
  event_configs: EventConfigItem[];
  sdk_settings: SDKSettings;
  ttl_seconds?: number;
}

export interface CachedConfigMetadata {
  version: string;
  config_hash: string;
  cached_at: number;
  expires_at: number;
  source: 'remote' | 'default' | 'cached';
  fetch_attempts: number;
  last_fetch_error?: string;
}

export interface ConfigFetchResult {
  success: boolean;
  config?: RemoteSDKConfig;
  error?: string;
  errorType?: 'network' | 'server' | 'timeout' | 'parse' | 'unknown';
  responseStatus?: number;
  timestamp: number;
}

export interface ConfigStatus {
  currentConfig: RemoteSDKConfig | null;
  metadata: CachedConfigMetadata | null;
  isUsingCached: boolean;
  isUsingDefault: boolean;
  lastFetchSuccess: boolean;
  lastFetchError?: string;
  consecutiveFailures: number;
}

export interface ConfigValidationError {
  event_type: string;
  field: string;
  error: string;
}

export interface ConfigChangedCallback {
  (oldConfig: RemoteSDKConfig | null, newConfig: RemoteSDKConfig): void;
}

export interface ConfigFetchErrorCallback {
  (error: string, errorType: string, consecutiveFailures: number): void;
}

export interface ConfigRecoveryCallback {
  (config: RemoteSDKConfig, consecutiveFailures: number): void;
}
