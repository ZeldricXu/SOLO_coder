package com.configcenter.api.service;

import com.configcenter.common.dto.*;
import com.configcenter.common.entity.*;
import com.configcenter.common.enums.*;
import com.configcenter.common.exception.*;
import com.configcenter.common.util.*;
import com.configcenter.audit.service.AuditService;
import com.configcenter.config.repository.ConfigItemRepository;
import com.configcenter.config.service.ConfigManagementService;
import com.configcenter.encryption.service.EncryptionService;
import com.configcenter.group.service.GroupManagementService;
import com.configcenter.push.service.PushService;
import com.configcenter.validation.service.ConfigValidationService;
import com.configcenter.validation.service.ValidationRuleService;
import com.configcenter.version.service.VersionControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigFacadeService {

    private final ConfigManagementService configManagementService;
    private final VersionControlService versionControlService;
    private final GroupManagementService groupManagementService;
    private final PushService pushService;
    private final AuditService auditService;
    private final ConfigValidationService configValidationService;
    private final EncryptionService encryptionService;
    private final ConfigItemRepository configItemRepository;
    private final ValidationRuleService validationRuleService;

    @Transactional
    public Map<String, Object> createConfig(CreateConfigRequest request, String ipAddress) {
        log.info("Creating config with facade: key={}", request.getConfigKey());
        
        GroupDTO group = groupManagementService.getGroupById(request.getGroupId());
        if (group == null) {
            throw new GroupNotFoundException(request.getGroupId());
        }

        configValidationService.validateKey(request.getConfigKey());
        configValidationService.validateDescription(request.getDescription());

        if (configItemRepository.existsByConfigKeyAndEnvironmentAndGroupIdAndDeletedFalse(
                request.getConfigKey(), request.getEnvironment(), request.getGroupId())) {
            throw new BusinessException("配置已存在: " + request.getConfigKey());
        }

        if (request.getValidationRuleIds() != null && !request.getValidationRuleIds().isEmpty()) {
            Map<String, Object> validationResult = validationRuleService.validateWithRules(
                    request.getConfigValue(), request.getValidationRuleIds(), request.getValidationParams());
            if (!Boolean.TRUE.equals(validationResult.get("success"))) {
                List<?> errors = (List<?>) validationResult.get("errors");
                throw new ConfigValidationException("自定义校验失败: " + errors.toString());
            }
        }

        configValidationService.validate(request.getConfigValue(), request.getConfigType());

        ConfigDTO config = configManagementService.createConfig(request);

        if (request.getValidationRuleIds() != null && !request.getValidationRuleIds().isEmpty()) {
            validationRuleService.addConfigRule(config.getConfigId(), request.getValidationRuleIds());
            log.info("Applied validation rules to config: configId={}, rules={}", 
                    config.getConfigId(), request.getValidationRuleIds());
        }

        auditService.recordCreate(config.getConfigId(), config.getConfigValue(), request.getOperator(), ipAddress);

        Map<String, Object> result = new HashMap<>();
        result.put("configId", config.getConfigId());
        result.put("version", config.getCurrentVersion());

        log.info("Config created successfully: configId={}", config.getConfigId());
        return result;
    }

    @Transactional
    public Map<String, Object> updateConfig(UpdateConfigRequest request, String ipAddress) {
        log.info("Updating config: configId={}", request.getConfigId());

        ConfigItem item = configItemRepository.findByConfigIdAndDeletedFalse(request.getConfigId())
                .orElseThrow(() -> new ConfigNotFoundException(request.getConfigId()));

        if (request.getValidationRuleIds() != null && !request.getValidationRuleIds().isEmpty()) {
            Map<String, Object> validationResult = validationRuleService.validateWithRules(
                    request.getConfigValue(), request.getValidationRuleIds(), request.getValidationParams());
            if (!Boolean.TRUE.equals(validationResult.get("success"))) {
                List<?> errors = (List<?>) validationResult.get("errors");
                throw new ConfigValidationException("自定义校验失败: " + errors.toString());
            }
        } else {
            List<String> configRules = validationRuleService.getRulesForConfig(request.getConfigId(), item.getConfigKey());
            if (!configRules.isEmpty()) {
                Map<String, Object> validationResult = validationRuleService.validateWithRules(
                        request.getConfigValue(), configRules, request.getValidationParams());
                if (!Boolean.TRUE.equals(validationResult.get("success"))) {
                    List<?> errors = (List<?>) validationResult.get("errors");
                    throw new ConfigValidationException("配置项校验失败: " + errors.toString());
                }
            }
        }

        configValidationService.validate(request.getConfigValue(), item.getConfigType());

        String oldValue = item.getConfigValue();
        String newValue = encryptionService.encryptIfNeeded(request.getConfigValue(), item.getIsEncrypted());

        if (oldValue.equals(newValue)) {
            log.warn("Config value unchanged: configId={}", request.getConfigId());
            Map<String, Object> result = new HashMap<>();
            result.put("configId", item.getConfigId());
            result.put("version", item.getCurrentVersion());
            result.put("pushStatus", PushStatus.COMPLETED.name());
            result.put("unchanged", true);
            return result;
        }

        VersionDTO version = versionControlService.createVersion(
                item, 
                request.getChangeReason() != null ? request.getChangeReason() : "配置更新", 
                request.getOperator(),
                newValue);

        item.setConfigValue(newValue);
        item.setCurrentVersion(version.getVersion());
        item.setUpdatedBy(request.getOperator());
        configItemRepository.save(item);

        auditService.recordUpdate(item.getConfigId(), oldValue, newValue, 
                request.getOperator(), request.getChangeReason(), ipAddress);

        PushResultDTO pushResult = null;
        if (Boolean.TRUE.equals(request.getAutoPush())) {
            pushResult = pushService.pushConfig(
                    item.getConfigId(), 
                    version.getVersion(), 
                    item.getGroupId(), 
                    request.getOperator());
            auditService.recordPush(item.getConfigId(), request.getOperator(), ipAddress);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("configId", item.getConfigId());
        result.put("version", version.getVersion());
        result.put("pushStatus", pushResult != null ? pushResult.getPushStatus().name() : PushStatus.PENDING.name());
        result.put("pushId", pushResult != null ? pushResult.getPushId() : null);
        result.put("asyncPush", request.getAsyncPush() != null ? request.getAsyncPush() : true);

        log.info("Config updated successfully: configId={}, version={}", item.getConfigId(), version.getVersion());
        return result;
    }

    @Transactional
    public Map<String, Object> rollbackConfig(RollbackConfigRequest request, String ipAddress) {
        log.info("Rolling back config: configId={}, targetVersion={}", 
                request.getConfigId(), request.getTargetVersion());

        ConfigItem item = configItemRepository.findByConfigIdAndDeletedFalse(request.getConfigId())
                .orElseThrow(() -> new ConfigNotFoundException(request.getConfigId()));

        String oldValue = item.getConfigValue();
        VersionDTO rollbackVersion = versionControlService.rollback(request);

        ConfigItem updatedItem = configItemRepository.findByConfigIdAndDeletedFalse(request.getConfigId()).get();
        String newValue = updatedItem.getConfigValue();

        auditService.recordRollback(item.getConfigId(), oldValue, newValue, 
                request.getOperator(), ipAddress);

        PushResultDTO pushResult = null;
        if (Boolean.TRUE.equals(request.getAutoPush())) {
            pushResult = pushService.pushConfig(
                    updatedItem.getConfigId(), 
                    rollbackVersion.getVersion(), 
                    updatedItem.getGroupId(), 
                    request.getOperator());
            auditService.recordPush(updatedItem.getConfigId(), request.getOperator(), ipAddress);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("configId", updatedItem.getConfigId());
        result.put("version", rollbackVersion.getVersion());
        result.put("pushStatus", pushResult != null ? pushResult.getPushStatus().name() : PushStatus.PENDING.name());
        result.put("pushId", pushResult != null ? pushResult.getPushId() : null);

        log.info("Config rolled back successfully: configId={}, version={}", 
                updatedItem.getConfigId(), rollbackVersion.getVersion());
        return result;
    }

    public List<ConfigDTO> queryConfigs(QueryConfigRequest request) {
        return configManagementService.queryConfigs(request);
    }

    public ConfigDTO getConfigDetail(String configId) {
        return configManagementService.getConfigById(configId);
    }

    public List<VersionDTO> getVersionHistory(String configId) {
        return versionControlService.getVersionHistory(configId);
    }

    public VersionDTO getVersion(String configId, String version) {
        return versionControlService.getVersion(configId, version);
    }

    public Map<String, Object> pushConfig(String configId, String operator, String ipAddress) {
        return pushConfig(configId, operator, ipAddress, null);
    }

    public Map<String, Object> pushConfig(String configId, String operator, String ipAddress, Boolean async) {
        log.info("Manually pushing config: configId={}, async={}", configId, async);

        ConfigItem item = configItemRepository.findByConfigIdAndDeletedFalse(configId)
                .orElseThrow(() -> new ConfigNotFoundException(configId));

        PushResultDTO result = pushService.pushConfig(
                configId, item.getCurrentVersion(), item.getGroupId(), operator);

        auditService.recordPush(configId, operator, ipAddress);

        Map<String, Object> response = new HashMap<>();
        response.put("pushId", result.getPushId());
        response.put("pushStatus", result.getPushStatus().name());
        response.put("async", async != null ? async : true);
        return response;
    }

    public PushResultDTO getPushStatus(String pushId) {
        return pushService.getPushRecord(pushId);
    }

    public List<PushResultDTO> getPushHistory(String configId) {
        return pushService.getPushRecordsByConfig(configId);
    }

    public List<AuditRecordDTO> getAuditRecords(String configId) {
        return auditService.getAuditRecordsByConfig(configId);
    }

    public Map<String, Object> getAuditStatistics(String configId) {
        return auditService.getAuditStatistics(configId);
    }
}
