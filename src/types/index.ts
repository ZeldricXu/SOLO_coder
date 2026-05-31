export interface Entity {
  id: string;
  type: string;
  status: string;
  attributes: Record<string, any>;
  created_at: string;
  updated_at: string;
}

export interface Config {
  config_id: string;
  namespace: string;
  version: number;
  parameters: Record<string, any>;
  enabled: boolean;
  applied_at: string;
}

export interface RunInstance {
  run_id: string;
  entity_id: string;
  phase: string;
  progress: number;
  started_at: string;
  completed_at: string | null;
  error_detail: string | null;
}

export interface Snapshot {
  snapshot_id: string;
  timestamp: string;
  metrics: {
    throughput: number;
    latency_p99: number;
    error_rate: number;
  };
  dimensions: Record<string, string>;
}

export type CommandStatus = 'pending' | 'processing' | 'completed' | 'failed';

export interface Command {
  command_id: string;
  type: string;
  payload: Record<string, any>;
  issued_by: string;
  issued_at: string;
  status: CommandStatus;
  result?: Record<string, any>;
  error?: string;
}

export type ComplianceLevel = 'low' | 'medium' | 'high';

export interface AuditLog {
  log_id: string;
  command_id: string;
  action: string;
  actor: string;
  timestamp: string;
  details: Record<string, any>;
  compliance_level: ComplianceLevel;
}

export interface Event {
  event_id: string;
  type: string;
  aggregate_id: string;
  version: number;
  payload: Record<string, any>;
  timestamp: string;
  metadata: Record<string, any>;
}

export type Protocol = 'http' | 'grpc' | 'mqtt' | 'tcp';

export interface RouteConfig {
  path: string;
  method: string;
  target: string;
  protocol: Protocol;
  timeout?: number;
  retries?: number;
  rateLimit?: number;
}

export type DNSRecordType = 'A' | 'AAAA' | 'CNAME' | 'MX' | 'TXT';

export interface DNSRecord {
  name: string;
  type: DNSRecordType;
  value: string;
  ttl: number;
}

export interface ImageLayer {
  digest: string;
  size: number;
  url: string;
  mediaType: string;
}

export interface ContainerImage {
  name: string;
  tag: string;
  digest: string;
  layers: ImageLayer[];
  size: number;
  createdAt: string;
}

export interface Resource {
  id: string;
  type: string;
  config: Record<string, any>;
  labels: Record<string, string>;
  status: string;
  createdAt: string;
}
