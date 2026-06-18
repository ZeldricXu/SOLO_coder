import { BaseNotifier, AlertMessage } from './base.notifier';
export declare class EmailNotifier extends BaseNotifier {
    private readonly target;
    private transporter;
    constructor(target: string);
    send(message: AlertMessage): Promise<void>;
}
