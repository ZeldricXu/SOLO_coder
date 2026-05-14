package com.configcenter.common.entity;

import com.configcenter.common.enums.Environment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "config_group")
public class ConfigGroup {
    
    @Id
    @GenericGenerator(name = "uuid", strategy = "uuid")
    @GeneratedValue(generator = "uuid")
    @Column(name = "group_id", length = 36)
    private String groupId;

    @Column(name = "group_name", length = 100, nullable = false)
    private String groupName;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", length = 20, nullable = false)
    private Environment environment;

    @Column(name = "description", length = 500)
    private String description;

    @ElementCollection
    @CollectionTable(name = "config_group_applications", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "application")
    private List<String> applications = new ArrayList<>();
    
    @Column(name = "parallel_push_count")
    private Integer parallelPushCount;
    
    @Column(name = "group_type", length = 50)
    private String groupType;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}