package com.projectservice.model.scaffold;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class ProjectTemplate {
    private String id;
    private String name;
    private String description;
    private String language;
    private String framework;
    private String version;
    private List<TemplateParameter> parameters;
    private Map<String, Object> structure;
    private List<String> tags;
    private String owner;
    private boolean isPublic;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ProjectTemplate() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getFramework() { return framework; }
    public void setFramework(String framework) { this.framework = framework; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public List<TemplateParameter> getParameters() { return parameters; }
    public void setParameters(List<TemplateParameter> parameters) { this.parameters = parameters; }
    public Map<String, Object> getStructure() { return structure; }
    public void setStructure(Map<String, Object> structure) { this.structure = structure; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean isPublic) { this.isPublic = isPublic; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
