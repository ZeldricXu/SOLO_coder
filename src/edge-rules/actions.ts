import { RuleAction, RuleContext } from './types';

export interface ActionHandler {
  execute(action: RuleAction, context: RuleContext): Promise<unknown>;
}

export class LogAction implements ActionHandler {
  async execute(action: RuleAction, context: RuleContext): Promise<unknown> {
    const level = action.params.level as string || 'info';
    const message = action.params.message as string || 'Rule triggered';
    console[level](`[EdgeRule] ${message}`, { action, event: context.event });
    return { success: true, message };
  }
}

export class SetStateAction implements ActionHandler {
  async execute(action: RuleAction, context: RuleContext): Promise<unknown> {
    const key = action.params.key as string;
    const value = action.params.value;
    if (context.state && key) {
      context.state[key] = value;
    }
    return { success: true, key, value };
  }
}

export class SendNotificationAction implements ActionHandler {
  async execute(action: RuleAction, context: RuleContext): Promise<unknown> {
    const type = action.params.type as string;
    const recipient = action.params.recipient as string;
    const message = action.params.message as string;
    console.log(`[Notification] ${type} to ${recipient}: ${message}`);
    return { success: true, type, recipient, message };
  }
}

export class HttpCallAction implements ActionHandler {
  async execute(action: RuleAction, context: RuleContext): Promise<unknown> {
    const url = action.params.url as string;
    const method = (action.params.method as string) || 'POST';
    const headers = (action.params.headers as Record<string, string>) || {};
    const body = action.params.body || context.event;

    console.log(`[HTTP] ${method} ${url}`, { headers, body });
    return { success: true, url, method };
  }
}

export class DeviceCommandAction implements ActionHandler {
  async execute(action: RuleAction, context: RuleContext): Promise<unknown> {
    const deviceId = action.params.deviceId as string;
    const command = action.params.command as string;
    const params = action.params.params as Record<string, unknown>;

    console.log(`[DeviceCommand] ${deviceId}: ${command}`, params);
    return { success: true, deviceId, command, params };
  }
}

export function createActionHandler(type: string): ActionHandler {
  switch (type) {
    case 'log':
      return new LogAction();
    case 'set_state':
      return new SetStateAction();
    case 'send_notification':
      return new SendNotificationAction();
    case 'http_call':
      return new HttpCallAction();
    case 'device_command':
      return new DeviceCommandAction();
    default:
      throw new Error(`Unknown action type: ${type}`);
  }
}
