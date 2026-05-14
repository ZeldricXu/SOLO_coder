package com.formflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;

@Data
@Embeddable
public class ProcessTransition {

    @Column(name = "from_node", nullable = false)
    private String fromNode;

    @Column(name = "to_node", nullable = false)
    private String toNode;

    @Column(name = "condition")
    private String condition;

    @Column(name = "condition_expression", length = 1000)
    private String conditionExpression;

    @Column(name = "sort_order")
    private Integer sortOrder;
}
