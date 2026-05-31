package com.datamasker.interfaces.dto.tee;

import lombok.Data;

@Data
public class AttestationRequest {

    private String enclaveId;

    private String expectedMeasurement;
}
