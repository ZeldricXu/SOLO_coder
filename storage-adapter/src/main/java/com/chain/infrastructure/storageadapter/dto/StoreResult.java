package com.chain.infrastructure.storageadapter.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class StoreResult {

    private String objectId;

    private String storageNetwork;

    private String cid;

    private String contentHash;

    private String contentType;

    private Long size;

    private String pinStatus;

    private Map<String, Object> metadata;

    private String gatewayUrl;

    private LocalDateTime createdAt;
}
