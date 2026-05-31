package com.web3platform.catalog.application.dto;

import com.web3platform.catalog.domain.model.ServiceStatus;

import java.util.List;

public class UpdateServiceRequest {
    private String name;
    private String description;
    private String language;
    private String owner;
    private String team;
    private String repositoryUrl;
    private String apiDocUrl;
    private ServiceStatus status;
    private String version;
    private List<String> tags;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public void setRepositoryUrl(String repositoryUrl) { this.repositoryUrl = repositoryUrl; }
    public String getApiDocUrl() { return apiDocUrl; }
    public void setApiDocUrl(String apiDocUrl) { this.apiDocUrl = apiDocUrl; }
    public ServiceStatus getStatus() { return status; }
    public void setStatus(ServiceStatus status) { this.status = status; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
}
