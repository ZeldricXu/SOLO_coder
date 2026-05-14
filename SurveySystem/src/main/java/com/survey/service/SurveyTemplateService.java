package com.survey.service;

import com.survey.common.SurveyConstants;
import com.survey.dto.TemplateCreateRequest;
import com.survey.entity.SurveyTemplate;
import com.survey.exception.SurveyException;
import com.survey.repository.SurveyTemplateRepository;
import com.survey.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyTemplateService {

    private final SurveyTemplateRepository templateRepository;
    private final HistoryService historyService;

    @Transactional
    public SurveyTemplate createTemplate(TemplateCreateRequest request) {
        log.info("创建问卷模板: {}", request.getTemplateName());

        SurveyTemplate template = new SurveyTemplate();
        template.setTemplateId(IdGenerator.generateTemplateId());
        template.setTemplateName(request.getTemplateName());
        template.setTemplateType(request.getTemplateType());
        template.setTemplateDescription(request.getTemplateDescription());
        template.setTemplateQuestions(request.getTemplateQuestions());
        template.setTemplateStatus(SurveyConstants.TEMPLATE_STATUS_ACTIVE);
        template.setCreatedAt(LocalDateTime.now());

        SurveyTemplate saved = templateRepository.save(template);
        historyService.recordSurveyHistory(saved.getTemplateId(), "CREATE_TEMPLATE",
                "创建问卷模板: " + request.getTemplateName(), null);
        log.info("问卷模板创建成功: {}", saved.getTemplateId());
        return saved;
    }

    @Transactional
    public SurveyTemplate updateTemplate(String templateId, TemplateCreateRequest request) {
        log.info("更新问卷模板: {}", templateId);

        SurveyTemplate template = templateRepository.findByTemplateId(templateId)
                .orElseThrow(() -> new SurveyException(404, "模板不存在: " + templateId));

        template.setTemplateName(request.getTemplateName());
        template.setTemplateType(request.getTemplateType());
        template.setTemplateDescription(request.getTemplateDescription());
        template.setTemplateQuestions(request.getTemplateQuestions());
        template.setUpdatedAt(LocalDateTime.now());

        SurveyTemplate saved = templateRepository.save(template);
        historyService.recordSurveyHistory(templateId, "UPDATE_TEMPLATE",
                "更新问卷模板: " + request.getTemplateName(), null);
        return saved;
    }

    @Transactional
    public void deactivateTemplate(String templateId) {
        log.info("停用问卷模板: {}", templateId);

        SurveyTemplate template = templateRepository.findByTemplateId(templateId)
                .orElseThrow(() -> new SurveyException(404, "模板不存在: " + templateId));

        template.setTemplateStatus(SurveyConstants.TEMPLATE_STATUS_INACTIVE);
        template.setUpdatedAt(LocalDateTime.now());
        templateRepository.save(template);
        historyService.recordSurveyHistory(templateId, "DEACTIVATE_TEMPLATE",
                "停用问卷模板: " + template.getTemplateName(), null);
    }

    @Transactional
    public void activateTemplate(String templateId) {
        log.info("启用问卷模板: {}", templateId);

        SurveyTemplate template = templateRepository.findByTemplateId(templateId)
                .orElseThrow(() -> new SurveyException(404, "模板不存在: " + templateId));

        template.setTemplateStatus(SurveyConstants.TEMPLATE_STATUS_ACTIVE);
        template.setUpdatedAt(LocalDateTime.now());
        templateRepository.save(template);
        historyService.recordSurveyHistory(templateId, "ACTIVATE_TEMPLATE",
                "启用问卷模板: " + template.getTemplateName(), null);
    }

    public List<SurveyTemplate> getAllTemplates() {
        return templateRepository.findAll();
    }

    public List<SurveyTemplate> getActiveTemplates() {
        return templateRepository.findByTemplateStatus(SurveyConstants.TEMPLATE_STATUS_ACTIVE);
    }

    public List<SurveyTemplate> getTemplatesByType(String templateType) {
        return templateRepository.findByTemplateType(templateType);
    }

    public SurveyTemplate getTemplate(String templateId) {
        return templateRepository.findByTemplateId(templateId)
                .orElseThrow(() -> new SurveyException(404, "模板不存在: " + templateId));
    }

    public boolean templateExists(String templateId) {
        return templateRepository.findByTemplateId(templateId).isPresent();
    }
}
