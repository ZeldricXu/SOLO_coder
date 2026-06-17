package com.designsystem.service;

import com.designsystem.common.enums.ComponentFramework;
import com.designsystem.entity.Component;
import com.designsystem.entity.DesignToken;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.designsystem.config.RabbitMQConfig.*;

@Service
public class CodeGenerationService {

    private final Configuration freemarkerConfig;
    private final DesignTokenService tokenService;
    private final ComponentService componentService;
    private final RabbitTemplate rabbitTemplate;

    public CodeGenerationService(Configuration freemarkerConfig, DesignTokenService tokenService,
                                 ComponentService componentService, RabbitTemplate rabbitTemplate) {
        this.freemarkerConfig = freemarkerConfig;
        this.tokenService = tokenService;
        this.componentService = componentService;
        this.rabbitTemplate = rabbitTemplate;
    }

    public Map<String, String> generateScaffold(ComponentFramework framework, List<String> componentNames,
                                                 String projectName, boolean includeTokens) throws Exception {
        Map<String, Object> model = new HashMap<>();
        model.put("projectName", projectName);
        model.put("framework", framework.getCode());
        model.put("timestamp", System.currentTimeMillis());

        List<Component> components = new ArrayList<>();
        for (String componentName : componentNames) {
            Component component = componentService.getComponentByNameAndFramework(componentName, framework.getCode());
            if (component != null) {
                components.add(component);
            }
        }
        model.put("components", components);

        if (includeTokens) {
            List<DesignToken> tokens = tokenService.getTokenTree();
            model.put("tokens", tokens);
        }

        Map<String, String> generatedFiles = new HashMap<>();

        String templatePrefix = framework.getCode() + "/";

        generatedFiles.put("package.json", generateFromTemplate(templatePrefix + "package.json.ftl", model));
        generatedFiles.put("README.md", generateFromTemplate(templatePrefix + "README.md.ftl", model));
        generatedFiles.put(".gitignore", generateFromTemplate(templatePrefix + "gitignore.ftl", model));

        if (includeTokens) {
            if (framework == ComponentFramework.REACT) {
                generatedFiles.put("src/styles/tokens.css", tokenService.exportTokens(
                        com.designsystem.common.enums.ExportFormat.CSS, null, null));
                generatedFiles.put("src/styles/tokens.js", tokenService.exportTokens(
                        com.designsystem.common.enums.ExportFormat.JS, null, null));
            } else {
                generatedFiles.put("src/styles/tokens.css", tokenService.exportTokens(
                        com.designsystem.common.enums.ExportFormat.CSS, null, null));
                generatedFiles.put("src/styles/tokens.scss", tokenService.exportTokens(
                        com.designsystem.common.enums.ExportFormat.SCSS, null, null));
            }
        }

        for (Component component : components) {
            String componentDir = "src/components/" + component.getName() + "/";
            Map<String, Object> componentModel = new HashMap<>(model);
            componentModel.put("component", component);

            if (framework == ComponentFramework.REACT) {
                generatedFiles.put(componentDir + "index.tsx",
                        generateFromTemplate(templatePrefix + "Component.tsx.ftl", componentModel));
                generatedFiles.put(componentDir + "index.css",
                        generateFromTemplate(templatePrefix + "Component.css.ftl", componentModel));
                generatedFiles.put(componentDir + "index.test.tsx",
                        generateFromTemplate(templatePrefix + "Component.test.tsx.ftl", componentModel));
                generatedFiles.put(componentDir + "README.md",
                        generateFromTemplate(templatePrefix + "Component.md.ftl", componentModel));
            } else {
                generatedFiles.put(componentDir + "index.vue",
                        generateFromTemplate(templatePrefix + "Component.vue.ftl", componentModel));
                generatedFiles.put(componentDir + "index.css",
                        generateFromTemplate(templatePrefix + "Component.css.ftl", componentModel));
                generatedFiles.put(componentDir + "index.test.ts",
                        generateFromTemplate(templatePrefix + "Component.test.ts.ftl", componentModel));
                generatedFiles.put(componentDir + "README.md",
                        generateFromTemplate(templatePrefix + "Component.md.ftl", componentModel));
            }
        }

        Map<String, Object> event = new HashMap<>();
        event.put("projectName", projectName);
        event.put("framework", framework.getCode());
        event.put("components", componentNames);
        rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_CODE_GENERATE, event);

        return generatedFiles;
    }

    public byte[] downloadScaffold(Map<String, String> files) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zos.putNextEntry(zipEntry);
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    @Transactional(rollbackFor = Exception.class)
    public String pushToGitRepository(Map<String, String> files, String gitUrl, String branch,
                                      String username, String password, String commitMessage) throws Exception {
        Path tempDir = Files.createTempDirectory("design-system-scaffold-");

        try {
            Git git;
            try {
                git = Git.cloneRepository()
                        .setURI(gitUrl)
                        .setDirectory(tempDir.toFile())
                        .setBranch(branch)
                        .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password))
                        .call();
            } catch (GitAPIException e) {
                git = Git.init().setDirectory(tempDir.toFile()).call();
                git.branchRename().setNewName(branch).call();
            }

            for (Map.Entry<String, String> entry : files.entrySet()) {
                Path filePath = tempDir.resolve(entry.getKey());
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, entry.getValue(), StandardCharsets.UTF_8);
                git.add().addFilepattern(entry.getKey()).call();
            }

            git.commit()
                    .setMessage(commitMessage != null ? commitMessage : "feat: add design system scaffold")
                    .call();

            if (!git.getRepository().getRemoteNames().contains("origin")) {
                git.remoteAdd().setName("origin").setUri(new org.eclipse.jgit.transport.URIish(gitUrl)).call();
            }

            git.push()
                    .setRemote("origin")
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password))
                    .call();

            return gitUrl + "/tree/" + branch;
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    private String generateFromTemplate(String templateName, Map<String, Object> model) throws Exception {
        try {
            Template template = freemarkerConfig.getTemplate(templateName);
            StringWriter writer = new StringWriter();
            template.process(model, writer);
            return writer.toString();
        } catch (Exception e) {
            return generateDefaultContent(templateName, model);
        }
    }

    private String generateDefaultContent(String templateName, Map<String, Object> model) {
        String framework = (String) model.get("framework");
        String projectName = (String) model.get("projectName");

        if (templateName.endsWith("package.json.ftl")) {
            return """
                    {
                      "name": "%s",
                      "version": "0.1.0",
                      "private": true,
                      "dependencies": {
                        %s
                      },
                      "devDependencies": {
                        %s
                      }
                    }
                    """.formatted(
                    projectName,
                    "react".equals(framework)
                            ? "\"react\": \"^18.2.0\", \"react-dom\": \"^18.2.0\""
                            : "\"vue\": \"^3.4.0\"",
                    "react".equals(framework)
                            ? "\"@types/react\": \"^18.2.0\", \"typescript\": \"^5.0.0\""
                            : "\"vue-tsc\": \"^1.8.0\", \"typescript\": \"^5.0.0\""
            );
        }

        if (templateName.endsWith("README.md.ftl")) {
            return "# " + projectName + "\n\nGenerated by Design System Platform\n";
        }

        if (templateName.endsWith("gitignore.ftl")) {
            return "node_modules\ndist\n.env\n.DS_Store\n*.log\n";
        }

        if (templateName.contains("Component.tsx.ftl")) {
            Component component = (Component) model.get("component");
            return """
                    import React from 'react';
                    import './index.css';
                    
                    export interface %sProps {
                      className?: string;
                      children?: React.ReactNode;
                    }
                    
                    export const %s: React.FC<%sProps> = ({ className, children }) => {
                      return (
                        <div className={\\`ds-%s ${className || ''}\\`}>
                          {children}
                        </div>
                      );
                    };
                    
                    export default %s;
                    """.formatted(component.getName(), component.getName(),
                    component.getName(), component.getName().toLowerCase(), component.getName());
        }

        if (templateName.contains("Component.vue.ftl")) {
            Component component = (Component) model.get("component");
            return """
                    <template>
                      <div :class="['ds-%s', className]">
                        <slot />
                      </div>
                    </template>
                    
                    <script setup lang="ts">
                    interface Props {
                      className?: string
                    }
                    
                    withDefaults(defineProps<Props>(), {
                      className: ''
                    })
                    </script>
                    
                    <style scoped src="./index.css" />
                    """.formatted(component.getName().toLowerCase());
        }

        if (templateName.contains("Component.css.ftl")) {
            Component component = (Component) model.get("component");
            return """
                    .ds-%s {
                      padding: var(--ds-spacing-md, 16px);
                      border-radius: var(--ds-radius-md, 8px);
                      background-color: var(--ds-color-bg, #ffffff);
                    }
                    """.formatted(component.getName().toLowerCase());
        }

        return "";
    }

    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }
}
