package com.example.mailservice.service;

import com.example.mailservice.model.MailTemplate;
import com.example.mailservice.repository.MailTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final MailTemplateRepository templateRepository;

    @Transactional
    public MailTemplate createTemplate(MailTemplate template) {
        template.setTemplateId("tpl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        if (template.getEnabled() == null) {
            template.setEnabled(true);
        }
        return templateRepository.save(template);
    }

    public Optional<MailTemplate> getTemplateByTemplateId(String templateId) {
        return templateRepository.findByTemplateId(templateId);
    }

    public Optional<MailTemplate> getTemplateByName(String templateName) {
        return templateRepository.findByTemplateName(templateName);
    }

    public List<MailTemplate> getAllEnabledTemplates() {
        return templateRepository.findByEnabledTrue();
    }

    @Transactional
    public MailTemplate updateTemplate(String templateId, MailTemplate updatedTemplate) {
        Optional<MailTemplate> existingOpt = templateRepository.findByTemplateId(templateId);
        if (!existingOpt.isPresent()) {
            return null;
        }
        MailTemplate existing = existingOpt.get();
        existing.setTemplateName(updatedTemplate.getTemplateName());
        existing.setTemplateSubject(updatedTemplate.getTemplateSubject());
        existing.setTemplateContent(updatedTemplate.getTemplateContent());
        existing.setVariables(updatedTemplate.getVariables());
        existing.setEnabled(updatedTemplate.getEnabled());
        return templateRepository.save(existing);
    }

    @Transactional
    public void deleteTemplate(String templateId) {
        Optional<MailTemplate> templateOpt = templateRepository.findByTemplateId(templateId);
        templateOpt.ifPresent(templateRepository::delete);
    }

    public RenderedTemplate renderTemplate(String templateId, Map<String, String> variables) {
        Optional<MailTemplate> templateOpt = templateRepository.findByTemplateId(templateId);
        if (!templateOpt.isPresent()) {
            return null;
        }

        MailTemplate template = templateOpt.get();
        String subject = replaceVariables(template.getTemplateSubject(), variables);
        String content = replaceVariables(template.getTemplateContent(), variables);

        return RenderedTemplate.builder()
                .templateId(templateId)
                .subject(subject)
                .content(content)
                .build();
    }

    private String replaceVariables(String text, Map<String, String> variables) {
        if (text == null || variables == null) {
            return text;
        }

        String result = text;
        Pattern pattern = Pattern.compile("\\{\\{(\\w+)\\}\\}");
        Matcher matcher = pattern.matcher(result);

        while (matcher.find()) {
            String varName = matcher.group(1);
            String varValue = variables.get(varName);
            if (varValue != null) {
                result = result.replace("{{" + varName + "}}", varValue);
            }
        }

        return result;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RenderedTemplate {
        private String templateId;
        private String subject;
        private String content;
    }
}
