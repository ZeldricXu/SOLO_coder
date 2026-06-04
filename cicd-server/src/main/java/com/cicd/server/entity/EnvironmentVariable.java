package com.cicd.server.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "environment_variables")
public class EnvironmentVariable extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String value;

    @Column(name = "is_sensitive", nullable = false)
    private Boolean isSensitive = false;

    @Column(name = "is_masked", nullable = false)
    private Boolean isMasked = false;

    @Column(length = 500)
    private String description;
}
