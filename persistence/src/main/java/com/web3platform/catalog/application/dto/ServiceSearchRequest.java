package com.web3platform.catalog.application.dto;

import com.web3platform.catalog.domain.model.ServiceStatus;

import java.util.List;

public class ServiceSearchRequest {
    private String keyword;
    private String language;
    private String team;
    private ServiceStatus status;
    private List<String> tags;
    private int page;
    private int pageSize;

    public ServiceSearchRequest() {
        this.page = 0;
        this.pageSize = 20;
    }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }
    public ServiceStatus getStatus() { return status; }
    public void setStatus(ServiceStatus status) { this.status = status; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
}
