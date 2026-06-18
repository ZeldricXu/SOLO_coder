export interface AlertMessage {
    ruleName: string;
    metricName: string;
    value: number;
    condition: Record<string, any>;
    timestamp: Date;
    dashboardUrl: string;
}
export declare abstract class BaseNotifier {
    abstract send(message: AlertMessage): Promise<void>;
}
