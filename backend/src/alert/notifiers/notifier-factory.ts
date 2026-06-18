import { AlertChannelType } from '../dto/create-alert-rule.dto';
import { BaseNotifier } from './base.notifier';
import { EmailNotifier } from './email.notifier';
import { WeComNotifier } from './wecom.notifier';
import { DingTalkNotifier } from './dingtalk.notifier';

export class NotifierFactory {
  static create(type: AlertChannelType, target: string): BaseNotifier {
    switch (type) {
      case AlertChannelType.EMAIL:
        return new EmailNotifier(target);
      case AlertChannelType.WECOM:
        return new WeComNotifier(target);
      case AlertChannelType.DINGTALK:
        return new DingTalkNotifier(target);
      default:
        throw new Error(`Unsupported alert channel type: ${type}`);
    }
  }
}
