package com.exam.vo;

import lombok.Data;

@Data
public class SubmitProgressVO {
    private Long examId;
    private Integer totalCandidates;
    private Integer submittedCount;
    private Integer unsubmittedCount;
    private Double submitRate;
    private Integer objectiveGradedCount;
    private Integer allGradedCount;
}
