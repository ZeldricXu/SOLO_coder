package com.survey.controller;

import com.survey.dto.ApiResponse;
import com.survey.dto.TemplateCreateRequest;
import com.survey.entity.SurveyTemplate;
import com.survey.service.SurveyTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final SurveyTemplateService templateService;

    @PostMapping
    public ApiResponse<SurveyTemplate> createTemplate(@Valid @RequestBody TemplateCreateRequest request) {
        SurveyTemplate template = templateService.createTemplate(request);
        return ApiResponse.success("模板创建成功", template);
    }

    @PutMapping("/{templateId}")
    public ApiResponse<SurveyTemplate> updateTemplate(
            @PathVariable String templateId,
            @Valid @RequestBody TemplateCreateRequest request) {
        SurveyTemplate template = templateService.updateTemplate(templateId, request);
        return ApiResponse.success("模板更新成功", template);
    }

    @PostMapping("/{templateId}/deactivate")
    public ApiResponse<Void> deactivateTemplate(@PathVariable String templateId) {
        templateService.deactivateTemplate(templateId);
        return ApiResponse.success("模板已停用", null);
    }

    @PostMapping("/{templateId}/activate")
    public ApiResponse<Void> activateTemplate(@PathVariable String templateId) {
        templateService.activateTemplate(templateId);
        return ApiResponse.success("模板已启用", null);
    }

    @GetMapping("/{templateId}")
    public ApiResponse<SurveyTemplate> getTemplate(@PathVariable String templateId) {
        SurveyTemplate template = templateService.getTemplate(templateId);
        return ApiResponse.success(template);
    }

    @GetMapping
    public ApiResponse<List<SurveyTemplate>> getAllTemplates() {
        List<SurveyTemplate> templates = templateService.getAllTemplates();
        return ApiResponse.success(templates);
    }

    @GetMapping("/active")
    public ApiResponse<List<SurveyTemplate>> getActiveTemplates() {
        List<SurveyTemplate> templates = templateService.getActiveTemplates();
        return ApiResponse.success(templates);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<SurveyTemplate>> getTemplatesByType(@PathVariable String type) {
        List<SurveyTemplate> templates = templateService.getTemplatesByType(type);
        return ApiResponse.success(templates);
    }
}
