package com.cicd.server.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "pipeline_templates")
public class PipelineTemplate extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "yaml_definition", columnDefinition = "TEXT", nullable = false)
    private String yamlDefinition;

    @Column(name = "parameters_json", columnDefinition = "TEXT")
    private String parametersJson;

    @Column(name = "is_builtin", nullable = false)
    private Boolean isBuiltin = false;

    @Column(name = "icon", length = 100)
    private String icon;
}
