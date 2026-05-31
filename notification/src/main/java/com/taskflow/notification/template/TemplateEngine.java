package com.taskflow.notification.template;

import freemarker.template.Configuration;
import freemarker.template.Template;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

@Slf4j
@Component
public class TemplateEngine {

    private final Configuration freemarkerConfig;

    public TemplateEngine() {
        this.freemarkerConfig = new Configuration(Configuration.VERSION_2_3_32);
        this.freemarkerConfig.setDefaultEncoding("UTF-8");
    }

    public String render(String templateContent, Map<String, Object> variables) {
        try {
            Template template = new Template("dynamic", new StringReader(templateContent), freemarkerConfig);
            StringWriter writer = new StringWriter();
            template.process(variables, writer);
            return writer.toString();
        } catch (Exception e) {
            log.error("Template rendering failed", e);
            return templateContent;
        }
    }

    public String renderSubject(String subjectTemplate, Map<String, Object> variables) {
        if (subjectTemplate == null) return null;
        return render(subjectTemplate, variables);
    }
}
