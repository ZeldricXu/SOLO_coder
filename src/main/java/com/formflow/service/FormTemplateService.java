package com.formflow.service;

import com.formflow.entity.FormTemplate;
import com.formflow.entity.FormTemplateField;
import com.formflow.enums.FieldType;
import com.formflow.exception.BusinessException;
import com.formflow.repository.FormTemplateRepository;
import com.formflow.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class FormTemplateService {

    private static final Logger logger = LoggerFactory.getLogger(FormTemplateService.class);

    @Autowired
    private FormTemplateRepository formTemplateRepository;

    public FormTemplate getTemplateByTemplateId(String templateId) {
        return formTemplateRepository.findByTemplateId(templateId)
                .orElseThrow(() -> new BusinessException(404, "表单模板不存在: " + templateId));
    }

    public FormTemplate getEnabledTemplate(String templateId) {
        return formTemplateRepository.findByTemplateIdAndEnabledTrue(templateId)
                .orElseThrow(() -> new BusinessException(404, "表单模板不存在或已禁用: " + templateId));
    }

    public List<FormTemplate> getAllTemplates() {
        return formTemplateRepository.findAll();
    }

    public List<FormTemplate> getEnabledTemplates() {
        return formTemplateRepository.findByEnabledTrue();
    }

    @Transactional
    public FormTemplate createTemplate(FormTemplate template, String creatorId, String creatorName) {
        if (formTemplateRepository.existsByTemplateId(template.getTemplateId())) {
            throw new BusinessException("模板ID已存在: " + template.getTemplateId());
        }

        if (template.getTemplateId() == null || template.getTemplateId().isEmpty()) {
            template.setTemplateId(IdGenerator.generateTemplateId(null));
        }

        template.setCreatorId(creatorId);
        template.setCreatorName(creatorName);
        template.setVersion(1);
        template.setEnabled(true);

        validateFields(template.getFields());

        FormTemplate saved = formTemplateRepository.save(template);
        logger.info("创建表单模板成功: {}", saved.getTemplateId());
        return saved;
    }

    @Transactional
    public FormTemplate updateTemplate(String templateId, FormTemplate template) {
        FormTemplate existing = getTemplateByTemplateId(templateId);

        existing.setTemplateName(template.getTemplateName());
        existing.setDescription(template.getDescription());
        existing.setFields(template.getFields());
        existing.setProcessDefinitionId(template.getProcessDefinitionId());
        existing.setVersion(existing.getVersion() + 1);

        validateFields(existing.getFields());

        FormTemplate saved = formTemplateRepository.save(existing);
        logger.info("更新表单模板成功: {}", templateId);
        return saved;
    }

    @Transactional
    public void deleteTemplate(String templateId) {
        FormTemplate template = getTemplateByTemplateId(templateId);
        formTemplateRepository.delete(template);
        logger.info("删除表单模板成功: {}", templateId);
    }

    @Transactional
    public FormTemplate enableTemplate(String templateId) {
        FormTemplate template = getTemplateByTemplateId(templateId);
        template.setEnabled(true);
        FormTemplate saved = formTemplateRepository.save(template);
        logger.info("启用表单模板成功: {}", templateId);
        return saved;
    }

    @Transactional
    public FormTemplate disableTemplate(String templateId) {
        FormTemplate template = getTemplateByTemplateId(templateId);
        template.setEnabled(false);
        FormTemplate saved = formTemplateRepository.save(template);
        logger.info("禁用表单模板成功: {}", templateId);
        return saved;
    }

    public void validateFormData(String templateId, Map<String, Object> formData) {
        FormTemplate template = getEnabledTemplate(templateId);
        validateFormData(template, formData);
    }

    public void validateFormData(FormTemplate template, Map<String, Object> formData) {
        if (formData == null) {
            throw new BusinessException("表单数据不能为空");
        }

        for (FormTemplateField field : template.getFields()) {
            String fieldId = field.getFieldId();
            Object value = formData.get(fieldId);

            if (Boolean.TRUE.equals(field.getRequired())) {
                if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
                    throw new BusinessException("字段 " + field.getFieldName() + " 不能为空");
                }
            }

            if (value != null) {
                validateFieldType(field, value);
            }
        }
    }

    private void validateFieldType(FormTemplateField field, Object value) {
        FieldType fieldType = field.getFieldType();
        String fieldName = field.getFieldName();

        switch (fieldType) {
            case NUMBER:
                if (!(value instanceof Number) && !(value instanceof String)) {
                    throw new BusinessException("字段 " + fieldName + " 必须是数字类型");
                }
                if (value instanceof String) {
                    try {
                        Double.parseDouble((String) value);
                    } catch (NumberFormatException e) {
                        throw new BusinessException("字段 " + fieldName + " 必须是有效的数字");
                    }
                }
                break;
            case DATE:
            case DATETIME:
                if (!(value instanceof String)) {
                    throw new BusinessException("字段 " + fieldName + " 必须是日期字符串");
                }
                break;
            case TEXT:
            case TEXTAREA:
            case SELECT:
            case RADIO:
            case CHECKBOX:
            case FILE:
            case USER:
            case DEPARTMENT:
                if (!(value instanceof String) && !(value instanceof List)) {
                    throw new BusinessException("字段 " + fieldName + " 类型不合法");
                }
                break;
            default:
                break;
        }
    }

    private void validateFields(List<FormTemplateField> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }

        for (int i = 0; i < fields.size(); i++) {
            FormTemplateField field = fields.get(i);
            if (field.getFieldId() == null || field.getFieldId().isEmpty()) {
                throw new BusinessException("字段ID不能为空，索引: " + i);
            }
            if (field.getFieldName() == null || field.getFieldName().isEmpty()) {
                throw new BusinessException("字段名称不能为空，索引: " + i);
            }
            if (field.getFieldType() == null) {
                throw new BusinessException("字段类型不能为空，索引: " + i);
            }
            if (field.getSortOrder() == null) {
                field.setSortOrder(i);
            }
            if (field.getRequired() == null) {
                field.setRequired(false);
            }
        }
    }
}
