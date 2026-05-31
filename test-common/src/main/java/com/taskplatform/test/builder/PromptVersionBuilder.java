package com.taskplatform.test.builder;

import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.common.util.JsonUtil;
import com.taskplatform.persistence.entity.PromptVersion;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class PromptVersionBuilder {

    private String promptId;
    private Integer version = 1;
    private String name;
    private String content;
    private String template;
    private String variables;
    private String modelId = "model-default";
    private Double temperature = 0.7;
    private Integer maxTokens = 2048;
    private String description;
    private String tags;
    private String createdBy = "test-user";
    private Boolean isLatest = true;

    public static PromptVersionBuilder aPromptVersion() {
        return new PromptVersionBuilder();
    }

    public PromptVersionBuilder withPromptId(String promptId) {
        this.promptId = promptId;
        return this;
    }

    public PromptVersionBuilder withVersion(Integer version) {
        this.version = version;
        return this;
    }

    public PromptVersionBuilder withName(String name) {
        this.name = name;
        return this;
    }

    public PromptVersionBuilder withContent(String content) {
        this.content = content;
        return this;
    }

    public PromptVersionBuilder withTemplate(String template) {
        this.template = template;
        return this;
    }

    public PromptVersionBuilder withVariables(Map<String, Object> variables) {
        this.variables = JsonUtil.toJson(variables);
        return this;
    }

    public PromptVersionBuilder withModelId(String modelId) {
        this.modelId = modelId;
        return this;
    }

    public PromptVersionBuilder withTemperature(Double temperature) {
        this.temperature = temperature;
        return this;
    }

    public PromptVersionBuilder withMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    public PromptVersionBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public PromptVersionBuilder withTags(String tags) {
        this.tags = tags;
        return this;
    }

    public PromptVersionBuilder withCreatedBy(String createdBy) {
        this.createdBy = createdBy;
        return this;
    }

    public PromptVersionBuilder withIsLatest(Boolean isLatest) {
        this.isLatest = isLatest;
        return this;
    }

    public PromptVersion build() {
        PromptVersion prompt = new PromptVersion();
        prompt.setPromptId(promptId != null ? promptId : IdGenerator.generatePromptId());
        prompt.setVersion(version);
        prompt.setName(name != null ? name : "Test Prompt v" + version);
        prompt.setContent(content != null ? content : generateDefaultContent());
        prompt.setTemplate(template);
        prompt.setVariables(variables);
        prompt.setModelId(modelId);
        prompt.setTemperature(temperature);
        prompt.setMaxTokens(maxTokens);
        prompt.setDescription(description);
        prompt.setTags(tags);
        prompt.setCreatedBy(createdBy);
        prompt.setIsLatest(isLatest);
        prompt.setCreatedAt(LocalDateTime.now());
        prompt.setUpdatedAt(LocalDateTime.now());
        return prompt;
    }

    private String generateDefaultContent() {
        return "你是一个专业的助手，请根据用户的问题提供准确的回答。\n" +
               "用户问题: {{user_input}}\n" +
               "上下文信息: {{context}}\n" +
               "请用中文回答。";
    }

    public PromptVersion buildWithTemplate() {
        this.template = "你是一个{{role}}，请{{task}}。\n用户输入: {{input}}";
        this.variables = JsonUtil.toJson(Map.of(
                "role", "专业助手",
                "task", "回答用户问题",
                "input", "string"
        ));
        return build();
    }

    public PromptVersion buildOldVersion() {
        this.version = 1;
        this.isLatest = false;
        return build();
    }

    public PromptVersion buildLatestVersion() {
        this.version = 5;
        this.isLatest = true;
        return build();
    }
}
