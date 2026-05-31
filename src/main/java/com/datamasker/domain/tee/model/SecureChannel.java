package com.datamasker.domain.tee.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SecureChannel {

    private String channelId;

    private String enclaveId;

    private String sessionKey;

    private LocalDateTime establishedAt;

    private boolean active;
}
