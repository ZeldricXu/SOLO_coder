package com.apishield.audit.dto;

import lombok.Data;
import java.util.List;

@Data
public class AuditVerifyResult {
    private boolean valid;
    private String message;
    private List<String> tamperedLogIds;
    private int verifiedCount;
    private int tamperedCount;
}
