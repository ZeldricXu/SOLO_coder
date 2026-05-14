package com.configcenter.importexport.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.configcenter.common.dto.*;
import com.configcenter.common.entity.*;
import com.configcenter.common.enums.*;
import com.configcenter.common.exception.BusinessException;
import com.configcenter.common.util.EntityConverter;
import com.configcenter.config.repository.ConfigItemRepository;
import com.configcenter.config.service.ConfigManagementService;
import com.configcenter.importexport.dto.ImportExportDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportExportService {

    private final ConfigManagementService configManagementService;
    private final ConfigItemRepository configItemRepository;

    public String exportToJson(List<String> configIds, String exportedBy) {
        log.info("Exporting configs to JSON, count={}", configIds != null ? configIds.size() : 0);
        
        ImportExportDTO dto = new ImportExportDTO();
        dto.setVersion("1.0");
        dto.setExportTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        dto.setExportedBy(exportedBy);
        
        List<ImportExportDTO.ConfigItemExport> exports = new ArrayList<>();
        
        if (configIds != null) {
            for (String configId : configIds) {
                ConfigDTO config = configManagementService.getConfigById(configId);
                if (config != null) {
                    ImportExportDTO.ConfigItemExport item = new ImportExportDTO.ConfigItemExport();
                    item.setConfigKey(config.getConfigKey());
                    item.setConfigValue(config.getConfigValue());
                    item.setConfigType(config.getConfigType());
                    item.setIsEncrypted(config.getIsEncrypted());
                    item.setEnvironment(config.getEnvironment());
                    item.setGroupId(config.getGroupId());
                    item.setDescription(config.getDescription());
                    item.setCurrentVersion(config.getCurrentVersion());
                    exports.add(item);
                }
            }
        }
        
        dto.setConfigs(exports);
        
        String result = JSON.toJSONString(dto, true);
        log.info("Export completed, config count={}", exports.size());
        return result;
    }

    public String exportGroupToJson(String groupId, Environment environment, String exportedBy) {
        log.info("Exporting group configs: groupId={}, environment={}", groupId, environment);
        
        List<ConfigDTO> configs;
        if (environment != null) {
            configs = configManagementService.getConfigsByGroupAndEnvironment(groupId, environment);
        } else {
            configs = configManagementService.getConfigsByGroup(groupId);
        }
        
        List<String> configIds = new ArrayList<>();
        for (ConfigDTO config : configs) {
            configIds.add(config.getConfigId());
        }
        
        return exportToJson(configIds, exportedBy);
    }

    public String exportEnvironmentToJson(Environment environment, String exportedBy) {
        log.info("Exporting environment configs: environment={}", environment);
        
        List<ConfigDTO> configs = configManagementService.getConfigsByEnvironment(environment);
        
        List<String> configIds = new ArrayList<>();
        for (ConfigDTO config : configs) {
            configIds.add(config.getConfigId());
        }
        
        return exportToJson(configIds, exportedBy);
    }

    @Transactional
    public Map<String, Object> importFromJson(String jsonContent, String operator, boolean overwrite) {
        log.info("Importing configs from JSON, overwrite={}", overwrite);
        
        Map<String, Object> result = new HashMap<>();
        List<String> successList = new ArrayList<>();
        List<String> failedList = new ArrayList<>();
        List<String> skippedList = new ArrayList<>();
        
        try {
            ImportExportDTO dto = JSON.parseObject(jsonContent, ImportExportDTO.class);
            
            if (dto.getConfigs() == null || dto.getConfigs().isEmpty()) {
                log.warn("No configs found in import file");
                result.put("total", 0);
                result.put("success", 0);
                result.put("failed", 0);
                result.put("skipped", 0);
                result.put("successList", successList);
                result.put("failedList", failedList);
                result.put("skippedList", skippedList);
                return result;
            }
            
            for (ImportExportDTO.ConfigItemExport item : dto.getConfigs()) {
                try {
                    importConfigItem(item, operator, overwrite, successList, skippedList);
                } catch (Exception e) {
                    log.error("Failed to import config: {}", item.getConfigKey(), e);
                    failedList.add(item.getConfigKey() + ": " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            log.error("Import failed", e);
            throw new BusinessException("导入失败: " + e.getMessage(), e);
        }
        
        result.put("total", dto.getConfigs().size());
        result.put("success", successList.size());
        result.put("failed", failedList.size());
        result.put("skipped", skippedList.size());
        result.put("successList", successList);
        result.put("failedList", failedList);
        result.put("skippedList", skippedList);
        
        log.info("Import completed: total={}, success={}, failed={}, skipped={}",
                dto.getConfigs().size(), successList.size(), failedList.size(), skippedList.size());
        
        return result;
    }

    private void importConfigItem(ImportExportDTO.ConfigItemExport item, String operator, 
            boolean overwrite, List<String> successList, List<String> skippedList) {
        
        boolean exists = configItemRepository.existsByConfigKeyAndEnvironmentAndGroupIdAndDeletedFalse(
                item.getConfigKey(), item.getEnvironment(), item.getGroupId());
        
        if (exists && !overwrite) {
            log.info("Config already exists, skipping: {}", item.getConfigKey());
            skippedList.add(item.getConfigKey());
            return;
        }
        
        if (exists) {
            Optional<ConfigItem> existingOpt = configItemRepository.findByConfigKeyAndEnvironmentAndGroupId(
                    item.getConfigKey(), item.getEnvironment(), item.getGroupId());
            
            if (existingOpt.isPresent()) {
                ConfigItem existing = existingOpt.get();
                existing.setConfigValue(item.getConfigValue());
                existing.setConfigType(item.getConfigType());
                existing.setIsEncrypted(item.getIsEncrypted());
                existing.setDescription(item.getDescription());
                existing.setUpdatedBy(operator);
                configItemRepository.save(existing);
                log.info("Config updated: {}", item.getConfigKey());
                successList.add(item.getConfigKey());
                return;
            }
        }
        
        CreateConfigRequest request = new CreateConfigRequest();
        request.setConfigKey(item.getConfigKey());
        request.setConfigValue(item.getConfigValue());
        request.setConfigType(item.getConfigType());
        request.setIsEncrypted(item.getIsEncrypted());
        request.setEnvironment(item.getEnvironment());
        request.setGroupId(item.getGroupId());
        request.setDescription(item.getDescription());
        request.setOperator(operator);
        
        configManagementService.createConfig(request);
        log.info("Config created: {}", item.getConfigKey());
        successList.add(item.getConfigKey());
    }

    public String validateImportJson(String jsonContent) {
        try {
            JSONObject json = JSON.parseObject(jsonContent);
            
            if (!json.containsKey("configs")) {
                return "缺少 configs 字段";
            }
            
            return null;
        } catch (Exception e) {
            return "JSON格式错误: " + e.getMessage();
        }
    }
}
