package com.apishield.shamir.dto;

import lombok.Data;
import java.util.Map;

@Data
public class ShamirRecoverRequest {
    private String keyId;
    private Map<Integer, String> shares;
    private int threshold;
}
