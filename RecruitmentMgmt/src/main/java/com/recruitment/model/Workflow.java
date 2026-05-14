package com.recruitment.model;

import com.recruitment.common.enums.InterviewType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "workflows")
public class Workflow {
    @Id
    @Column(name = "workflow_id", nullable = false, unique = true)
    private String workflowId;

    @Column(name = "workflow_name", nullable = false)
    private String workflowName;

    @Column(name = "position_type", nullable = false)
    private String positionType;

    @Column(name = "is_default")
    @Builder.Default
    private Boolean isDefault = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_stages", joinColumns = @JoinColumn(name = "workflow_id"))
    @Column(name = "stage_type")
    private List<InterviewType> stages;

    @Column(name = "screen_rules")
    @Lob
    private String screenRules;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (isDefault == null) {
            isDefault = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
