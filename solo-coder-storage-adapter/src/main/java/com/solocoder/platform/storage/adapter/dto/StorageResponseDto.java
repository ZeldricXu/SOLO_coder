package com.solocoder.platform.storage.adapter.dto;

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
public class StorageResponseDto {

    private String contentId;
    private String contentHash;
    private String storageType;
    private String network;
    private Long size;
    private String mimeType;
    private String pinStatus;
    private String pinLocation;
    private Integer replicationCount;
    private LocalDateTime expireTime;
    private Map<String, Object> metadata;
    private String gatewayUrl;
    private String createdBy;
    private LocalDateTime createdAt;
}
