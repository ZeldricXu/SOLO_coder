package com.apishield.shamir.api.dto;

import lombok.Data;

@Data
public class GenerateSharesRequest {
    private String secret;
    private int threshold;
    private int totalShares;
    private String keyId;
}
