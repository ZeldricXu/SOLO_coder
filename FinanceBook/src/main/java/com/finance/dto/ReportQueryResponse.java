package com.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportQueryResponse {
    private ReportInfo report;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportInfo {
        private BigDecimal income;
        private BigDecimal expense;
        private BigDecimal balance;
        private String period;
    }
}
