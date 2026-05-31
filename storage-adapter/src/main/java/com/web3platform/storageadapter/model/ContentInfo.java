package com.web3platform.storageadapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentInfo {

    private String cid;
    private String storageType;
    private long sizeBytes;
    private String pinStatus;
    private LocalDateTime retrievedAt;
}
