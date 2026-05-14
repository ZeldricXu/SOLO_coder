package com.taskscheduler.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "task_dependency", indexes = {
    @Index(name = "idx_task_id_dep", columnList = "task_id"),
    @Index(name = "idx_depends_on", columnList = "depends_on")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Dependency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "task_id", length = 100, nullable = false)
    private String taskId;

    @Column(name = "depends_on", length = 100, nullable = false)
    private String dependsOn;

    @Column(name = "dependency_type", length = 50, nullable = false)
    private String dependencyType = "sequential";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
