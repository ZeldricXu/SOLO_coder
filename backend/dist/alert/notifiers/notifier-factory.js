"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.NotifierFactory = void 0;
const create_alert_rule_dto_1 = require("../dto/create-alert-rule.dto");
const email_notifier_1 = require("./email.notifier");
const wecom_notifier_1 = require("./wecom.notifier");
const dingtalk_notifier_1 = require("./dingtalk.notifier");
class NotifierFactory {
    static create(type, target) {
        switch (type) {
            case create_alert_rule_dto_1.AlertChannelType.EMAIL:
                return new email_notifier_1.EmailNotifier(target);
            case create_alert_rule_dto_1.AlertChannelType.WECOM:
                return new wecom_notifier_1.WeComNotifier(target);
            case create_alert_rule_dto_1.AlertChannelType.DINGTALK:
                return new dingtalk_notifier_1.DingTalkNotifier(target);
            default:
                throw new Error(`Unsupported alert channel type: ${type}`);
        }
    }
}
exports.NotifierFactory = NotifierFactory;
//# sourceMappingURL=notifier-factory.js.map