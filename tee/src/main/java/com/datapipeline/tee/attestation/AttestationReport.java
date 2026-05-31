package com.datapipeline.tee.attestation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttestationReport {

    private String reportId;
    private String enclaveId;
    private RemoteAttestationService.AttestationResult result;
    private Instant timestamp;
    private List<String> reasons;
    private String mrenclave;
    private String mrsigner;
    private int isvProdId;
    private int isvSvn;

}
