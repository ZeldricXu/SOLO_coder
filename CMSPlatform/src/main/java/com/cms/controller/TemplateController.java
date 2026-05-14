package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.entity.Template;
import com.cms.service.TemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {

    @Autowired
    private TemplateService templateService;

    @PostMapping
    public ApiResponse<Template> createTemplate(@RequestBody Template template) {
        Template createdTemplate = templateService.createTemplate(template);
        return ApiResponse.success(createdTemplate);
    }

    @PutMapping("/{templateId}")
    public ApiResponse<Template> updateTemplate(@PathVariable String templateId, @RequestBody Template template) {
        Template updatedTemplate = templateService.updateTemplate(templateId, template);
        return ApiResponse.success(updatedTemplate);
    }

    @GetMapping("/{templateId}")
    public ApiResponse<Template> getTemplate(@PathVariable String templateId) {
        Template template = templateService.getTemplateById(templateId);
        return ApiResponse.success(template);
    }

    @GetMapping
    public ApiResponse<List<Template>> getAllTemplates() {
        List<Template> templates = templateService.getAllTemplates();
        return ApiResponse.success(templates);
    }

    @GetMapping("/active")
    public ApiResponse<List<Template>> getActiveTemplates() {
        List<Template> templates = templateService.getActiveTemplates();
        return ApiResponse.success(templates);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<Template>> getTemplatesByType(@PathVariable String type) {
        List<Template> templates = templateService.getTemplatesByType(type);
        return ApiResponse.success(templates);
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> deleteTemplate(@PathVariable String templateId) {
        templateService.deleteTemplate(templateId);
        return ApiResponse.success(null);
    }
}
