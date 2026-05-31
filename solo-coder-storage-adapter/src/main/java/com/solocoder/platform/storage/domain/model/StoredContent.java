package com.solocoder.platform.storage.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredContent {

    private Long id;
    private String contentId;
    private String contentHash;
    private StorageType storageType;
    private String network;
    private Long size;
    private String mimeType;
    private PinStatus pinStatus;
    private String pinLocation;
    private Integer replicationCount;
    private LocalDateTime expireTime;
    private Map<String, Object> metadata;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum StorageType {
        IPFS,
        ARWEAVE,
        FILECOIN
    }

    public enum PinStatus {
        PINNED,
        UNPINNED,
        PINNING,
        FAILED
    }
}
