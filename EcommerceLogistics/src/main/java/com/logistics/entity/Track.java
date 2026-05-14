package com.logistics.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "tracks")
public class Track {

    @Id
    @Column(name = "track_id", nullable = false, unique = true)
    private String trackId;

    @Column(name = "logistics_id", nullable = false)
    private String logisticsId;

    @Column(name = "track_status", nullable = false)
    private String trackStatus;

    @Column(name = "track_location", nullable = false)
    private String trackLocation;

    @Column(name = "track_time", nullable = false)
    private LocalDateTime trackTime;

    @Column(name = "track_detail")
    private String trackDetail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (trackTime == null) {
            trackTime = LocalDateTime.now();
        }
    }
}
