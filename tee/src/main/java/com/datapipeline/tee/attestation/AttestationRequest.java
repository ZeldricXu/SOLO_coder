package com.datapipeline.tee.attestation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttestationRequest {

    private String requestId;
    private String enclaveId;
    private String nonce;
    private Instant timestamp;

}
