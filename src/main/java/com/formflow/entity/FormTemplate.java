package com.formflow.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "form_templates")
public class FormTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", unique = true, nullable = false)
    private String templateId;

    @Column(name = "template_name", nullable = false)
    private String templateName;

    @Column(name = "description")
    private String description;

    @ElementCollection
    @CollectionTable(name = "form_template_fields", joinColumns = @JoinColumn(name = "template_id"))
    private List<FormTemplateField> fields = new ArrayList<>();

    @Column(name = "process_definition_id")
    private String processDefinitionId;

    @Column(name = "enabled")
    private Boolean enabled = true;

    @Column(name = "version")
    private Integer version = 1;

    @Column(name = "creator_id")
    private String creatorId;

    @Column(name = "creator_name")
    private String creatorName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
