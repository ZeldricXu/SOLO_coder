package com.survey.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatQueryResponse {

    private Integer answerCount;
    private Integer reviewedCount;
    private Double completionRate;
    private String questionStat;
}
