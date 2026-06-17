-- 风控系统初始数据

-- ============ 初始规则配置 ============
-- 支付规则：单笔金额 > 50000 且 国家不在白名单
INSERT INTO risk_rules (
    rule_id, rule_name, rule_type, business_line, event_types,
    priority, short_circuit, enabled, severity,
    dsl_expression, model_weight, threshold,
    actions, description, version, created_at, updated_at
) VALUES (
    'RULE_PAY_001',
    '大额跨境支付异常',
    'EXPRESSION',
    'payment',
    'payment.create,payment.confirm',
    10,
    true,
    true,
    'HIGH',
    'event.amount > 50000 && event.country NOT IN [''CN'', ''US'', ''JP'', ''GB'', ''DE'']',
    0.6,
    0.7,
    'ACT_FREEZE_ACCOUNT,ACT_WEBHOOK_RISK',
    '单笔金额超过5万且非白名单国家的支付交易',
    1,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

-- 支付规则：5分钟内同一IP登录失败超过5次
INSERT INTO risk_rules (
    rule_id, rule_name, rule_type, business_line, event_types,
    priority, short_circuit, enabled, severity,
    dsl_expression, model_weight, threshold,
    window_config, escalation_threshold,
    actions, description, version, created_at, updated_at
) VALUES (
    'RULE_LOGIN_001',
    '短时间内登录失败过多',
    'WINDOW',
    'login',
    'login.fail',
    20,
    true,
    true,
    'MEDIUM',
    NULL,
    0.5,
    0.7,
    '{"window_size_ms": 300000, "aggregation_field": "event.loginCount", "aggregation_type": "COUNT", "group_by": ["event.ip"], "threshold_value": 5.0, "operator": ">"}',
    3,
    'ACT_BLOCK_IP,ACT_WEBHOOK_LOGIN',
    '5分钟内同一IP登录失败超过5次',
    1,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

-- 登录规则：登录成功后3分钟内发起大额支付（序列规则）
INSERT INTO risk_rules (
    rule_id, rule_name, rule_type, business_line, event_types,
    priority, short_circuit, enabled, severity,
    model_weight, threshold,
    sequence_config,
    actions, description, version, created_at, updated_at
) VALUES (
    'RULE_SEQ_001',
    '登录后快速大额支付',
    'SEQUENCE',
    'transaction',
    'login.success,payment.create',
    15,
    true,
    true,
    'HIGH',
    0.7,
    0.6,
    '{"pattern": "A->B", "time_window_ms": 180000, "event_mappings": [{"step_name": "A", "event_type": "login.success", "condition": null}, {"step_name": "B", "event_type": "payment.create", "condition": "event.amount > 10000"}]}',
    'ACT_MANUAL_REVIEW,ACT_FREEZE_ACCOUNT,ACT_SEND_SMS',
    '登录成功后3分钟内发起超过1万的支付',
    1,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

-- 营销规则：同一用户1分钟内领取优惠券超过3张
INSERT INTO risk_rules (
    rule_id, rule_name, rule_type, business_line, event_types,
    priority, short_circuit, enabled, severity,
    model_weight, threshold,
    window_config, escalation_threshold,
    actions, description, version, created_at, updated_at
) VALUES (
    'RULE_MKT_001',
    '短时间内重复领券',
    'WINDOW',
    'marketing',
    'marketing.coupon.redeem',
    30,
    false,
    true,
    'WARNING',
    0.4,
    0.6,
    '{"window_size_ms": 60000, "aggregation_field": "event.couponId", "aggregation_type": "DISTINCT_COUNT", "group_by": ["event.userId"], "threshold_value": 3.0, "operator": ">"}',
    5,
    'ACT_RATE_LIMIT_MARKETING',
    '1分钟内同一用户领取不同优惠券超过3张，疑似薅羊毛',
    1,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

-- 交易规则：设备指纹异常 + 金额异常
INSERT INTO risk_rules (
    rule_id, rule_name, rule_type, business_line, event_types,
    priority, short_circuit, enabled, severity,
    dsl_expression, model_weight, threshold,
    suppression_rules,
    actions, description, version, created_at, updated_at
) VALUES (
    'RULE_TXN_001',
    '设备指纹异常交易',
    'EXPRESSION',
    'transaction',
    'transaction.create,transaction.confirm',
    25,
    true,
    true,
    'CRITICAL',
    'event.deviceRiskLevel >= 4 && (event.amount > 10000 || event.isFirstTransaction == true)',
    0.8,
    0.5,
    'RULE_MKT_001',
    'ACT_FREEZE_ACCOUNT,ACT_BLOCK_IP,ACT_MANUAL_REVIEW,ACT_SEND_EMAIL',
    '高风险设备 + 首次交易或大额交易，直接触发熔断',
    1,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

-- ============ 初始模型配置 ============
INSERT INTO model_configs (
    model_id, model_name, model_version, model_path,
    feature_names, feature_extractors, default_values,
    input_name, output_name,
    threshold, enabled, weight,
    created_at, updated_at
) VALUES (
    'MODEL_FRAUD_001',
    'fraud_detection_xgboost',
    'v1.2.0',
    'classpath:models/fraud_detection_v1.onnx',
    'amount,login_age_hours,device_risk,ip_risk,country_risk,is_first_pay,avg_amount_7d,txn_count_1h',
    '{"amount": "event.amount", "login_age_hours": "context.loginAgeHours", "device_risk": "event.deviceRiskLevel", "ip_risk": "context.ipRiskScore", "country_risk": "context.countryRisk", "is_first_pay": "event.isFirstTransaction", "avg_amount_7d": "redis.avgAmount7d", "txn_count_1h": "redis.txnCount1h"}',
    '{"login_age_hours": 24.0, "device_risk": 2.0, "ip_risk": 0.5, "country_risk": 0.3, "avg_amount_7d": 1000.0, "txn_count_1h": 1.0}',
    'float_input',
    'probability',
    0.5,
    false,
    0.5,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

-- ============ 初始动作配置 ============
INSERT INTO action_definitions (
    action_id, action_name, action_type, business_line, enabled,
    parameters, webhook_config, retry_config, rate_limit_config,
    description, created_at, updated_at
) VALUES (
    'ACT_FREEZE_ACCOUNT',
    '冻结账户',
    'FREEZE_ACCOUNT',
    NULL,
    true,
    '{"freeze_hours": 24, "reason_code": "RISK_FREEZE", "notify_user": true}',
    '{"url": "https://account-service.internal/api/v1/accounts/freeze", "method": "POST", "headers": {"Content-Type": "application/json"}, "auth_type": "BEARER", "auth_token": "${ACCOUNT_SERVICE_TOKEN}"}',
    '{"strategy": "EXPONENTIAL_BACKOFF", "max_attempts": 3, "initial_delay_ms": 500, "multiplier": 2.0}',
    '{"level": "ENTITY", "max_per_minute": 60}',
    '触发风控后自动冻结关联账户24小时',
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

INSERT INTO action_definitions (
    action_id, action_name, action_type, business_line, enabled,
    parameters, webhook_config, retry_config, rate_limit_config,
    description, created_at, updated_at
) VALUES (
    'ACT_BLOCK_IP',
    '拉黑IP',
    'BLOCK_IP',
    NULL,
    true,
    '{"block_hours": 12, "block_list": "risk_blacklist", "notify_firewall": true}',
    '{"url": "https://firewall.internal/api/v1/rules/block", "method": "PUT", "headers": {"X-API-Key": "${FIREWALL_API_KEY}"}}',
    '{"strategy": "FIXED", "max_attempts": 3, "initial_delay_ms": 1000}',
    '{"level": "GLOBAL", "max_per_minute": 300}',
    '将风险IP加入防火墙黑名单12小时',
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

INSERT INTO action_definitions (
    action_id, action_name, action_type, business_line, enabled,
    parameters, webhook_config, retry_config, rate_limit_config,
    description, created_at, updated_at
) VALUES (
    'ACT_MANUAL_REVIEW',
    '人工审核',
    'REQUIRE_MANUAL_REVIEW',
    NULL,
    true,
    '{"queue": "RISK_REVIEW", "priority": "AUTO", "sla_minutes": 60, "reviewer_groups": "risk_team_1,risk_team_2"}',
    '{"url": "https://ticketing.internal/api/v1/tickets/create", "method": "POST"}',
    '{"strategy": "LINEAR", "max_attempts": 5, "initial_delay_ms": 2000}',
    '{"level": "GLOBAL", "max_per_minute": 100}',
    '创建工单通知风控团队人工审核',
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

INSERT INTO action_definitions (
    action_id, action_name, action_type, business_line, enabled,
    parameters, webhook_config, retry_config, rate_limit_config,
    description, created_at, updated_at
) VALUES (
    'ACT_RATE_LIMIT_MARKETING',
    '营销接口限流',
    'RATE_LIMIT',
    'marketing',
    true,
    '{"duration_seconds": 3600, "qps": 1, "target": "USER", "path_prefix": "/api/marketing/coupon"}',
    NULL,
    '{"strategy": "NONE", "max_attempts": 1}',
    '{"level": "RULE", "max_per_minute": 1000}',
    '对指定用户ID营销接口限流1QPS持续1小时',
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

INSERT INTO action_definitions (
    action_id, action_name, action_type, business_line, enabled,
    parameters, webhook_config, retry_config, rate_limit_config,
    description, created_at, updated_at
) VALUES (
    'ACT_WEBHOOK_RISK',
    '风控Webhook通知',
    'WEBHOOK',
    NULL,
    true,
    '{"template_id": "RISK_ALERT_TEMPLATE", "include_events": true}',
    '{"url": "https://notify.internal/risk/webhook", "method": "POST", "headers": {"Content-Type": "application/json"}, "auth_type": "SIGNATURE", "sign_header": "X-Risk-Signature"}',
    '{"strategy": "EXPONENTIAL_BACKOFF", "max_attempts": 5, "initial_delay_ms": 1000, "multiplier": 2.5}',
    '{"level": "GLOBAL", "max_per_minute": 500}',
    '通用风险告警Webhook通知下游系统',
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

INSERT INTO action_definitions (
    action_id, action_name, action_type, business_line, enabled,
    parameters, webhook_config, retry_config, rate_limit_config,
    description, created_at, updated_at
) VALUES (
    'ACT_WEBHOOK_LOGIN',
    '登录风控Webhook',
    'WEBHOOK',
    'login',
    true,
    '{"template_id": "LOGIN_RISK_ALERT"}',
    '{"url": "https://login.internal/risk/callback", "method": "POST", "auth_type": "BEARER", "auth_token": "${LOGIN_SERVICE_TOKEN}"}',
    '{"strategy": "EXPONENTIAL_BACKOFF", "max_attempts": 3, "initial_delay_ms": 500}',
    '{"level": "BUSINESS_LINE", "max_per_minute": 200}',
    '登录风险回调通知登录服务',
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

INSERT INTO action_definitions (
    action_id, action_name, action_type, business_line, enabled,
    parameters, webhook_config, retry_config, rate_limit_config,
    description, created_at, updated_at
) VALUES (
    'ACT_SEND_SMS',
    '发送短信',
    'SEND_SMS',
    NULL,
    true,
    '{"template_code": "SMS_RISK_ALERT_001", "channel": "PRIORITY"}',
    '{"url": "https://sms.internal/api/v1/send", "method": "POST", "headers": {"X-API-Key": "${SMS_API_KEY}"}}',
    '{"strategy": "FIXED", "max_attempts": 2, "initial_delay_ms": 1000}',
    '{"level": "GLOBAL", "max_per_minute": 1000}',
    '向用户手机号发送风险告警短信',
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

INSERT INTO action_definitions (
    action_id, action_name, action_type, business_line, enabled,
    parameters, webhook_config, retry_config, rate_limit_config,
    description, created_at, updated_at
) VALUES (
    'ACT_SEND_EMAIL',
    '发送邮件',
    'SEND_EMAIL',
    NULL,
    true,
    '{"template_code": "EMAIL_RISK_ALERT_001", "from": "risk@enterprise.com", "to": "risk-team@enterprise.com", "priority": "HIGH"}',
    '{"url": "https://email.internal/api/v1/send", "method": "POST", "auth_type": "BASIC", "auth_username": "${EMAIL_USER}", "auth_password": "${EMAIL_PASS}"}',
    '{"strategy": "FIXED", "max_attempts": 3, "initial_delay_ms": 2000}',
    '{"level": "GLOBAL", "max_per_minute": 200}',
    '向风控团队发送邮件告警',
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

-- ============ 初始事件Schema ============
INSERT INTO event_schemas (
    schema_id, business_line, event_type,
    required_fields, schema_definition,
    enabled, created_at, updated_at
) VALUES (
    'SCHEMA_PAY_CREATE',
    'payment',
    'payment.create',
    'amount,currency,userId,orderId',
    '{"fields": {"amount": "number", "currency": "string", "userId": "string", "orderId": "string", "country": "string", "deviceRiskLevel": "number", "isFirstTransaction": "boolean"}}',
    true,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

INSERT INTO event_schemas (
    schema_id, business_line, event_type,
    required_fields, schema_definition,
    enabled, created_at, updated_at
) VALUES (
    'SCHEMA_LOGIN_FAIL',
    'login',
    'login.fail',
    'userId,ip,reason',
    '{"fields": {"userId": "string", "ip": "string", "reason": "string", "deviceId": "string"}}',
    true,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

INSERT INTO event_schemas (
    schema_id, business_line, event_type,
    required_fields, schema_definition,
    enabled, created_at, updated_at
) VALUES (
    'SCHEMA_LOGIN_SUCCESS',
    'login',
    'login.success',
    'userId,ip',
    '{"fields": {"userId": "string", "ip": "string", "deviceId": "string", "loginAgeHours": "number"}}',
    true,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

INSERT INTO event_schemas (
    schema_id, business_line, event_type,
    required_fields, schema_definition,
    enabled, created_at, updated_at
) VALUES (
    'SCHEMA_COUPON_REDEEM',
    'marketing',
    'marketing.coupon.redeem',
    'userId,couponId',
    '{"fields": {"userId": "string", "couponId": "string", "campaignId": "string"}}',
    true,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);

INSERT INTO event_schemas (
    schema_id, business_line, event_type,
    required_fields, schema_definition,
    enabled, created_at, updated_at
) VALUES (
    'SCHEMA_TXN_CREATE',
    'transaction',
    'transaction.create',
    'txnId,userId,amount,type',
    '{"fields": {"txnId": "string", "userId": "string", "amount": "number", "type": "string", "deviceRiskLevel": "number", "isFirstTransaction": "boolean"}}',
    true,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
);
