package com.adplatform.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ad_placement")
public class AdPlacement {
    @Id
    @Column(name = "placement_id", length = 50)
    private String placementId;

    @Column(name = "ad_id", length = 50, nullable = false)
    private String adId;

    @Column(name = "placement_channel", length = 100, nullable = false)
    private String placementChannel;

    @Column(name = "placement_position", length = 100, nullable = false)
    private String placementPosition;

    @Column(name = "placement_start", nullable = false)
    private LocalDateTime placementStart;

    @Column(name = "placement_end", nullable = false)
    private LocalDateTime placementEnd;

    @Column(name = "placement_status", length = 50, nullable = false)
    private String placementStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
