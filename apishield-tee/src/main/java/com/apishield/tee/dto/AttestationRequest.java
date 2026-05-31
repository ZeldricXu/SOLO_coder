package com.apishield.tee.dto;

import lombok.Data;

@Data
public class AttestationRequest {
    private String enclaveId;
    private String attestationType;
    private String challenge;
}
