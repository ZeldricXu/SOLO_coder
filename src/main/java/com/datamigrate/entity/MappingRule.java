package com.datamigrate.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

@Entity
@Table(name = "mapping_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MappingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    @JsonBackReference
    private MigrateTask task;

    @Column(name = "source_field", nullable = false)
    private String sourceField;

    @Column(name = "target_field", nullable = false)
    private String targetField;

    @Column(name = "transformation")
    private String transformation;

    @Column(name = "rule_order")
    private Integer ruleOrder;
}
