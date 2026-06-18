import { BaseNotifier, AlertMessage } from './base.notifier';
export declare class WeComNotifier extends BaseNotifier {
    private readonly webhookUrl;
    constructor(webhookUrl: string);
    send(message: AlertMessage): Promise<void>;
}
