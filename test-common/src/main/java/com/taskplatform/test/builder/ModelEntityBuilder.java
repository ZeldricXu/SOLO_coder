package com.taskplatform.test.builder;

import com.taskplatform.common.enums.StageType;
import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.persistence.entity.ModelEntity;

import java.time.LocalDateTime;

public class ModelEntityBuilder {

    private String modelId;
    private String name;
    private String description;
    private String latestVersion;
    private StageType stage = StageType.STAGING;
    private String modelType = "LLM";
    private String framework = "pytorch";
    private String createdBy = "test-user";

    public static ModelEntityBuilder aModel() {
        return new ModelEntityBuilder();
    }

    public ModelEntityBuilder withModelId(String modelId) {
        this.modelId = modelId;
        return this;
    }

    public ModelEntityBuilder withName(String name) {
        this.name = name;
        return this;
    }

    public ModelEntityBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public ModelEntityBuilder withLatestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
        return this;
    }

    public ModelEntityBuilder withStage(StageType stage) {
        this.stage = stage;
        return this;
    }

    public ModelEntityBuilder withModelType(String modelType) {
        this.modelType = modelType;
        return this;
    }

    public ModelEntityBuilder withFramework(String framework) {
        this.framework = framework;
        return this;
    }

    public ModelEntityBuilder withCreatedBy(String createdBy) {
        this.createdBy = createdBy;
        return this;
    }

    public ModelEntity build() {
        ModelEntity model = new ModelEntity();
        model.setModelId(modelId != null ? modelId : IdGenerator.generateModelId());
        model.setName(name != null ? name : "Test Model");
        model.setDescription(description);
        model.setLatestVersion(latestVersion);
        model.setStage(stage);
        model.setModelType(modelType);
        model.setFramework(framework);
        model.setCreatedBy(createdBy);
        model.setCreatedAt(LocalDateTime.now());
        model.setUpdatedAt(LocalDateTime.now());
        return model;
    }

    public ModelEntity buildStagingModel() {
        this.stage = StageType.STAGING;
        return build();
    }

    public ModelEntity buildProductionModel() {
        this.stage = StageType.PRODUCTION;
        this.latestVersion = "1.0.0";
        return build();
    }

    public ModelEntity buildArchivedModel() {
        this.stage = StageType.ARCHIVED;
        return build();
    }
}
