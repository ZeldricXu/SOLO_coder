package com.solocoder.platform.logging.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogLevelConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String loggerName;
    private String level;
    private String scope;
    private long ttlSeconds;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private String source;

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }
}
