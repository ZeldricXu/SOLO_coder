package com.apishield.shamir.domain.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ShamirKeyShare {
    private String id;
    private String keyId;
    private int shareIndex;
    private String shareValue;
    private String ownerId;
    private int threshold;
    private int totalShares;
    private ShareStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum ShareStatus {
        GENERATED, DISTRIBUTED, REVOKED, EXPIRED
    }
}
