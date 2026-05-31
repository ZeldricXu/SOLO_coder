package com.datamasker.interfaces.dto.tee;

import lombok.Data;

@Data
public class AttestationResponse {

    private String enclaveId;

    private boolean verified;

    private String measurementHash;

    private String expectedHash;

    private boolean signatureValid;
}
