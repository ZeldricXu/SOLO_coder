import { AlertChannelType } from '../dto/create-alert-rule.dto';
import { BaseNotifier } from './base.notifier';
export declare class NotifierFactory {
    static create(type: AlertChannelType, target: string): BaseNotifier;
}
