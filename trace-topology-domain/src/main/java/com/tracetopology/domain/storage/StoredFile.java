package com.tracetopology.domain.storage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredFile {

    private String fileId;
    private String bucket;
    private String path;
    private String fileName;
    private long size;
    private String contentType;
    private String status;
    private Map<String, String> metadata;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;
    private boolean archived;

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isActive() {
        return "active".equals(status) && !isExpired();
    }
}
