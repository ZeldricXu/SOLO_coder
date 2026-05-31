package com.apishield.shamir.dto;

import lombok.Data;

@Data
public class ShamirGenerateRequest {
    private String secret;
    private int threshold;
    private int totalShares;
    private String keyId;
}
