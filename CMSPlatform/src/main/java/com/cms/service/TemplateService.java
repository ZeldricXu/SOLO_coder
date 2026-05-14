package com.cms.service;

import com.cms.entity.Template;
import com.cms.exception.BusinessException;
import com.cms.repository.TemplateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TemplateService {

    @Autowired
    private TemplateRepository templateRepository;

    @Transactional
    public Template createTemplate(Template template) {
        if (templateRepository.findByTemplateName(template.getTemplateName()).isPresent()) {
            throw new BusinessException(400, "模板名称已存在");
        }

        Template newTemplate = new Template();
        newTemplate.setTemplateId("template_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        newTemplate.setTemplateName(template.getTemplateName());
        newTemplate.setTemplateType(template.getTemplateType() != null ? template.getTemplateType() : "default");
        newTemplate.setTemplateContent(template.getTemplateContent());
        newTemplate.setTemplateCss(template.getTemplateCss());
        newTemplate.setTemplateJs(template.getTemplateJs());
        newTemplate.setTemplateDescription(template.getTemplateDescription());
        newTemplate.setTemplateStatus(template.getTemplateStatus() != null ? template.getTemplateStatus() : "active");
        newTemplate.setUseCount(0L);
        newTemplate.setCreatedBy(template.getCreatedBy());

        return templateRepository.save(newTemplate);
    }

    @Transactional
    public Template updateTemplate(String templateId, Template template) {
        Template existingTemplate = getTemplateById(templateId);

        if (template.getTemplateName() != null) {
            Optional<Template> duplicate = templateRepository.findByTemplateName(template.getTemplateName());
            if (duplicate.isPresent() && !duplicate.get().getTemplateId().equals(templateId)) {
                throw new BusinessException(400, "模板名称已存在");
            }
            existingTemplate.setTemplateName(template.getTemplateName());
        }
        if (template.getTemplateType() != null) {
            existingTemplate.setTemplateType(template.getTemplateType());
        }
        if (template.getTemplateContent() != null) {
            existingTemplate.setTemplateContent(template.getTemplateContent());
        }
        if (template.getTemplateCss() != null) {
            existingTemplate.setTemplateCss(template.getTemplateCss());
        }
        if (template.getTemplateJs() != null) {
            existingTemplate.setTemplateJs(template.getTemplateJs());
        }
        if (template.getTemplateDescription() != null) {
            existingTemplate.setTemplateDescription(template.getTemplateDescription());
        }
        if (template.getTemplateStatus() != null) {
            existingTemplate.setTemplateStatus(template.getTemplateStatus());
        }

        return templateRepository.save(existingTemplate);
    }

    public Template getTemplateById(String templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(404, "模板不存在"));
    }

    public List<Template> getAllTemplates() {
        return templateRepository.findAll();
    }

    public List<Template> getActiveTemplates() {
        return templateRepository.findByTemplateStatus("active");
    }

    public List<Template> getTemplatesByType(String type) {
        return templateRepository.findByTemplateType(type);
    }

    @Transactional
    public void deleteTemplate(String templateId) {
        Template template = getTemplateById(templateId);
        if (template.getUseCount() > 0) {
            throw new BusinessException(400, "该模板已被使用，无法删除");
        }
        templateRepository.delete(template);
    }

    @Transactional
    public void incrementUseCount(String templateId) {
        Template template = getTemplateById(templateId);
        template.setUseCount(template.getUseCount() + 1);
        templateRepository.save(template);
    }

    @Transactional
    public void decrementUseCount(String templateId) {
        Template template = getTemplateById(templateId);
        template.setUseCount(Math.max(0, template.getUseCount() - 1));
        templateRepository.save(template);
    }
}
