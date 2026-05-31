package com.chainetl.modules.storage.dto;

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
public class RetrieveContentResponse {

    private String recordId;
    private String storageType;
    private String contentHash;
    private String contentUrl;
    private String content;
    private String pinStatus;
    private Long size;
    private Instant createdAt;
    private Map<String, Object> metadata;
}
