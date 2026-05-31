package com.smartflow.common.dto;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class AssignmentResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long assigneeId;
    private String assigneeName;
    private Integer matchScore;
    private Integer currentLoad;
    private LocalDateTime assignedAt;
    private String reason;
}
