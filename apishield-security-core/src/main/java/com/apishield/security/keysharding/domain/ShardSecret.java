package com.apishield.security.keysharding.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShardSecret {
    private String keyId;
    private String originalSecretHash;
    private int threshold;
    private int totalShares;
    private List<KeyShare> shares;
    private String algorithm;
}
