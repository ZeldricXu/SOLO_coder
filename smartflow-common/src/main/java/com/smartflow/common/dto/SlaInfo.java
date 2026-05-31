package com.smartflow.common.dto;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class SlaInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long slaId;
    private Long relatedId;
    private String relatedType;
    private Long remainingTime;
    private LocalDateTime deadline;
    private Integer slaStatus;
    private Integer escalationLevel;
}
