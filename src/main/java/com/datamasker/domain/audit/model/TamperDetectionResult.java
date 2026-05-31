package com.datamasker.domain.audit.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TamperDetectionResult {

    private boolean verified;

    private int totalLogs;

    private List<Integer> tamperedIndices;

    private int tamperedCount;

    private LocalDateTime checkedAt;
}
