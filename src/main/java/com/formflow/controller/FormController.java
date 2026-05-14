package com.formflow.controller;

import com.formflow.common.ApiResponse;
import com.formflow.dto.FormSubmitRequest;
import com.formflow.dto.FormSubmitResponse;
import com.formflow.entity.FormData;
import com.formflow.entity.FormTemplate;
import com.formflow.service.FormDataService;
import com.formflow.service.FormTemplateService;
import com.formflow.service.ProcessEngineService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/forms")
public class FormController {

    private static final Logger logger = LoggerFactory.getLogger(FormController.class);

    @Autowired
    private FormTemplateService formTemplateService;

    @Autowired
    private FormDataService formDataService;

    @Autowired
    private ProcessEngineService processEngineService;

    @PostMapping("/submit")
    public ApiResponse<FormSubmitResponse> submitForm(@Valid @RequestBody FormSubmitRequest request) {
        logger.info("接收表单提交请求: templateId={}", request.getTemplateId());

        if (request.getSubmitterId() == null || request.getSubmitterId().isEmpty()) {
            request.setSubmitterId("anonymous_user");
        }

        FormSubmitResponse response = processEngineService.submitFormAndStartProcess(request);
        return ApiResponse.success("表单提交成功", response);
    }

    @GetMapping("/templates")
    public ApiResponse<List<FormTemplate>> getFormTemplates() {
        logger.info("查询表单模板列表");
        List<FormTemplate> templates = formTemplateService.getEnabledTemplates();
        return ApiResponse.success(templates);
    }

    @GetMapping("/templates/{templateId}")
    public ApiResponse<FormTemplate> getFormTemplate(@PathVariable String templateId) {
        logger.info("查询表单模板: templateId={}", templateId);
        FormTemplate template = formTemplateService.getEnabledTemplate(templateId);
        return ApiResponse.success(template);
    }

    @PostMapping("/templates")
    public ApiResponse<FormTemplate> createFormTemplate(@RequestBody FormTemplate template,
                                                         @RequestHeader(value = "X-User-Id", required = false) String userId,
                                                         @RequestHeader(value = "X-User-Name", required = false) String userName) {
        logger.info("创建表单模板: templateName={}", template.getTemplateName());
        FormTemplate created = formTemplateService.createTemplate(template, userId, userName);
        return ApiResponse.success("模板创建成功", created);
    }

    @PutMapping("/templates/{templateId}")
    public ApiResponse<FormTemplate> updateFormTemplate(@PathVariable String templateId,
                                                         @RequestBody FormTemplate template) {
        logger.info("更新表单模板: templateId={}", templateId);
        FormTemplate updated = formTemplateService.updateTemplate(templateId, template);
        return ApiResponse.success("模板更新成功", updated);
    }

    @DeleteMapping("/templates/{templateId}")
    public ApiResponse<Void> deleteFormTemplate(@PathVariable String templateId) {
        logger.info("删除表单模板: templateId={}", templateId);
        formTemplateService.deleteTemplate(templateId);
        return ApiResponse.success("模板删除成功", null);
    }

    @GetMapping("/my/{userId}")
    public ApiResponse<List<FormData>> getMyForms(@PathVariable String userId) {
        logger.info("查询用户表单: userId={}", userId);
        List<FormData> forms = formDataService.getFormsBySubmitterId(userId);
        return ApiResponse.success(forms);
    }

    @GetMapping("/{formId}")
    public ApiResponse<FormData> getFormDetail(@PathVariable String formId) {
        logger.info("查询表单详情: formId={}", formId);
        FormData formData = formDataService.getFormByFormId(formId);
        return ApiResponse.success(formData);
    }

    @GetMapping("/{formId}/data")
    public ApiResponse<Map<String, Object>> getFormData(@PathVariable String formId) {
        logger.info("查询表单数据: formId={}", formId);
        FormData formData = formDataService.getFormByFormId(formId);
        Map<String, Object> dataMap = formDataService.getFormDataAsMap(formData);
        return ApiResponse.success(dataMap);
    }
}
