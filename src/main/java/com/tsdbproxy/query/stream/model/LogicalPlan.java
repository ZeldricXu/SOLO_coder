package com.tsdbproxy.query.stream.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LogicalPlan {
    private String operator;
    private List<String> projections;
    private List<String> tables;
    private String condition;
    private List<String> groupBy;
    private List<String> orderBy;
    private Integer limit;
    private List<LogicalPlan> children;
}
