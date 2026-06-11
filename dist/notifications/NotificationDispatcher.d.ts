import { NotificationConfig, NotificationMessage, AuditEvent } from '../types';
export interface NotificationChannel {
    readonly type: string;
    send(message: NotificationMessage): Promise<{
        success: boolean;
        error?: string;
    }>;
}
export declare class SlackWebhookChannel implements NotificationChannel {
    readonly type = "slack";
    private webhookUrl;
    private username?;
    private channel?;
    constructor(config: {
        webhookUrl: string;
        username?: string;
        channel?: string;
    });
    send(message: NotificationMessage): Promise<{
        success: boolean;
        error?: string;
    }>;
    private buildBlocks;
    private formatChangesAsBlocks;
    private formatValue;
}
export declare class EmailChannel implements NotificationChannel {
    readonly type = "email";
    private config;
    constructor(config: EmailChannel['config']);
    send(message: NotificationMessage): Promise<{
        success: boolean;
        error?: string;
    }>;
    private buildHtmlBody;
    private buildTextBody;
    private formatValue;
    private escapeHtml;
}
export declare class CustomWebhookChannel implements NotificationChannel {
    readonly type = "webhook";
    private config;
    constructor(config: CustomWebhookChannel['config']);
    send(message: NotificationMessage): Promise<{
        success: boolean;
        error?: string;
    }>;
    private buildPayload;
}
export declare class KafkaChannel implements NotificationChannel {
    readonly type = "kafka";
    private brokers;
    private topic;
    private clientId;
    private auth?;
    constructor(config: {
        brokers: string;
        topic: string;
        clientId?: string;
        auth?: {
            mechanism: string;
            username: string;
            password: string;
        };
    });
    send(message: NotificationMessage): Promise<{
        success: boolean;
        error?: string;
    }>;
}
export declare class ElasticsearchChannel implements NotificationChannel {
    readonly type = "elasticsearch";
    private nodeUrl;
    private index;
    private auth?;
    constructor(config: {
        nodeUrl: string;
        index: string;
        auth?: {
            username: string;
            password: string;
        };
    });
    send(message: NotificationMessage): Promise<{
        success: boolean;
        error?: string;
    }>;
}
export declare class NotificationDispatcher {
    private channels;
    constructor(configs?: NotificationConfig[]);
    addChannel(config: NotificationConfig): void;
    addCustomChannel(id: string, channel: NotificationChannel): void;
    dispatch(message: NotificationMessage): Promise<{
        channelId: string;
        success: boolean;
        error?: string;
    }[]>;
    dispatchTo(channelIds: string[], message: NotificationMessage): Promise<{
        channelId: string;
        success: boolean;
        error?: string;
    }[]>;
    listChannels(): {
        id: string;
        type: string;
    }[];
    static buildAuditEvent(message: NotificationMessage): AuditEvent;
}
