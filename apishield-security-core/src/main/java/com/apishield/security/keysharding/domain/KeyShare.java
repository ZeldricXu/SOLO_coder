package com.apishield.security.keysharding.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyShare {
    private String shareId;
    private String keyId;
    private int shareIndex;
    private String shareValue;
    private String ownerId;
    private LocalDateTime createdAt;
    private LocalDateTime expiredAt;
    private boolean active;
}
