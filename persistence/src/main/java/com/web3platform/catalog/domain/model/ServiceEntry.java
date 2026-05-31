package com.web3platform.catalog.domain.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class ServiceEntry {
    private final UUID id;
    private String name;
    private String description;
    private String language;
    private String owner;
    private String team;
    private String repositoryUrl;
    private String apiDocUrl;
    private ServiceStatus status;
    private String version;
    private final List<String> tags;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ServiceEntry(UUID id, String name, String description, String language, 
                        String owner, String team, String repositoryUrl, String apiDocUrl,
                        ServiceStatus status, String version, List<String> tags,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.language = language;
        this.owner = owner;
        this.team = team;
        this.repositoryUrl = repositoryUrl;
        this.apiDocUrl = apiDocUrl;
        this.status = status;
        this.version = version;
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ServiceEntry create(String name, String description, String language,
                                      String owner, String team, String repositoryUrl,
                                      String version) {
        return new ServiceEntry(
            UUID.randomUUID(),
            name,
            description,
            language,
            owner,
            team,
            repositoryUrl,
            null,
            ServiceStatus.DEVELOPMENT,
            version,
            new ArrayList<>(),
            LocalDateTime.now(),
            LocalDateTime.now()
        );
    }

    public void update(String name, String description, String language,
                       String owner, String team, String repositoryUrl,
                       String apiDocUrl, ServiceStatus status, String version) {
        this.name = name;
        this.description = description;
        this.language = language;
        this.owner = owner;
        this.team = team;
        this.repositoryUrl = repositoryUrl;
        this.apiDocUrl = apiDocUrl;
        this.status = status;
        this.version = version;
        this.updatedAt = LocalDateTime.now();
    }

    public void addTag(String tag) {
        if (!this.tags.contains(tag)) {
            this.tags.add(tag);
            this.updatedAt = LocalDateTime.now();
        }
    }

    public void removeTag(String tag) {
        this.tags.remove(tag);
        this.updatedAt = LocalDateTime.now();
    }

    public void deprecate() {
        this.status = ServiceStatus.DEPRECATED;
        this.updatedAt = LocalDateTime.now();
    }

    public void activate() {
        this.status = ServiceStatus.ACTIVE;
        this.updatedAt = LocalDateTime.now();
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
    public List<String> getTags() { return Collections.unmodifiableList(tags); }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
