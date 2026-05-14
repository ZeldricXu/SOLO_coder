package com.recruitment.controller;

import com.recruitment.common.dto.ApiResponse;
import com.recruitment.model.PositionTypeConfig;
import com.recruitment.service.PositionTypeConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/position-types")
@RequiredArgsConstructor
public class PositionTypeConfigController {

    private final PositionTypeConfigService positionTypeConfigService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<PositionTypeConfig>>> getAllPositionTypes(
            @RequestParam(required = false, defaultValue = "true") boolean enabledOnly) {
        log.info("API: 获取所有职位类型配置, enabledOnly: {}", enabledOnly);
        List<PositionTypeConfig> types = enabledOnly ?
                positionTypeConfigService.getAllEnabledTypes() :
                positionTypeConfigService.getAllTypes();
        return ResponseEntity.ok(ApiResponse.success(types));
    }

    @GetMapping("/{typeCode}")
    public ResponseEntity<ApiResponse<PositionTypeConfig>> getPositionType(@PathVariable String typeCode) {
        log.info("API: 获取职位类型, typeCode: {}", typeCode);
        PositionTypeConfig config = positionTypeConfigService.getPositionTypeByCode(typeCode)
                .orElseThrow(() -> new RuntimeException("职位类型不存在: " + typeCode));
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @GetMapping("/map")
    public ResponseEntity<ApiResponse<Map<String, String>>> getPositionTypeMap() {
        log.info("API: 获取职位类型映射");
        Map<String, String> typeMap = positionTypeConfigService.getPositionTypeMap();
        return ResponseEntity.ok(ApiResponse.success(typeMap));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PositionTypeConfig>> addPositionType(
            @RequestParam String typeCode,
            @RequestParam String typeName,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String interviewStages,
            @RequestParam(required = false) Integer sortOrder) {
        log.info("API: 添加新职位类型, code: {}, name: {}", typeCode, typeName);
        PositionTypeConfig config = positionTypeConfigService.addPositionType(
                typeCode, typeName, description, interviewStages, sortOrder);
        return ResponseEntity.ok(ApiResponse.success("职位类型添加成功", config));
    }

    @PutMapping("/{typeCode}")
    public ResponseEntity<ApiResponse<PositionTypeConfig>> updatePositionType(
            @PathVariable String typeCode,
            @RequestParam(required = false) String typeName,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String interviewStages,
            @RequestParam(required = false) Integer sortOrder,
            @RequestParam(required = false) Boolean isEnabled) {
        log.info("API: 更新职位类型, typeCode: {}", typeCode);
        PositionTypeConfig config = positionTypeConfigService.updatePositionType(
                typeCode, typeName, description, interviewStages, sortOrder, isEnabled);
        return ResponseEntity.ok(ApiResponse.success("职位类型更新成功", config));
    }

    @DeleteMapping("/{typeCode}")
    public ResponseEntity<ApiResponse<Void>> deletePositionType(@PathVariable String typeCode) {
        log.info("API: 删除职位类型, typeCode: {}", typeCode);
        positionTypeConfigService.deletePositionType(typeCode);
        return ResponseEntity.ok(ApiResponse.success("职位类型删除成功", null));
    }

    @PostMapping("/{typeCode}/enable")
    public ResponseEntity<ApiResponse<PositionTypeConfig>> enablePositionType(@PathVariable String typeCode) {
        log.info("API: 启用职位类型, typeCode: {}", typeCode);
        PositionTypeConfig config = positionTypeConfigService.enablePositionType(typeCode);
        return ResponseEntity.ok(ApiResponse.success("职位类型已启用", config));
    }

    @PostMapping("/{typeCode}/disable")
    public ResponseEntity<ApiResponse<PositionTypeConfig>> disablePositionType(@PathVariable String typeCode) {
        log.info("API: 禁用职位类型, typeCode: {}", typeCode);
        PositionTypeConfig config = positionTypeConfigService.disablePositionType(typeCode);
        return ResponseEntity.ok(ApiResponse.success("职位类型已禁用", config));
    }

    @GetMapping("/{typeCode}/stages")
    public ResponseEntity<ApiResponse<List<String>>> getInterviewStages(@PathVariable String typeCode) {
        log.info("API: 获取职位类型的面试阶段, typeCode: {}", typeCode);
        List<String> stages = positionTypeConfigService.getInterviewStagesForType(typeCode)
                .stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(stages));
    }

    @GetMapping("/validate/{typeCode}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validatePositionType(@PathVariable String typeCode) {
        log.info("API: 验证职位类型, typeCode: {}", typeCode);
        boolean isValid = positionTypeConfigService.isValidPositionType(typeCode);
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("typeCode", typeCode);
        result.put("isValid", isValid);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
