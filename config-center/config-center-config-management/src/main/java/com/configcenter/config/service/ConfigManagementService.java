package com.configcenter.config.service;

import com.configcenter.common.dto.*;
import com.configcenter.common.entity.*;
import com.configcenter.common.enums.*;
import com.configcenter.common.exception.*;
import com.configcenter.common.util.*;
import com.configcenter.config.repository.ConfigItemRepository;
import com.configcenter.encryption.service.EncryptionService;
import com.configcenter.validation.service.ConfigValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigManagementService {

    private final ConfigItemRepository configItemRepository;
    private final EncryptionService encryptionService;
    private final ConfigValidationService configValidationService;

    @Transactional
    public ConfigDTO createConfig(CreateConfigRequest request) {
        log.info("Creating config: key={}, environment={}, groupId={}", 
                request.getConfigKey(), request.getEnvironment(), request.getGroupId());

        configValidationService.validateKey(request.getConfigKey());
        configValidationService.validateDescription(request.getDescription());

        if (configItemRepository.existsByConfigKeyAndEnvironmentAndGroupIdAndDeletedFalse(
                request.getConfigKey(), request.getEnvironment(), request.getGroupId())) {
            throw new BusinessException("配置已存在: " + request.getConfigKey());
        }

        configValidationService.validate(request.getConfigValue(), request.getConfigType());

        ConfigItem item = new ConfigItem();
        item.setConfigKey(request.getConfigKey());
        item.setConfigType(request.getConfigType());
        item.setIsEncrypted(request.getIsEncrypted());
        item.setEnvironment(request.getEnvironment());
        item.setGroupId(request.getGroupId());
        item.setDescription(request.getDescription());
        item.setCurrentVersion("v1");
        item.setCreatedBy(request.getOperator());
        item.setUpdatedBy(request.getOperator());

        String encryptedValue = encryptionService.encryptIfNeeded(request.getConfigValue(), request.getIsEncrypted());
        item.setConfigValue(encryptedValue);

        ConfigItem saved = configItemRepository.save(item);
        log.info("Config created: configId={}", saved.getConfigId());
        return EntityConverter.toConfigDTO(saved);
    }

    public ConfigDTO getConfigById(String configId) {
        ConfigItem item = configItemRepository.findByConfigIdAndDeletedFalse(configId)
                .orElseThrow(() -> new ConfigNotFoundException(configId));
        return EntityConverter.toConfigDTO(item);
    }

    public ConfigDTO getConfigByKey(String configKey, Environment environment, String groupId) {
        ConfigItem item = configItemRepository.findByConfigKeyAndEnvironmentAndGroupId(configKey, environment, groupId)
                .orElseThrow(() -> new ConfigNotFoundException(configKey, groupId));
        return EntityConverter.toConfigDTO(item);
    }

    public List<ConfigDTO> getConfigsByGroup(String groupId) {
        List<ConfigItem> items = configItemRepository.findByGroupIdAndDeletedFalse(groupId);
        List<ConfigDTO> result = new ArrayList<>();
        for (ConfigItem item : items) {
            result.add(EntityConverter.toConfigDTO(item));
        }
        return result;
    }

    public List<ConfigDTO> getConfigsByGroupAndEnvironment(String groupId, Environment environment) {
        List<ConfigItem> items = configItemRepository.findByGroupIdAndEnvironmentAndDeletedFalse(groupId, environment);
        List<ConfigDTO> result = new ArrayList<>();
        for (ConfigItem item : items) {
            result.add(EntityConverter.toConfigDTO(item));
        }
        return result;
    }

    public List<ConfigDTO> getConfigsByEnvironment(Environment environment) {
        List<ConfigItem> items = configItemRepository.findByEnvironmentAndDeletedFalse(environment);
        List<ConfigDTO> result = new ArrayList<>();
        for (ConfigItem item : items) {
            result.add(EntityConverter.toConfigDTO(item));
        }
        return result;
    }

    @Transactional
    public void deleteConfig(String configId, String operator) {
        log.info("Deleting config: configId={}", configId);
        ConfigItem item = configItemRepository.findByConfigIdAndDeletedFalse(configId)
                .orElseThrow(() -> new ConfigNotFoundException(configId));
        item.setDeleted(true);
        item.setUpdatedBy(operator);
        configItemRepository.save(item);
        log.info("Config deleted: configId={}", configId);
    }

    public String getDecryptedValue(ConfigItem item) {
        return encryptionService.decryptIfNeeded(item.getConfigValue(), item.getIsEncrypted());
    }

    public String getDecryptedValue(String configId) {
        ConfigItem item = configItemRepository.findByConfigIdAndDeletedFalse(configId)
                .orElseThrow(() -> new ConfigNotFoundException(configId));
        return getDecryptedValue(item);
    }

    public List<ConfigDTO> queryConfigs(QueryConfigRequest request) {
        List<ConfigItem> items;
        
        if (request.getGroupId() != null && request.getEnvironment() != null) {
            items = configItemRepository.findByGroupIdAndEnvironmentAndDeletedFalse(
                    request.getGroupId(), request.getEnvironment());
        } else if (request.getGroupId() != null) {
            items = configItemRepository.findByGroupIdAndDeletedFalse(request.getGroupId());
        } else if (request.getEnvironment() != null) {
            items = configItemRepository.findByEnvironmentAndDeletedFalse(request.getEnvironment());
        } else {
            items = configItemRepository.findAll();
            items.removeIf(ConfigItem::getDeleted);
        }

        if (request.getConfigKey() != null) {
            items.removeIf(item -> !item.getConfigKey().contains(request.getConfigKey()));
        }

        List<ConfigDTO> result = new ArrayList<>();
        for (ConfigItem item : items) {
            result.add(EntityConverter.toConfigDTO(item));
        }
        return result;
    }
}
