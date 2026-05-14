package com.formflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formflow.entity.FormData;
import com.formflow.entity.FormTemplate;
import com.formflow.enums.FormStatus;
import com.formflow.exception.BusinessException;
import com.formflow.repository.FormDataRepository;
import com.formflow.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class FormDataService {

    private static final Logger logger = LoggerFactory.getLogger(FormDataService.class);

    @Autowired
    private FormDataRepository formDataRepository;

    @Autowired
    private FormTemplateService formTemplateService;

    @Autowired
    private ObjectMapper objectMapper;

    public FormData getFormByFormId(String formId) {
        return formDataRepository.findByFormId(formId)
                .orElseThrow(() -> new BusinessException(404, "表单数据不存在: " + formId));
    }

    public FormData getFormByInstanceId(String instanceId) {
        return formDataRepository.findByProcessInstanceId(instanceId)
                .orElseThrow(() -> new BusinessException(404, "表单数据不存在，流程实例: " + instanceId));
    }

    public List<FormData> getFormsBySubmitterId(String submitterId) {
        return formDataRepository.findBySubmitterIdOrderBySubmitTimeDesc(submitterId);
    }

    public List<FormData> getFormsByTemplateId(String templateId) {
        return formDataRepository.findByTemplateId(templateId);
    }

    public List<FormData> getFormsByTemplateIdAndSubmitterId(String templateId, String submitterId) {
        return formDataRepository.findByTemplateIdAndSubmitterId(templateId, submitterId);
    }

    public List<FormData> getFormsByStatus(FormStatus status) {
        return formDataRepository.findByStatus(status);
    }

    @Transactional
    public FormData createDraft(String templateId, Map<String, Object> formDataMap,
                                String submitterId, String submitterName, String remark) {
        FormTemplate template = formTemplateService.getEnabledTemplate(templateId);

        FormData formData = new FormData();
        formData.setFormId(IdGenerator.generateFormId());
        formData.setTemplateId(templateId);
        formData.setSubmitterId(submitterId);
        formData.setSubmitterName(submitterName);
        formData.setStatus(FormStatus.DRAFT);
        formData.setRemark(remark);

        if (formDataMap != null && !formDataMap.isEmpty()) {
            try {
                formData.setFormData(objectMapper.writeValueAsString(formDataMap));
            } catch (JsonProcessingException e) {
                throw new BusinessException("表单数据序列化失败");
            }
        }

        FormData saved = formDataRepository.save(formData);
        logger.info("创建表单草稿成功: {}", saved.getFormId());
        return saved;
    }

    @Transactional
    public FormData submitForm(String templateId, Map<String, Object> formDataMap,
                               String submitterId, String submitterName, String remark) {
        FormTemplate template = formTemplateService.getEnabledTemplate(templateId);

        formTemplateService.validateFormData(template, formDataMap);

        FormData formData = new FormData();
        formData.setFormId(IdGenerator.generateFormId());
        formData.setTemplateId(templateId);
        formData.setSubmitterId(submitterId);
        formData.setSubmitterName(submitterName);
        formData.setStatus(FormStatus.PENDING_APPROVAL);
        formData.setRemark(remark);

        try {
            formData.setFormData(objectMapper.writeValueAsString(formDataMap));
        } catch (JsonProcessingException e) {
            throw new BusinessException("表单数据序列化失败");
        }

        FormData saved = formDataRepository.save(formData);
        logger.info("提交表单成功: {}", saved.getFormId());
        return saved;
    }

    @Transactional
    public FormData updateDraft(String formId, Map<String, Object> formDataMap, String remark) {
        FormData formData = getFormByFormId(formId);

        if (formData.getStatus() != FormStatus.DRAFT) {
            throw new BusinessException("只能修改草稿状态的表单");
        }

        if (formDataMap != null && !formDataMap.isEmpty()) {
            try {
                formData.setFormData(objectMapper.writeValueAsString(formDataMap));
            } catch (JsonProcessingException e) {
                throw new BusinessException("表单数据序列化失败");
            }
        }

        if (remark != null) {
            formData.setRemark(remark);
        }

        FormData saved = formDataRepository.save(formData);
        logger.info("更新表单草稿成功: {}", formId);
        return saved;
    }

    @Transactional
    public FormData submitDraft(String formId) {
        FormData formData = getFormByFormId(formId);

        if (formData.getStatus() != FormStatus.DRAFT) {
            throw new BusinessException("只能提交草稿状态的表单");
        }

        FormTemplate template = formTemplateService.getEnabledTemplate(formData.getTemplateId());

        Map<String, Object> formDataMap;
        try {
            formDataMap = objectMapper.readValue(formData.getFormData(), Map.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException("表单数据解析失败");
        }

        formTemplateService.validateFormData(template, formDataMap);

        formData.setStatus(FormStatus.PENDING_APPROVAL);

        FormData saved = formDataRepository.save(formData);
        logger.info("提交表单草稿成功: {}", formId);
        return saved;
    }

    @Transactional
    public void updateFormStatus(String formId, FormStatus status) {
        FormData formData = getFormByFormId(formId);
        formData.setStatus(status);

        if (status == FormStatus.APPROVED || status == FormStatus.REJECTED) {
            formData.setCompletedTime(LocalDateTime.now());
        }

        formDataRepository.save(formData);
        logger.info("更新表单状态成功: formId={}, status={}", formId, status);
    }

    @Transactional
    public void updateProcessInstanceId(String formId, String instanceId) {
        FormData formData = getFormByFormId(formId);
        formData.setProcessInstanceId(instanceId);
        formDataRepository.save(formData);
        logger.info("更新表单流程实例ID成功: formId={}, instanceId={}", formId, instanceId);
    }

    @Transactional
    public void updateCurrentApprovers(String formId, String approverIds) {
        FormData formData = getFormByFormId(formId);
        formData.setCurrentApproverIds(approverIds);
        formDataRepository.save(formData);
    }

    @Transactional
    public void deleteForm(String formId) {
        FormData formData = getFormByFormId(formId);
        if (formData.getStatus() != FormStatus.DRAFT) {
            throw new BusinessException("只能删除草稿状态的表单");
        }
        formDataRepository.delete(formData);
        logger.info("删除表单成功: {}", formId);
    }

    public Map<String, Object> getFormDataAsMap(FormData formData) {
        if (formData.getFormData() == null || formData.getFormData().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(formData.getFormData(), Map.class);
        } catch (JsonProcessingException e) {
            logger.warn("解析表单数据失败: {}", e.getMessage());
            return null;
        }
    }
}
