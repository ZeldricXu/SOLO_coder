package com.solocoder.platform.notification.template;

import com.solocoder.platform.notification.model.NotificationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class TemplateRenderer {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{(.+?)}");
    private final Map<String, NotificationTemplate> templateStore = new ConcurrentHashMap<>();

    public void registerTemplate(NotificationTemplate template) {
        templateStore.put(template.getTemplateId(), template);
        log.info("Template registered: id={}, channel={}", template.getTemplateId(), template.getChannel());
    }

    public String render(String templateId, Map<String, Object> params) {
        NotificationTemplate template = templateStore.get(templateId);
        if (template == null) {
            throw new IllegalArgumentException("Template not found: " + templateId);
        }
        return renderTemplate(template.getContent(), params, template.getDefaultParams());
    }

    public String renderContent(String content, Map<String, Object> params) {
        return renderTemplate(content, params, Map.of());
    }

    private String renderTemplate(String content, Map<String, Object> params, Map<String, String> defaultParams) {
        Map<String, Object> effectiveParams = new ConcurrentHashMap<>();
        if (defaultParams != null) {
            effectiveParams.putAll(defaultParams);
        }
        if (params != null) {
            effectiveParams.putAll(params);
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = effectiveParams.get(key);
            String replacement = value != null ? value.toString() : matcher.group(0);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public NotificationTemplate getTemplate(String templateId) {
        return templateStore.get(templateId);
    }

    public java.util.Collection<NotificationTemplate> getAllTemplates() {
        return templateStore.values();
    }
}
