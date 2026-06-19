import { AlertType } from '@prisma/client';
export declare enum AlertChannelType {
    EMAIL = "EMAIL",
    WECOM = "WECOM",
    DINGTALK = "DINGTALK"
}
export declare class AlertChannelDto {
    type: AlertChannelType;
    target: string;
}
export declare class CreateAlertRuleDto {
    name: string;
    type: AlertType;
    condition: Record<string, any>;
    metricId: string;
    channels: AlertChannelDto[];
    silenceMinutes?: number;
    escalationMinutes?: number;
    escalationChannels?: AlertChannelDto[];
    isActive?: boolean;
    consecutiveThreshold?: number;
    dedupMinutes?: number;
    aggregationGroup?: string;
}
