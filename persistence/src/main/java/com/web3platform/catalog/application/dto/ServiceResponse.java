package com.web3platform.catalog.application.dto;

import com.web3platform.catalog.domain.model.ServiceEntry;
import com.web3platform.catalog.domain.model.ServiceStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class ServiceResponse {
    private UUID id;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ServiceResponse fromDomain(ServiceEntry service) {
        ServiceResponse response = new ServiceResponse();
        response.id = service.getId();
        response.name = service.getName();
        response.description = service.getDescription();
        response.language = service.getLanguage();
        response.owner = service.getOwner();
        response.team = service.getTeam();
        response.repositoryUrl = service.getRepositoryUrl();
        response.apiDocUrl = service.getApiDocUrl();
        response.status = service.getStatus();
        response.version = service.getVersion();
        response.tags = service.getTags();
        response.createdAt = service.getCreatedAt();
        response.updatedAt = service.getUpdatedAt();
        return response;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getLanguage() { return language; }
    public String getOwner() { return owner; }
    public String getTeam() { return team; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public String getApiDocUrl() { return apiDocUrl; }
    public ServiceStatus getStatus() { return status; }
    public String getVersion() { return version; }
    public List<String> getTags() { return tags; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
