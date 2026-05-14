package com.mobilestore.dto;

import lombok.Data;

@Data
public class FeedbackProcessRequest {

    private String status;
    private String processingNote;
    private String assignee;
}
