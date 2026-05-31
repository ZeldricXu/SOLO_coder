package com.edgescheduler.domain.vo;

import lombok.Data;
import java.util.Map;

@Data
public class ResourceVO {
    private String id;
    private String type;
    private String status;
    private Double progress;
    private Map<String, Object> attributes;
    private String createdAt;
    private String updatedAt;
}
