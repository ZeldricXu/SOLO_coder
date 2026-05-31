package com.apishield.audit.api.dto;

import lombok.Data;
import java.util.List;

@Data
public class VerifyAuditRequest {
    private List<String> logIds;
    private Integer startHeight;
    private Integer endHeight;
    private boolean verifyFullChain;
}
