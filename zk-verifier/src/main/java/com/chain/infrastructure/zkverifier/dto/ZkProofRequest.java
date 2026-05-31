package com.chain.infrastructure.zkverifier.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ZkProofRequest {

    private String circuitId;

    private String schemeType;

    private String proofData;

    private List<Object> publicInputs;

    private String verificationKey;

    private Map<String, Object> metadata;
}
