package com.configcenter.api.controller;

import com.configcenter.api.service.ConfigFacadeService;
import com.configcenter.common.dto.*;
import com.configcenter.common.enums.AuditOperation;
import com.configcenter.common.enums.Environment;
import com.configcenter.common.enums.PushStatus;
import com.configcenter.group.service.GroupManagementService;
import com.configcenter.importexport.service.ImportExportService;
import com.configcenter.validation.service.ValidationRuleService;
import com.configcenter.version.service.VersionCompressionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/configs")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigFacadeService configFacadeService;
    private final GroupManagementService groupManagementService;
    private final ImportExportService importExportService;
    private final ValidationRuleService validationRuleService;
    private final VersionCompressionService versionCompressionService;

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createConfig(
            @Valid @RequestBody CreateConfigRequest request,
            HttpServletRequest httpRequest) {
        Map<String, Object> result = configFacadeService.createConfig(request, getClientIp(httpRequest));
        return ApiResponse.success(result);
    }

    @PostMapping("/update")
    public ApiResponse<Map<String, Object>> updateConfig(
            @Valid @RequestBody UpdateConfigRequest request,
            HttpServletRequest httpRequest) {
        Map<String, Object> result = configFacadeService.updateConfig(request, getClientIp(httpRequest));
        return ApiResponse.success(result);
    }

    @PostMapping("/rollback")
    public ApiResponse<Map<String, Object>> rollbackConfig(
            @Valid @RequestBody RollbackConfigRequest request,
            HttpServletRequest httpRequest) {
        Map<String, Object> result = configFacadeService.rollbackConfig(request, getClientIp(httpRequest));
        return ApiResponse.success(result);
    }

    @GetMapping("/query")
    public ApiResponse<List<ConfigDTO>> queryConfigs(
            @RequestParam(required = false) String application,
            @RequestParam(required = false) Environment environment,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) String configKey) {
        QueryConfigRequest request = new QueryConfigRequest();
        request.setApplication(application);
        request.setEnvironment(environment);
        request.setGroupId(groupId);
        request.setConfigKey(configKey);
        
        List<ConfigDTO> configs = configFacadeService.queryConfigs(request);
        return ApiResponse.success(configs);
    }

    @GetMapping("/{configId}")
    public ApiResponse<ConfigDTO> getConfigDetail(@PathVariable String configId) {
        ConfigDTO config = configFacadeService.getConfigDetail(configId);
        return ApiResponse.success(config);
    }

    @GetMapping("/{configId}/versions")
    public ApiResponse<List<VersionDTO>> getVersionHistory(@PathVariable String configId) {
        List<VersionDTO> versions = configFacadeService.getVersionHistory(configId);
        return ApiResponse.success(versions);
    }

    @GetMapping("/{configId}/versions/{version}")
    public ApiResponse<VersionDTO> getVersion(
            @PathVariable String configId,
            @PathVariable String version) {
        VersionDTO versionDTO = configFacadeService.getVersion(configId, version);
        return ApiResponse.success(versionDTO);
    }

    @PostMapping("/{configId}/push")
    public ApiResponse<Map<String, Object>> pushConfig(
            @PathVariable String configId,
            @RequestParam(defaultValue = "system") String operator,
            @RequestParam(required = false) Boolean async,
            HttpServletRequest httpRequest) {
        Map<String, Object> result = configFacadeService.pushConfig(configId, operator, getClientIp(httpRequest), async);
        return ApiResponse.success(result);
    }

    @GetMapping("/push/{pushId}")
    public ApiResponse<PushResultDTO> getPushStatus(@PathVariable String pushId) {
        PushResultDTO result = configFacadeService.getPushStatus(pushId);
        return ApiResponse.success(result);
    }

    @GetMapping("/{configId}/push-history")
    public ApiResponse<List<PushResultDTO>> getPushHistory(@PathVariable String configId) {
        List<PushResultDTO> history = configFacadeService.getPushHistory(configId);
        return ApiResponse.success(history);
    }

    @GetMapping("/{configId}/audit")
    public ApiResponse<List<AuditRecordDTO>> getAuditRecords(@PathVariable String configId) {
        List<AuditRecordDTO> records = configFacadeService.getAuditRecords(configId);
        return ApiResponse.success(records);
    }

    @GetMapping("/{configId}/audit/statistics")
    public ApiResponse<Map<String, Object>> getAuditStatistics(@PathVariable String configId) {
        Map<String, Object> stats = configFacadeService.getAuditStatistics(configId);
        return ApiResponse.success(stats);
    }

    @PostMapping("/groups")
    public ApiResponse<GroupDTO> createGroup(
            @Valid @RequestBody CreateGroupRequest request) {
        GroupDTO group = groupManagementService.createGroup(request);
        return ApiResponse.success(group);
    }

    @GetMapping("/groups")
    public ApiResponse<List<GroupDTO>> getGroups(
            @RequestParam(required = false) Environment environment) {
        List<GroupDTO> groups;
        if (environment != null) {
            groups = groupManagementService.getGroupsByEnvironment(environment);
        } else {
            groups = groupManagementService.getAllGroups();
        }
        return ApiResponse.success(groups);
    }

    @GetMapping("/groups/{groupId}")
    public ApiResponse<GroupDTO> getGroup(@PathVariable String groupId) {
        GroupDTO group = groupManagementService.getGroupById(groupId);
        return ApiResponse.success(group);
    }

    @PostMapping("/groups/{groupId}/applications/{application}")
    public ApiResponse<GroupDTO> addApplication(
            @PathVariable String groupId,
            @PathVariable String application) {
        GroupDTO group = groupManagementService.addApplication(groupId, application);
        return ApiResponse.success(group);
    }

    @DeleteMapping("/groups/{groupId}/applications/{application}")
    public ApiResponse<GroupDTO> removeApplication(
            @PathVariable String groupId,
            @PathVariable String application) {
        GroupDTO group = groupManagementService.removeApplication(groupId, application);
        return ApiResponse.success(group);
    }

    @PostMapping("/groups/{groupId}/parallelism")
    public ApiResponse<GroupDTO> setGroupParallelism(
            @PathVariable String groupId,
            @RequestParam Integer parallelPushCount,
            @RequestParam(defaultValue = "system") String operator) {
        GroupDTO group = groupManagementService.setParallelPushCount(groupId, parallelPushCount, operator);
        return ApiResponse.success(group);
    }

    @PostMapping("/groups/{groupId}/type")
    public ApiResponse<GroupDTO> setGroupType(
            @PathVariable String groupId,
            @RequestParam String groupType,
            @RequestParam(defaultValue = "system") String operator) {
        GroupDTO group = groupManagementService.setGroupType(groupId, groupType, operator);
        return ApiResponse.success(group);
    }

    @GetMapping("/groups/{groupId}/statistics")
    public ApiResponse<Map<String, Object>> getGroupStatistics(@PathVariable String groupId) {
        Map<String, Object> stats = groupManagementService.getGroupStatistics(groupId);
        return ApiResponse.success(stats);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportConfigs(
            @RequestParam(required = false) List<String> configIds,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) Environment environment,
            @RequestParam(defaultValue = "system") String exportedBy) {
        
        String json;
        if (configIds != null && !configIds.isEmpty()) {
            json = importExportService.exportToJson(configIds, exportedBy);
        } else if (groupId != null) {
            json = importExportService.exportGroupToJson(groupId, environment, exportedBy);
        } else if (environment != null) {
            json = importExportService.exportEnvironmentToJson(environment, exportedBy);
        } else {
            throw new IllegalArgumentException("请指定configIds、groupId或environment参数");
        }

        byte[] data = json.getBytes();
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=configs-export.json")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(data.length)
                .body(data);
    }

    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importConfigs(
            @RequestBody String jsonContent,
            @RequestParam(defaultValue = "false") boolean overwrite,
            @RequestParam(defaultValue = "system") String operator) {
        
        String validationError = importExportService.validateImportJson(jsonContent);
        if (validationError != null) {
            return ApiResponse.error(400, validationError);
        }

        Map<String, Object> result = importExportService.importFromJson(jsonContent, operator, overwrite);
        return ApiResponse.success(result);
    }

    @GetMapping("/{configId}/compression/statistics")
    public ApiResponse<Map<String, Object>> getCompressionStatistics(@PathVariable String configId) {
        Map<String, Object> stats = versionCompressionService.getCompressionStatistics(configId);
        return ApiResponse.success(stats);
    }

    @GetMapping("/{configId}/compression/policy")
    public ApiResponse<Map<String, Object>> getCompressionPolicy(@PathVariable String configId) {
        Map<String, Object> policy = versionCompressionService.getCompressionPolicyInfo(configId);
        return ApiResponse.success(policy);
    }

    @PostMapping("/{configId}/compression/run")
    public ApiResponse<Map<String, Object>> runCompression(
            @PathVariable String configId,
            @RequestParam(defaultValue = "system") String operator) {
        Map<String, Object> result = versionCompressionService.compressVersions(configId, operator);
        return ApiResponse.success(result);
    }

    @GetMapping("/{configId}/compression/archives")
    public ApiResponse<List<com.configcenter.version.entity.VersionCompressionArchive>> getArchives(@PathVariable String configId) {
        List<com.configcenter.version.entity.VersionCompressionArchive> archives = versionCompressionService.getArchives(configId);
        return ApiResponse.success(archives);
    }

    @PostMapping("/{configId}/compression/restore/{archiveId}")
    public ApiResponse<List<com.configcenter.common.entity.ConfigVersion>> restoreArchive(
            @PathVariable String configId,
            @PathVariable String archiveId) {
        List<com.configcenter.common.entity.ConfigVersion> versions = versionCompressionService.restoreAndSaveVersions(configId, archiveId);
        return ApiResponse.success(versions);
    }

    @GetMapping("/validation/rules")
    public ApiResponse<List<Map<String, Object>>> getValidationRules() {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (com.configcenter.validation.rule.ValidationRule rule : validationRuleService.getAllRules()) {
            Map<String, Object> ruleInfo = new HashMap<>();
            ruleInfo.put("ruleId", rule.getRuleId());
            ruleInfo.put("ruleType", rule.getRuleType());
            ruleInfo.put("name", rule.getName());
            ruleInfo.put("description", rule.getDescription());
            ruleInfo.put("enabled", rule.isEnabled());
            ruleInfo.put("priority", rule.getPriority());
            rules.add(ruleInfo);
        }
        return ApiResponse.success(rules);
    }

    @PostMapping("/validation/validate")
    public ApiResponse<Map<String, Object>> validateValue(
            @RequestParam String value,
            @RequestParam(required = false) List<String> ruleIds,
            @RequestParam(required = false) String configId,
            @RequestParam(required = false) String configKey) {
        Map<String, Object> result;
        if (configId != null || configKey != null) {
            result = validationRuleService.validateForConfig(value, configId, configKey, null);
        } else if (ruleIds != null && !ruleIds.isEmpty()) {
            result = validationRuleService.validateWithRules(value, ruleIds, null);
        } else {
            result = validationRuleService.validateWithRules(value, null, null);
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/validation/statistics")
    public ApiResponse<Map<String, Object>> getValidationStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("rules", validationRuleService.getRuleStatistics());
        stats.put("configRules", validationRuleService.getConfigRuleStatistics());
        return ApiResponse.success(stats);
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
