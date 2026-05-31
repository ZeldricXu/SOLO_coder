package com.datamasker.interfaces.dto.audit;

import lombok.Data;

import java.util.List;

@Data
public class VerificationResponse {

    private boolean verified;

    private int totalLogs;

    private int tamperedCount;

    private List<Integer> tamperedIndices;
}
