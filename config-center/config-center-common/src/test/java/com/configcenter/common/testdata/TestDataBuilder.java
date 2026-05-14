package com.configcenter.common.testdata;

import com.configcenter.common.entity.*;
import com.configcenter.common.enums.*;

import java.time.LocalDateTime;
import java.util.*;

public class TestDataBuilder {

    private TestDataBuilder() {
    }

    public static ConfigItem.ConfigItemBuilder configItemBuilder() {
        return ConfigItem.builder();
    }

    public static ConfigVersion.ConfigVersionBuilder configVersionBuilder() {
        return ConfigVersion.builder();
    }

    public static ConfigGroup.ConfigGroupBuilder configGroupBuilder() {
        return ConfigGroup.builder();
    }

    public static PushRecord.PushRecordBuilder pushRecordBuilder() {
        return PushRecord.builder();
    }

    public static ApplicationInstance.ApplicationInstanceBuilder applicationInstanceBuilder() {
        return ApplicationInstance.builder();
    }

    public static AuditRecord.AuditRecordBuilder auditRecordBuilder() {
        return AuditRecord.builder();
    }

    public static ConfigItem createDefaultConfigItem() {
        return ConfigItem.builder()
                .configId("config_db_01")
                .configKey("database.url")
                .configValue("jdbc:mysql://localhost:3306/test")
                .configType(ConfigType.STRING)
                .isEncrypted(false)
                .environment(Environment.PRODUCTION)
                .groupId("group_app_core")
                .description("数据库连接配置")
                .currentVersion("v5")
                .createdAt(LocalDateTime.now().minusDays(30))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .createdBy("admin_001")
                .updatedBy("admin_002")
                .deleted(false)
                .build();
    }

    public static ConfigItem createEncryptedConfigItem() {
        return ConfigItem.builder()
                .configId("config_secret_01")
                .configKey("database.password")
                .configValue("encrypted_password_123")
                .configType(ConfigType.STRING)
                .isEncrypted(true)
                .environment(Environment.PRODUCTION)
                .groupId("group_app_core")
                .description("数据库密码（敏感配置）")
                .currentVersion("v3")
                .createdAt(LocalDateTime.now().minusDays(20))
                .updatedAt(LocalDateTime.now().minusDays(3))
                .createdBy("admin_001")
                .updatedBy("admin_001")
                .deleted(false)
                .build();
    }

    public static List<ConfigItem> createMultipleConfigItems(int count) {
        List<ConfigItem> items = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            items.add(ConfigItem.builder()
                    .configId("config_" + i)
                    .configKey("app.feature." + i)
                    .configValue("value_" + i)
                    .configType(ConfigType.STRING)
                    .isEncrypted(false)
                    .environment(Environment.PRODUCTION)
                    .groupId("group_app_core")
                    .description("测试配置 " + i)
                    .currentVersion("v1")
                    .createdAt(LocalDateTime.now().minusDays(count - i))
                    .updatedAt(LocalDateTime.now().minusDays(count - i))
                    .createdBy("admin_001")
                    .updatedBy("admin_001")
                    .deleted(false)
                    .build());
        }
        return items;
    }

    public static List<ConfigVersion> createVersionHistory(String configId, int versionCount) {
        List<ConfigVersion> versions = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.now().minusDays(versionCount);
        
        for (int i = 1; i <= versionCount; i++) {
            versions.add(ConfigVersion.builder()
                    .versionId("ver_" + configId + "_v" + i)
                    .configId(configId)
                    .version("v" + i)
                    .configValue("config_value_v" + i + "_content_with_large_data_to_test_compression_abcdefghijklmnopqrstuvwxyz_0123456789")
                    .changeReason("版本变更说明 " + i)
                    .changedBy("admin_00" + (i % 3 + 1))
                    .changedAt(baseTime.plusDays(i))
                    .isRollback(false)
                    .rollbackFromVersion(null)
                    .build());
        }
        return versions;
    }

    public static ConfigVersion createRollbackVersion(String configId, String currentVersion, String rollbackFromVersion) {
        return ConfigVersion.builder()
                .versionId("ver_" + configId + "_" + currentVersion)
                .configId(configId)
                .version(currentVersion)
                .configValue("rollback_config_value")
                .changeReason("回滚到版本 " + rollbackFromVersion)
                .changedBy("admin_001")
                .changedAt(LocalDateTime.now())
                .isRollback(true)
                .rollbackFromVersion(rollbackFromVersion)
                .build();
    }

    public static ConfigGroup createDefaultConfigGroup() {
        List<String> apps = new ArrayList<>(Arrays.asList("app_order", "app_payment", "app_inventory"));
        return ConfigGroup.builder()
                .groupId("group_app_core")
                .groupName("核心应用配置组")
                .environment(Environment.PRODUCTION)
                .description("核心业务应用配置")
                .applications(apps)
                .createdAt(LocalDateTime.now().minusDays(60))
                .updatedAt(LocalDateTime.now().minusDays(5))
                .createdBy("admin_001")
                .deleted(false)
                .build();
    }

    public static ConfigGroup createEmptyConfigGroup() {
        return ConfigGroup.builder()
                .groupId("group_empty")
                .groupName("空配置组")
                .environment(Environment.TEST)
                .description("用于测试的空配置组")
                .applications(new ArrayList<>())
                .createdAt(LocalDateTime.now().minusDays(10))
                .updatedAt(LocalDateTime.now().minusDays(10))
                .createdBy("admin_001")
                .deleted(false)
                .build();
    }

    public static List<ApplicationInstance> createApplicationInstances(String application, int count, InstanceStatus status) {
        List<ApplicationInstance> instances = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            instances.add(ApplicationInstance.builder()
                    .instanceId("instance_" + application + "_" + i)
                    .application(application)
                    .instanceAddress("192.168.1." + (100 + i) + ":8080")
                    .lastConfigSync(LocalDateTime.now().minusHours(2))
                    .configVersion("v5")
                    .status(status)
                    .createdAt(LocalDateTime.now().minusDays(15))
                    .lastHeartbeat(LocalDateTime.now().minusMinutes(5))
                    .metadata("{\"region\":\"cn-east-1\",\"zone\":\"a\"}")
                    .build());
        }
        return instances;
    }

    public static List<ApplicationInstance> createMixedStatusInstances(int onlineCount, int offlineCount) {
        List<ApplicationInstance> instances = new ArrayList<>();
        
        for (int i = 1; i <= onlineCount; i++) {
            instances.add(ApplicationInstance.builder()
                    .instanceId("instance_online_" + i)
                    .application("app_order")
                    .instanceAddress("10.0.1." + i + ":8080")
                    .lastConfigSync(LocalDateTime.now().minusHours(1))
                    .configVersion("v3")
                    .status(InstanceStatus.ONLINE)
                    .createdAt(LocalDateTime.now().minusDays(7))
                    .lastHeartbeat(LocalDateTime.now().minusMinutes(1))
                    .build());
        }
        
        for (int i = 1; i <= offlineCount; i++) {
            instances.add(ApplicationInstance.builder()
                    .instanceId("instance_offline_" + i)
                    .application("app_order")
                    .instanceAddress("10.0.2." + i + ":8080")
                    .lastConfigSync(LocalDateTime.now().minusDays(3))
                    .configVersion("v2")
                    .status(InstanceStatus.OFFLINE)
                    .createdAt(LocalDateTime.now().minusDays(10))
                    .lastHeartbeat(LocalDateTime.now().minusDays(1))
                    .build());
        }
        
        return instances;
    }

    public static PushRecord createSuccessfulPushRecord() {
        return PushRecord.builder()
                .pushId("push_001")
                .configId("config_db_01")
                .version("v5")
                .targetGroup("group_app_core")
                .pushStatus(PushStatus.COMPLETED)
                .pushTime(LocalDateTime.now().minusMinutes(30))
                .completeTime(LocalDateTime.now().minusMinutes(28))
                .successCount(10)
                .failCount(0)
                .totalCount(10)
                .retryCount(0)
                .pushBy("admin_001")
                .errorMessage(null)
                .build();
    }

    public static PushRecord createPartialFailedPushRecord() {
        return PushRecord.builder()
                .pushId("push_002")
                .configId("config_db_01")
                .version("v5")
                .targetGroup("group_app_core")
                .pushStatus(PushStatus.PARTIAL_FAILED)
                .pushTime(LocalDateTime.now().minusHours(1))
                .completeTime(LocalDateTime.now().minusMinutes(58))
                .successCount(8)
                .failCount(2)
                .totalCount(10)
                .retryCount(1)
                .pushBy("admin_002")
                .errorMessage("2个实例推送失败")
                .build();
    }

    public static PushRecord createFailedPushRecord() {
        return PushRecord.builder()
                .pushId("push_003")
                .configId("config_db_01")
                .version("v5")
                .targetGroup("group_app_core")
                .pushStatus(PushStatus.FAILED)
                .pushTime(LocalDateTime.now().minusHours(2))
                .completeTime(LocalDateTime.now().minusHours(2))
                .successCount(0)
                .failCount(10)
                .totalCount(10)
                .retryCount(3)
                .pushBy("admin_001")
                .errorMessage("所有实例推送失败：连接超时")
                .build();
    }

    public static PushRecord createPendingPushRecord() {
        return PushRecord.builder()
                .pushId("push_004")
                .configId("config_db_01")
                .version("v5")
                .targetGroup("group_app_core")
                .pushStatus(PushStatus.PENDING)
                .pushTime(LocalDateTime.now())
                .completeTime(null)
                .successCount(0)
                .failCount(0)
                .totalCount(10)
                .retryCount(0)
                .pushBy("admin_001")
                .errorMessage(null)
                .build();
    }

    public static AuditRecord createAuditRecord(AuditOperation operation) {
        return AuditRecord.builder()
                .auditId("audit_" + operation.name().toLowerCase() + "_001")
                .configId("config_db_01")
                .operation(operation)
                .oldValue(operation == AuditOperation.CREATE ? null : "old_config_value")
                .newValue(operation == AuditOperation.DELETE ? null : "new_config_value")
                .operator("admin_001")
                .operatedAt(LocalDateTime.now())
                .remark(operation.name() + "配置")
                .ipAddress("192.168.1.100")
                .userAgent("Mozilla/5.0")
                .build();
    }

    public static List<AuditRecord> createAuditHistory(String configId, int count) {
        List<AuditRecord> records = new ArrayList<>();
        AuditOperation[] operations = AuditOperation.values();
        Random random = new Random(42);
        
        for (int i = 0; i < count; i++) {
            records.add(AuditRecord.builder()
                    .auditId("audit_" + configId + "_" + i)
                    .configId(configId)
                    .operation(operations[random.nextInt(operations.length)])
                    .oldValue("old_value_" + i)
                    .newValue("new_value_" + i)
                    .operator("admin_00" + (random.nextInt(3) + 1))
                    .operatedAt(LocalDateTime.now().minusHours(count - i))
                    .remark("审计记录 " + i)
                    .ipAddress("192.168.1." + (100 + random.nextInt(50)))
                    .build());
        }
        return records;
    }

    public static Map<String, Object> createValidationRule(String ruleId, String ruleType, Map<String, Object> params) {
        Map<String, Object> rule = new HashMap<>();
        rule.put("ruleId", ruleId);
        rule.put("ruleType", ruleType);
        rule.put("name", "校验规则-" + ruleType);
        rule.put("description", "用于测试的校验规则");
        rule.put("params", params != null ? params : new HashMap<String, Object>());
        rule.put("enabled", true);
        rule.put("priority", 100);
        return rule;
    }

    public static List<Map<String, Object>> createDefaultValidationRules() {
        List<Map<String, Object>> rules = new ArrayList<>();
        
        Map<String, Object> regexParams = new HashMap<>();
        regexParams.put("pattern", "^[a-zA-Z][a-zA-Z0-9._-]*$");
        rules.add(createValidationRule("RULE_KEY_FORMAT", "KEY_FORMAT", regexParams));
        
        Map<String, Object> lengthParams = new HashMap<>();
        lengthParams.put("minLength", 1);
        lengthParams.put("maxLength", 255);
        rules.add(createValidationRule("RULE_VALUE_LENGTH", "VALUE_LENGTH", lengthParams));
        
        Map<String, Object> jsonParams = new HashMap<>();
        jsonParams.put("allowEmpty", false);
        rules.add(createValidationRule("RULE_JSON_FORMAT", "JSON_FORMAT", jsonParams));
        
        Map<String, Object> rangeParams = new HashMap<>();
        rangeParams.put("min", 0);
        rangeParams.put("max", 1000000);
        rules.add(createValidationRule("RULE_NUMBER_RANGE", "NUMBER_RANGE", rangeParams));
        
        Map<String, Object> sensitiveParams = new HashMap<>();
        sensitiveParams.put("keywords", Arrays.asList("password", "secret", "token", "key"));
        rules.add(createValidationRule("RULE_SENSITIVE_CHECK", "SENSITIVE_CHECK", sensitiveParams));
        
        return rules;
    }

    public static String createLargeConfigValue(int sizeKB) {
        StringBuilder sb = new StringBuilder();
        String baseContent = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ";
        int repeats = (sizeKB * 1024) / baseContent.length();
        for (int i = 0; i < repeats; i++) {
            sb.append(baseContent);
        }
        return sb.toString();
    }

    public static String createCompressibleConfigValue() {
        StringBuilder sb = new StringBuilder();
        String repeated = "configuration_value_test_";
        for (int i = 0; i < 1000; i++) {
            sb.append(repeated).append(i).append("_");
        }
        return sb.toString();
    }
}
