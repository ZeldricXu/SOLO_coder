package com.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewArrangeResponse {
    private String interviewId;
    private String status;
    private String interviewerName;

    @Builder.Default
    private Boolean reminderScheduled = false;
}
