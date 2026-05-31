package com.parking.platform.document.entity;

import com.parking.platform.common.entity.BaseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Document extends BaseEntity {

    private static final int DEFAULT_LIST_SIZE = 4;
    private static final int DEFAULT_MAP_SIZE = 8;

    private String title;
    private String content;
    private String summary;
    private String source;
    private String sourceId;
    private String contentType;
    private String url;
    private List<String> tags;
    private List<String> allowedRoles;
    private String owner;
    private Instant lastIndexedAt;
    private Double score;
    private Map<String, Object> metadata;

    public Document() {
        super();
    }

    @Override
    protected String getIdPrefix() {
        return "doc";
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = super.toMap();
        if (title != null) map.put("title", title);
        if (content != null) map.put("content", content);
        if (summary != null) map.put("summary", summary);
        if (source != null) map.put("source", source);
        if (sourceId != null) map.put("sourceId", sourceId);
        if (contentType != null) map.put("contentType", contentType);
        if (url != null) map.put("url", url);
        if (tags != null) map.put("tags", tags);
        if (allowedRoles != null) map.put("allowedRoles", allowedRoles);
        if (owner != null) map.put("owner", owner);
        if (lastIndexedAt != null) map.put("lastIndexedAt", lastIndexedAt);
        if (score != null) map.put("score", score);
        if (metadata != null) map.put("metadata", metadata);
        return map;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<String> getTags() {
        return tags == null ? Collections.emptyList() : tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public List<String> getAllowedRoles() {
        return allowedRoles == null ? Collections.emptyList() : allowedRoles;
    }

    public void setAllowedRoles(List<String> allowedRoles) {
        this.allowedRoles = allowedRoles;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Instant getLastIndexedAt() {
        return lastIndexedAt;
    }

    public void setLastIndexedAt(Instant lastIndexedAt) {
        this.lastIndexedAt = lastIndexedAt;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public Map<String, Object> getMetadata() {
        return metadata == null ? Collections.emptyMap() : metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public void addTag(String tag) {
        if (tags == null) {
            tags = new ArrayList<>(DEFAULT_LIST_SIZE);
        }
        tags.add(tag);
    }

    public void addAllowedRole(String role) {
        if (allowedRoles == null) {
            allowedRoles = new ArrayList<>(DEFAULT_LIST_SIZE);
        }
        allowedRoles.add(role);
    }

    public void putMetadata(String key, Object value) {
        if (metadata == null) {
            metadata = new HashMap<>(DEFAULT_MAP_SIZE);
        }
        metadata.put(key, value);
    }
}
