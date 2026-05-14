package com.meeting.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InviteTask implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taskId;
    private String meetingId;
    private String userId;
    private String userName;
    private String userEmail;
    private String operatorId;
    private String importance;
    private int retryCount;
    private int maxRetryCount;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime lastRetryAt;
    private String errorMessage;

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
}
