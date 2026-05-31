package com.apishield.tee.dto;

import lombok.Data;

@Data
public class EncryptRequest {
    private String enclaveId;
    private String plainData;
    private String keyId;
}
