package com.configcenter.common.dto;

import com.configcenter.common.enums.Environment;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class GroupDTO {
    
    private String groupId;
    private String groupName;
    private Environment environment;
    private String description;
    private List<String> applications;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
}
