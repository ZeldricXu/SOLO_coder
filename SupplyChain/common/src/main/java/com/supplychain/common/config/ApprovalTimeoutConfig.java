package com.supplychain.common.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalTimeoutConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String configId;

    private String orderType;

    private int timeoutMinutes;

    private String description;

    private int notificationIntervalMinutes;

    private int maxNotifications;

    private boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Map<String, Object> metadata;

    public static Map<String, ApprovalTimeoutConfig> getDefaultConfigs() {
        Map<String, ApprovalTimeoutConfig> configs = new HashMap<>();

        configs.put("urgent_purchase", ApprovalTimeoutConfig.builder()
                .configId("cfg_timeout_urgent")
                .orderType("urgent_purchase")
                .timeoutMinutes(30)
                .description("紧急采购 - 30分钟超时快速提醒")
                .notificationIntervalMinutes(15)
                .maxNotifications(5)
                .enabled(true)
                .metadata(Map.of("priority", "critical", "escalation", "director"))
                .build());

        configs.put("purchase", ApprovalTimeoutConfig.builder()
                .configId("cfg_timeout_normal")
                .orderType("purchase")
                .timeoutMinutes(120)
                .description("普通采购 - 2小时超时提醒")
                .notificationIntervalMinutes(60)
                .maxNotifications(3)
                .enabled(true)
                .metadata(Map.of("priority", "normal", "escalation", "manager"))
                .build());

        configs.put("low_priority_purchase", ApprovalTimeoutConfig.builder()
                .configId("cfg_timeout_low")
                .orderType("low_priority_purchase")
                .timeoutMinutes(480)
                .description("低优先级采购 - 8小时超时提醒")
                .notificationIntervalMinutes(240)
                .maxNotifications(2)
                .enabled(true)
                .metadata(Map.of("priority", "low", "escalation", "supervisor"))
                .build());

        configs.put("standard_purchase", ApprovalTimeoutConfig.builder()
                .configId("cfg_timeout_standard")
                .orderType("standard_purchase")
                .timeoutMinutes(240)
                .description("标准采购 - 4小时超时提醒")
                .notificationIntervalMinutes(120)
                .maxNotifications(3)
                .enabled(true)
                .metadata(Map.of("priority", "medium", "escalation", "manager"))
                .build());

        configs.put("emergency_purchase", ApprovalTimeoutConfig.builder()
                .configId("cfg_timeout_emergency")
                .orderType("emergency_purchase")
                .timeoutMinutes(15)
                .description("应急采购 - 15分钟超时提醒")
                .notificationIntervalMinutes(5)
                .maxNotifications(10)
                .enabled(true)
                .metadata(Map.of("priority", "emergency", "escalation", "ceo"))
                .build());

        return configs;
    }

    public static ApprovalTimeoutConfig getDefaultConfig(String orderType) {
        Map<String, ApprovalTimeoutConfig> defaultConfigs = getDefaultConfigs();

        if (orderType != null) {
            for (Map.Entry<String, ApprovalTimeoutConfig> entry : defaultConfigs.entrySet()) {
                if (orderType.equalsIgnoreCase(entry.getKey()) ||
                    orderType.toLowerCase().contains(entry.getKey().replace("_purchase", ""))) {
                    return entry.getValue();
                }
            }

            if (orderType.toLowerCase().contains("urgent") || orderType.toLowerCase().contains("紧急")) {
                return defaultConfigs.get("urgent_purchase");
            }
            if (orderType.toLowerCase().contains("low") || orderType.toLowerCase().contains("低")) {
                return defaultConfigs.get("low_priority_purchase");
            }
            if (orderType.toLowerCase().contains("emergency") || orderType.toLowerCase().contains("应急")) {
                return defaultConfigs.get("emergency_purchase");
            }
            if (orderType.toLowerCase().contains("standard") || orderType.toLowerCase().contains("标准")) {
                return defaultConfigs.get("standard_purchase");
            }
        }

        return defaultConfigs.get("purchase");
    }
}
