import { v4 as uuidv4 } from 'uuid';

const PREFIX_MAP: Record<string, string> = {
  entity: 'ent',
  config: 'cfg',
  run: 'run',
  snapshot: 'snap',
  task: 'task',
  notification: 'notif',
  template: 'tpl',
  pipeline: 'pipe',
  feature: 'feat',
  experiment: 'exp',
  resource: 'rsc',
  batch: 'batch'
};

export function generateId(type: string): string {
  const prefix = PREFIX_MAP[type] || 'id';
  return `${prefix}_${uuidv4().slice(0, 8)}${uuidv4().slice(9, 13)}`;
}

export function generateTraceId(): string {
  return `trace_${Date.now().toString(36)}_${uuidv4().slice(0, 8)}`;
}
