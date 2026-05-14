package com.assetmanage.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "asset_history")
public class AssetHistory {

    @Id
    @Column(name = "history_id", length = 64)
    private String historyId;

    @Column(name = "asset_id")
    private String assetId;

    @Column(name = "action_type")
    private String actionType;

    @Column(name = "action_details", columnDefinition = "TEXT")
    private String actionDetails;

    @Column(name = "operator_id")
    private String operatorId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
