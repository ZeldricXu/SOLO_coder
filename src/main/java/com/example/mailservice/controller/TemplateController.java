package com.example.mailservice.controller;

import com.example.mailservice.dto.ApiResponse;
import com.example.mailservice.model.MailTemplate;
import com.example.mailservice.service.TemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/mail/template")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @PostMapping
    public ApiResponse<MailTemplate> createTemplate(@RequestBody MailTemplate template) {
        log.info("创建邮件模板: {}", template.getTemplateName());
        return ApiResponse.success(templateService.createTemplate(template));
    }

    @GetMapping
    public ApiResponse<List<MailTemplate>> getAllTemplates() {
        return ApiResponse.success(templateService.getAllEnabledTemplates());
    }

    @GetMapping("/{templateId}")
    public ApiResponse<MailTemplate> getTemplate(@PathVariable String templateId) {
        return templateService.getTemplateByTemplateId(templateId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "模板不存在"));
    }

    @PutMapping("/{templateId}")
    public ApiResponse<MailTemplate> updateTemplate(
            @PathVariable String templateId,
            @RequestBody MailTemplate template) {
        MailTemplate updated = templateService.updateTemplate(templateId, template);
        if (updated == null) {
            return ApiResponse.error(404, "模板不存在");
        }
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{templateId}")
    public ApiResponse<String> deleteTemplate(@PathVariable String templateId) {
        templateService.deleteTemplate(templateId);
        return ApiResponse.success(null, "模板删除成功");
    }

    @PostMapping("/{templateId}/render")
    public ApiResponse<TemplateService.RenderedTemplate> renderTemplate(
            @PathVariable String templateId,
            @RequestBody Map<String, String> variables) {
        TemplateService.RenderedTemplate rendered = templateService.renderTemplate(templateId, variables);
        if (rendered == null) {
            return ApiResponse.error(404, "模板不存在");
        }
        return ApiResponse.success(rendered);
    }
}
