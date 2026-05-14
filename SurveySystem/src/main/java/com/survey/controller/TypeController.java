package com.survey.controller;

import com.survey.dto.ApiResponse;
import com.survey.dto.TypeCreateRequest;
import com.survey.entity.SurveyType;
import com.survey.service.SurveyTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/types")
@RequiredArgsConstructor
public class TypeController {

    private final SurveyTypeService typeService;

    @PostMapping
    public ApiResponse<SurveyType> createType(@Valid @RequestBody TypeCreateRequest request) {
        SurveyType type = typeService.createType(request);
        return ApiResponse.success("类型创建成功", type);
    }

    @PutMapping("/{typeCode}")
    public ApiResponse<SurveyType> updateType(
            @PathVariable String typeCode,
            @Valid @RequestBody TypeCreateRequest request) {
        SurveyType type = typeService.updateType(typeCode, request);
        return ApiResponse.success("类型更新成功", type);
    }

    @DeleteMapping("/{typeCode}")
    public ApiResponse<Void> deleteType(@PathVariable String typeCode) {
        typeService.deleteType(typeCode);
        return ApiResponse.success("类型已删除", null);
    }

    @PostMapping("/{typeCode}/deactivate")
    public ApiResponse<Void> deactivateType(@PathVariable String typeCode) {
        typeService.deactivateType(typeCode);
        return ApiResponse.success("类型已停用", null);
    }

    @PostMapping("/{typeCode}/activate")
    public ApiResponse<Void> activateType(@PathVariable String typeCode) {
        typeService.activateType(typeCode);
        return ApiResponse.success("类型已启用", null);
    }

    @GetMapping("/{typeCode}")
    public ApiResponse<SurveyType> getType(@PathVariable String typeCode) {
        SurveyType type = typeService.getType(typeCode);
        return ApiResponse.success(type);
    }

    @GetMapping
    public ApiResponse<List<SurveyType>> getAllTypes() {
        List<SurveyType> types = typeService.getAllTypes();
        return ApiResponse.success(types);
    }

    @GetMapping("/active")
    public ApiResponse<List<SurveyType>> getActiveTypes() {
        List<SurveyType> types = typeService.getActiveTypes();
        return ApiResponse.success(types);
    }

    @GetMapping("/category/{category}")
    public ApiResponse<List<SurveyType>> getTypesByCategory(@PathVariable String category) {
        List<SurveyType> types = typeService.getTypesByCategory(category);
        return ApiResponse.success(types);
    }

    @GetMapping("/system")
    public ApiResponse<List<SurveyType>> getSystemTypes() {
        List<SurveyType> types = typeService.getSystemTypes();
        return ApiResponse.success(types);
    }

    @GetMapping("/custom")
    public ApiResponse<List<SurveyType>> getCustomTypes() {
        List<SurveyType> types = typeService.getCustomTypes();
        return ApiResponse.success(types);
    }

    @GetMapping("/{typeCode}/exists")
    public ApiResponse<Map<String, Object>> checkTypeExists(@PathVariable String typeCode) {
        Map<String, Object> result = new HashMap<>();
        result.put("typeCode", typeCode);
        result.put("exists", typeService.typeExists(typeCode));
        result.put("isSystem", typeService.typeExists(typeCode) && typeService.isSystemType(typeCode));
        return ApiResponse.success(result);
    }

    @PostMapping("/reload")
    public ApiResponse<Void> reloadTypes() {
        typeService.initializeFromConfig();
        return ApiResponse.success("类型配置已重新加载", null);
    }
}
