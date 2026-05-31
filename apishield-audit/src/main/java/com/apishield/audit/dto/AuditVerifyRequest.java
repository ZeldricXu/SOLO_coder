package com.apishield.audit.dto;

import lombok.Data;
import java.util.List;

@Data
public class AuditVerifyRequest {
    private List<String> logIds;
    private int startHeight;
    private int endHeight;
}
