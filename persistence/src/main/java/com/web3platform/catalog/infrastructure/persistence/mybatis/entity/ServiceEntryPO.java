package com.web3platform.catalog.infrastructure.persistence.mybatis.entity;

import com.web3platform.catalog.domain.model.ServiceEntry;
import com.web3platform.catalog.domain.model.ServiceStatus;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class ServiceEntryPO {
    private String id;
    private String name;
    private String description;
    private String language;
    private String owner;
    private String team;
    private String repositoryUrl;
    private String apiDocUrl;
    private String status;
    private String version;
    private String tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ServiceEntryPO fromDomain(ServiceEntry service) {
        ServiceEntryPO po = new ServiceEntryPO();
        po.id = service.getId().toString();
        po.name = service.getName();
        po.description = service.getDescription();
        po.language = service.getLanguage();
        po.owner = service.getOwner();
        po.team = service.getTeam();
        po.repositoryUrl = service.getRepositoryUrl();
        po.apiDocUrl = service.getApiDocUrl();
        po.status = service.getStatus().name();
        po.version = service.getVersion();
        po.tags = String.join(",", service.getTags());
        po.createdAt = service.getCreatedAt();
        po.updatedAt = service.getUpdatedAt();
        return po;
    }

    public ServiceEntry toDomain() {
        List<String> tagList = tags != null && !tags.isEmpty()
            ? Arrays.asList(tags.split(","))
            : Collections.emptyList();
        return new ServiceEntry(
            UUID.fromString(id),
            name,
            description,
            language,
            owner,
            team,
            repositoryUrl,
            apiDocUrl,
            ServiceStatus.valueOf(status),
            version,
            tagList,
            createdAt,
            updatedAt
        );
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
