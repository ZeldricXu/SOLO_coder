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
@Table(name = "process_definitions")
public class ProcessDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "process_id", unique = true, nullable = false)
    private String processId;

    @Column(name = "process_name", nullable = false)
    private String processName;

    @Column(name = "description")
    private String description;

    @ElementCollection
    @CollectionTable(name = "process_definition_nodes", joinColumns = @JoinColumn(name = "process_definition_id"))
    private List<ProcessNode> nodes = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "process_definition_transitions", joinColumns = @JoinColumn(name = "process_definition_id"))
    private List<ProcessTransition> transitions = new ArrayList<>();

    @Column(name = "start_node_id", nullable = false)
    private String startNodeId;

    @Column(name = "end_node_id", nullable = false)
    private String endNodeId;

    @Column(name = "version")
    private Integer version = 1;

    @Column(name = "enabled")
    private Boolean enabled = true;

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
