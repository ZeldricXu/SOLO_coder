package com.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportQueryRequest {
    private String account_id;
    private String start_date;
    private String end_date;
    private String period;
}
