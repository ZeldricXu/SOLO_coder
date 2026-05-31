package com.smartflow.common.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class AssignmentRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long ticketId;
    private String ticketType;
    private String requiredSkills;
    private Integer priority;
    private Long currentAssigneeId;
}
