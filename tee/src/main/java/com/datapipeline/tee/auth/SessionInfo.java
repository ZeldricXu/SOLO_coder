package com.datapipeline.tee.auth;

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
public class SessionInfo {

    private String token;
    private String subject;
    private Map<String, Object> claims;
    private Instant createdAt;
    private Instant expiresAt;

}
