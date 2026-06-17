package com.enterprise.risk.gateway.validator;

import com.enterprise.risk.common.event.EntityType;
import com.enterprise.risk.common.event.EventBusinessLine;
import com.enterprise.risk.common.event.RiskEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 事件校验器
 * 对RiskEvent进行多维度校验：
 * 1. 必填字段非空校验
 * 2. event_type白名单校验
 * 3. business_line合法性校验
 * 4. timestamp时间范围校验
 * 5. IP格式校验
 * 6. 字段类型校验
 * 7. 业务线Schema必填字段校验
 */
@Slf4j
@Component
public class EventValidator {

    private final EventSchemaRegistry schemaRegistry;

    /**
     * event_type白名单集合
     */
    private static final Set<String> EVENT_TYPE_WHITELIST = new HashSet<>();

    /**
     * IPv4地址正则表达式
     */
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
    );

    /**
     * IPv6地址正则表达式（简化版）
     */
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$"
    );

    /**
     * 时间允许偏差：1天（毫秒）
     * 事件时间戳不能早于当前时间24小时，也不能晚于当前时间1小时
     */
    private static final long MAX_PAST_DEVIATION_MS = 24 * 60 * 60 * 1000L;
    private static final long MAX_FUTURE_DEVIATION_MS = 60 * 60 * 1000L;

    static {
        EVENT_TYPE_WHITELIST.add("login");
        EVENT_TYPE_WHITELIST.add("logout");
        EVENT_TYPE_WHITELIST.add("payment");
        EVENT_TYPE_WHITELIST.add("refund");
        EVENT_TYPE_WHITELIST.add("register");
        EVENT_TYPE_WHITELIST.add("order_create");
        EVENT_TYPE_WHITELIST.add("order_cancel");
        EVENT_TYPE_WHITELIST.add("password_reset");
        EVENT_TYPE_WHITELIST.add("profile_update");
        EVENT_TYPE_WHITELIST.add("transaction");
        EVENT_TYPE_WHITELIST.add("withdraw");
        EVENT_TYPE_WHITELIST.add("deposit");
        EVENT_TYPE_WHITELIST.add("coupon_use");
        EVENT_TYPE_WHITELIST.add("risk_alert");
        EVENT_TYPE_WHITELIST.add("device_bind");
        EVENT_TYPE_WHITELIST.add("device_unbind");
    }

    public EventValidator(EventSchemaRegistry schemaRegistry) {
        this.schemaRegistry = schemaRegistry;
    }

    /**
     * 执行事件校验
     *
     * @param event 待校验的事件
     * @return 校验结果
     */
    public EventValidationResult validate(RiskEvent event) {
        List<String> errors = new ArrayList<>();

        validateRequiredFields(event, errors);
        validateEventType(event, errors);
        validateBusinessLine(event, errors);
        validateTimestamp(event, errors);
        validateIpFormat(event, errors);
        validateEntityType(event, errors);
        validateFieldTypes(event, errors);
        validateBusinessSchema(event, errors);

        boolean valid = errors.isEmpty();

        if (!valid) {
            log.warn("事件校验失败, eventId: {}, 错误数: {}, 详情: {}",
                    event.getEventId(), errors.size(), errors);
        }

        return EventValidationResult.builder()
                .valid(valid)
                .eventId(event.getEventId())
                .errors(errors)
                .build();
    }

    /**
     * 必填字段非空校验
     */
    private void validateRequiredFields(RiskEvent event, List<String> errors) {
        if (isBlank(event.getEventType())) {
            errors.add("event_type不能为空");
        }
        if (isBlank(event.getBusinessLine())) {
            errors.add("business_line不能为空");
        }
        if (event.getTimestamp() == null) {
            errors.add("timestamp不能为空");
        }
        if (isBlank(event.getEntityId())) {
            errors.add("entity_id不能为空");
        }
        if (isBlank(event.getEntityType())) {
            errors.add("entity_type不能为空");
        }
    }

    /**
     * event_type白名单校验
     */
    private void validateEventType(RiskEvent event, List<String> errors) {
        String eventType = event.getEventType();
        if (eventType == null) {
            return;
        }

        String normalizedType = eventType.toLowerCase().trim();
        if (!EVENT_TYPE_WHITELIST.contains(normalizedType)) {
            errors.add("event_type不在白名单中: " + eventType + ", 允许值: " + EVENT_TYPE_WHITELIST);
        } else {
            event.setEventType(normalizedType);
        }
    }

    /**
     * business_line合法性校验
     */
    private void validateBusinessLine(RiskEvent event, List<String> errors) {
        String businessLine = event.getBusinessLine();
        if (businessLine == null) {
            return;
        }

        try {
            EventBusinessLine.fromCode(businessLine);
        } catch (IllegalArgumentException e) {
            errors.add("business_line非法: " + businessLine);
        }
    }

    /**
     * entity_type合法性校验
     */
    private void validateEntityType(RiskEvent event, List<String> errors) {
        String entityType = event.getEntityType();
        if (entityType == null) {
            return;
        }

        try {
            EntityType.fromCode(entityType);
        } catch (IllegalArgumentException e) {
            errors.add("entity_type非法: " + entityType);
        }
    }

    /**
     * timestamp时间范围校验
     */
    private void validateTimestamp(RiskEvent event, List<String> errors) {
        Long timestamp = event.getTimestamp();
        if (timestamp == null) {
            return;
        }

        long now = Instant.now().toEpochMilli();

        if (timestamp < now - MAX_PAST_DEVIATION_MS) {
            errors.add("timestamp超出允许范围: 时间戳过早（超过24小时前）");
        }

        if (timestamp > now + MAX_FUTURE_DEVIATION_MS) {
            errors.add("timestamp超出允许范围: 时间戳过晚（超过1小时后）");
        }

        if (timestamp <= 0) {
            errors.add("timestamp必须为正整数");
        }
    }

    /**
     * IP格式校验（IPv4/IPv6）
     */
    private void validateIpFormat(RiskEvent event, List<String> errors) {
        String ip = event.getIp();
        if (ip == null || ip.isEmpty()) {
            return;
        }

        boolean validIp = IPV4_PATTERN.matcher(ip).matches()
                || IPV6_PATTERN.matcher(ip).matches()
                || "0:0:0:0:0:0:0:1".equals(ip)
                || "localhost".equalsIgnoreCase(ip);

        if (!validIp) {
            errors.add("IP格式非法: " + ip);
        }
    }

    /**
     * 字段类型校验
     * 校验attributes中各字段的类型合法性
     */
    private void validateFieldTypes(RiskEvent event, List<String> errors) {
        if (event.getAttributes() == null || event.getAttributes().isEmpty()) {
            return;
        }

        for (var entry : event.getAttributes().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (key == null || key.isEmpty()) {
                errors.add("attributes中存在空键名");
                continue;
            }

            if (value == null) {
                continue;
            }

            if (!(value instanceof String
                    || value instanceof Number
                    || value instanceof Boolean
                    || value instanceof java.util.List
                    || value instanceof java.util.Map)) {
                errors.add("attributes字段[" + key + "]类型不支持: " + value.getClass().getSimpleName());
            }
        }
    }

    /**
     * 业务线Schema必填字段校验
     */
    private void validateBusinessSchema(RiskEvent event, List<String> errors) {
        List<String> requiredFields = schemaRegistry.getRequiredFields(event.getBusinessLine());
        if (requiredFields == null || requiredFields.isEmpty()) {
            return;
        }

        for (String field : requiredFields) {
            boolean present = isFieldPresent(event, field);
            if (!present) {
                errors.add("业务线[" + event.getBusinessLine() + "]缺少必填字段: " + field);
            }
        }
    }

    /**
     * 检查字段是否存在且非空
     */
    private boolean isFieldPresent(RiskEvent event, String field) {
        return switch (field) {
            case "source" -> !isBlank(event.getSource());
            case "session_id" -> !isBlank(event.getSessionId());
            case "ip" -> !isBlank(event.getIp());
            case "user_id" -> !isBlank(event.getUserId());
            default -> {
                Object attrValue = event.getAttributes().get(field);
                yield attrValue != null && !attrValue.toString().isEmpty();
            }
        };
    }

    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }

    /**
     * 事件校验结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventValidationResult implements Serializable {

        /**
         * 校验是否通过
         */
        private boolean valid;

        /**
         * 事件ID
         */
        private String eventId;

        /**
         * 错误信息列表
         */
        private List<String> errors;

        /**
         * 获取合并的错误信息字符串
         */
        public String getErrorSummary() {
            if (errors == null || errors.isEmpty()) {
                return "";
            }
            return String.join("; ", errors);
        }
    }
}
