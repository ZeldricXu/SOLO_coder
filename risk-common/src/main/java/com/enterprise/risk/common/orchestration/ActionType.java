package com.enterprise.risk.common.orchestration;

public enum ActionType {
    RATE_LIMIT("rate_limit", "接口限流"),
    BLOCK_IP("block_ip", "拉黑IP"),
    FREEZE_ACCOUNT("freeze_account", "冻结账户"),
    REQUIRE_MANUAL_REVIEW("require_manual_review", "人工确认"),
    WEBHOOK("webhook", "Webhook通知"),
    SEND_EMAIL("send_email", "发送邮件"),
    SEND_SMS("send_sms", "发送短信"),
    LOG_EVENT("log_event", "记录日志");

    private final String code;
    private final String description;

    ActionType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
