package com.apishield.shamir.api.dto;

import lombok.Data;
import java.util.Map;

@Data
public class RecoverSecretRequest {
    private String keyId;
    private Map<Integer, String> shares;
    private int threshold;
}
